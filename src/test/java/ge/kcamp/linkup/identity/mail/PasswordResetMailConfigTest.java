package ge.kcamp.linkup.identity.mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordResetMailConfigTest {

    private final PasswordResetMailConfig config = new PasswordResetMailConfig();
    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        // Boot creates the sender even when spring.mail.host is empty; model that.
        when(provider.getIfAvailable()).thenReturn(javaMailSender);
        return provider;
    }

    @Test
    void anEmptyHostIsNotAMailServerEvenThoughBootCreatedASender() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        PasswordResetMailer mailer = config.passwordResetMailer(provider(), prod, "", "", "");

        mailer.sendResetCode("nino@example.com", "123456", 15);

        verifyNoInteractions(javaMailSender);
    }

    @Test
    void aConfiguredHostWithNoFromAddressFailsStartup() {
        assertThatThrownBy(() -> config.passwordResetMailer(
                provider(), new MockEnvironment(), "smtp.example.com", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LINKUP_MAIL_FROM");
    }

    @Test
    void aConfiguredHostSendsTheCodeInTheBodyAndReturnsPromptly() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<SimpleMailMessage> message = new AtomicReference<>();
        doAnswer(invocation -> {
            message.set(invocation.getArgument(0));
            sent.countDown();
            return null;
        }).when(javaMailSender).send(any(SimpleMailMessage.class));

        PasswordResetMailer mailer = config.passwordResetMailer(
                provider(), new MockEnvironment(), "smtp.example.com", "hello@linkup.app", "user");
        mailer.sendResetCode("nino@example.com", "123456", 15);

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(message.get().getFrom()).isEqualTo("hello@linkup.app");
        assertThat(message.get().getTo()).containsExactly("nino@example.com");
        assertThat(message.get().getText()).contains("123456").contains("15 minutes");
    }

    @Test
    void aFailingSmtpServerNeverSurfacesToTheCaller() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            attempted.countDown();
            throw new org.springframework.mail.MailSendException("connection refused");
        }).when(javaMailSender).send(any(SimpleMailMessage.class));

        PasswordResetMailer mailer = config.passwordResetMailer(
                provider(), new MockEnvironment(), "smtp.example.com", "", "hello@linkup.app");
        mailer.sendResetCode("nino@example.com", "123456", 15);

        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
