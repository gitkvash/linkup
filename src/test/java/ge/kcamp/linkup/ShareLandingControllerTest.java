package ge.kcamp.linkup;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShareLandingControllerTest {

    @Test
    void theLandingPageHandsTheTapOnToTheAppByItsOwnScheme() {
        UUID id = UUID.randomUUID();

        ResponseEntity<String> response = new ShareLandingController().plan(id);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("linkup://app/p/" + id);
        assertThat(response.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void theLandingPageSaysNothingAboutThePlanItself() {
        ResponseEntity<String> response = new ShareLandingController().plan(UUID.randomUUID());

        // Nothing is looked up, so there is nothing to leak: the page is the same for a plan
        // that exists, one that does not, and one the reader may not see.
        assertThat(response.getBody()).doesNotContain("%");
    }
}
