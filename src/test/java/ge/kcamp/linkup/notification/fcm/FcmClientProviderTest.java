package ge.kcamp.linkup.notification.fcm;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class FcmClientProviderTest {

    @Test
    void blankCredentialsPathYieldsEmptyClientWithoutThrowing() {
        FcmClientProvider provider = new FcmClientProvider(new MockEnvironment(), "");
        provider.init();

        assertThat(provider.getClient()).isEmpty();
    }

    @Test
    void missingCredentialsFileYieldsEmptyClientWithoutThrowing() {
        FcmClientProvider provider = new FcmClientProvider(new MockEnvironment(), "/no/such/file/credentials.json");
        provider.init();

        assertThat(provider.getClient()).isEmpty();
    }

    @Test
    void nullCredentialsPathYieldsEmptyClientWithoutThrowing() {
        FcmClientProvider provider = new FcmClientProvider(new MockEnvironment(), null);
        provider.init();

        assertThat(provider.getClient()).isEmpty();
    }
}
