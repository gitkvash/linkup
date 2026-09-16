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
     */
    @Bean
    HawkingParserPool hawkingParserPool(
            @Value("${linkup.nlp.parser-pool-size:2}") int poolSize,
            @Value("${linkup.nlp.parser-borrow-timeout-ms:20000}") long borrowTimeoutMillis) {
        return new HawkingParserPool(poolSize, borrowTimeoutMillis);
    }
}
