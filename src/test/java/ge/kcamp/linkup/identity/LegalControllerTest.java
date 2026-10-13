package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.controller.LegalController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegalControllerTest {

    private final LegalController configured = new LegalController("Linkup LLC", "legal@example.com");

    @Test
    void bothDocumentsNameTheOperatorAndHowToReachThem() {
        for (String page : new String[] {configured.terms().getBody(), configured.privacy().getBody()}) {
            assertThat(page).contains("Linkup LLC").contains("mailto:legal@example.com");
        }
    }

    @Test
    void bothDocumentsPrintTheVersionAndAgeTheSignUpRecords() {
        for (String page : new String[] {configured.terms().getBody(), configured.privacy().getBody()}) {
            assertThat(page)
                    .contains(LegalDocuments.CURRENT_VERSION)
                    .contains(Integer.toString(LegalDocuments.MINIMUM_AGE))
                    .doesNotContain("{{");
        }
    }

    @Test
    void anUnconfiguredDeploymentSaysSoInsteadOfNamingNobody() {
        var page = new LegalController("", "").privacy().getBody();

        assertThat(page).contains("not yet named").contains("not yet set").doesNotContain("{{");
    }

    @Test
    void configuredValuesCannotInjectMarkup() {
        var page = new LegalController("<script>x</script>", "a@b.c\"><i>").terms().getBody();

        assertThat(page).doesNotContain("<script>x").doesNotContain("<i>");
    }

    @Test
    void theCurrentVersionIsTheOnlyOneAccepted() {
        LegalDocuments.requireCurrent(LegalDocuments.CURRENT_VERSION);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LegalDocuments.requireCurrent(null))
                .isInstanceOf(ge.kcamp.linkup.identity.exception.TermsNotAcceptedException.class);
    }
}
