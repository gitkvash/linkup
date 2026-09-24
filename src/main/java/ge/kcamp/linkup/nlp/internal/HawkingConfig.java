package ge.kcamp.linkup.nlp.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class HawkingConfig {

    /**
     * A pool, not a single parser: Hawking wraps a Stanford CoreNLP pipeline, which is
     * expensive to build (so it can't be per-request) and not thread-safe (so it can't be
     * a bare shared singleton, which is what it was).
     * <p>
     * The borrow wait is short on purpose. It happens on the request thread, and with a
     * 20 s wait, a burst of a couple of hundred free-text creates queued behind two
     * multi-second parses left every Tomcat thread parked in {@code poll()}, so the
     * whole API stopped answering, not just this endpoint. Two seconds covers a
     * normal queue behind one parse; past that the caller gets a 503 ("try again") via
     * {@code NlpUnavailableException} and the thread goes back to serving requests.
     */
    @Bean
    HawkingParserPool hawkingParserPool(
            @Value("${linkup.nlp.parser-pool-size:2}") int poolSize,
            @Value("${linkup.nlp.parser-borrow-timeout-ms:2000}") long borrowTimeoutMillis) {
        return new HawkingParserPool(poolSize, borrowTimeoutMillis);
    }
}
