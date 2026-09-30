package ge.kcamp.linkup.identity.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Picks how reset codes leave the server, once, at startup:
 * <ul>
 *   <li>{@code spring.mail.host} set: real SMTP ({@code LINKUP_MAIL_HOST} and friends).</li>
 *   <li>Otherwise in {@code dev}: the code is written to the log, so the whole flow can be
 *       exercised on a laptop without a mail account.</li>
 *   <li>Otherwise: nothing is sent, and startup says so. Failing startup was the
 *       alternative and is how {@code LINKUP_JWT_SECRET} behaves, but a missing mail
 *       account should not take the whole API down - only this one feature is off, and
 *       it must never fall back to logging the code, which would put every reset code
 *       into the deployment's logs.</li>
 * </ul>
 * One factory rather than three conditional beans: {@code @ConditionalOnMissingBean} is
 * only dependable in auto-configuration, and this decision reads three inputs.
 */
@Configuration
public class PasswordResetMailConfig {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailConfig.class);

    @Bean
    PasswordResetMailer passwordResetMailer(
            ObjectProvider<JavaMailSender> mailSender,
            Environment environment,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${linkup.mail.from:}") String from,
            @Value("${spring.mail.username:}") String smtpUsername) {
        // An empty host still creates Boot's JavaMailSender (the property merely exists), so
        // the value is what decides, not the bean.
        JavaMailSender sender = mailHost.isBlank() ? null : mailSender.getIfAvailable();
        if (sender != null) {
            String sentFrom = !from.isBlank() ? from : smtpUsername;
            if (sentFrom.isBlank()) {
                throw new IllegalStateException(
                        "spring.mail.host is set but neither linkup.mail.from (LINKUP_MAIL_FROM) nor "
                                + "spring.mail.username is: there is no address to send reset codes from");
            }
            return new SmtpPasswordResetMailer(sender, sentFrom, sendQueue());
        }
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            log.info("No mail server configured: password reset codes will be logged, not sent (dev only)");
            return (to, code, minutes) ->
                    log.warn("DEV password reset code for {}: {} (valid {} min)", to, code, minutes);
        }
        log.warn("No mail server configured (LINKUP_MAIL_HOST): password reset is DISABLED - "
                + "requests are accepted and no code is sent");
        return (to, code, minutes) ->
                log.warn("Password reset requested but no mail server is configured; nothing sent");
    }

    /**
     * Its own small pool, not {@code applicationTaskExecutor}: an SMTP server that hangs for
     * its timeout would otherwise sit on a thread that event listeners need. Bounded, and a
     * full queue drops the mail with a warning rather than slowing the request.
     */
    private static ThreadPoolTaskExecutor sendQueue() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("linkup-mail-");
        // Not a bean, so nothing shuts it down: daemon threads keep it from holding the JVM open.
        executor.setDaemon(true);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler((task, pool) -> {
            throw new RejectedExecutionException("mail queue is full");
        });
        executor.initialize();
        return executor;
    }

    static final class SmtpPasswordResetMailer implements PasswordResetMailer {

        private final JavaMailSender sender;
        private final String from;
        private final ThreadPoolTaskExecutor queue;

        SmtpPasswordResetMailer(JavaMailSender sender, String from, ThreadPoolTaskExecutor queue) {
            this.sender = sender;
            this.from = from;
            this.queue = queue;
        }

        @Override
        public void sendResetCode(String toEmail, String code, int validForMinutes) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(toEmail);
            message.setSubject("Your Linkup password reset code");
            message.setText("Your Linkup code is " + code + "\n\n"
                    + "It works for " + validForMinutes + " minutes. If you didn't ask to reset your "
                    + "password, ignore this email - your password has not changed.");
            try {
                queue.execute(() -> {
                    try {
                        sender.send(message);
                    } catch (RuntimeException e) {
                        // Not the address, and not the code: both are secrets or personal data.
                        log.error("Could not send a password reset email: {}", e.toString());
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("Password reset email dropped: the mail queue is full");
            }
        }
    }
}
