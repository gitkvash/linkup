package ge.kcamp.linkup.identity.controller;

import ge.kcamp.linkup.identity.LegalDocuments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The Terms of Service and the Privacy Policy, at {@code /legal/terms} and
 * {@code /legal/privacy}. Public, because a person has to be able to read them before an
 * account exists and because Google Play and the App Store want a public address for the
 * policy.
 * <p>
 * The wording lives in {@code resources/legal/*.html} as body fragments; this class puts the
 * deployment's own details in and wraps them in one page. Who runs the service and how to
 * reach them is configuration ({@code linkup.legal.*}), not text in the repository, so the
 * documents say who is actually responsible. The version printed is
 * {@link LegalDocuments#CURRENT_VERSION}, the same one the sign-up records.
 */
@RestController
public class LegalController {

    private static final Logger log = LoggerFactory.getLogger(LegalController.class);

    private final String terms;
    private final String privacy;

    public LegalController(
            @Value("${linkup.legal.operator-name:}") String operatorName,
            @Value("${linkup.legal.contact-email:}") String contactEmail) {
        if (operatorName.isBlank() || contactEmail.isBlank()) {
            log.warn("linkup.legal.operator-name / linkup.legal.contact-email are not set: the Terms and "
                    + "Privacy Policy pages will say so instead of naming who runs Linkup. Set "
                    + "LINKUP_LEGAL_OPERATOR_NAME and LINKUP_LEGAL_CONTACT_EMAIL before publishing the app.");
        }
        this.terms = render("Terms of Service", "legal/terms.html", operatorName, contactEmail);
        this.privacy = render("Privacy Policy", "legal/privacy.html", operatorName, contactEmail);
    }

    @GetMapping(value = "/legal/terms", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> terms() {
        return page(terms);
    }

    @GetMapping(value = "/legal/privacy", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> privacy() {
        return page(privacy);
    }

    // Cacheable, unlike the share page: the text only changes with a deploy.
    private static ResponseEntity<String> page(String html) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(html);
    }

    private static String render(String title, String resource, String operatorName, String contactEmail) {
        String body;
        try (var in = new ClassPathResource(resource).getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing legal document " + resource, e);
        }
        String operator = operatorName.isBlank()
                ? "the operator of Linkup (not yet named in this deployment's configuration)"
                : "<strong>" + HtmlUtils.htmlEscape(operatorName) + "</strong>";
        String contact = contactEmail.isBlank()
                ? "the contact address (not yet set in this deployment's configuration)"
                : "<a href=\"mailto:" + HtmlUtils.htmlEscape(contactEmail) + "\">"
                        + HtmlUtils.htmlEscape(contactEmail) + "</a>";
        String filled = body
                .replace("{{OPERATOR}}", operator)
                .replace("{{CONTACT}}", contact)
                .replace("{{VERSION}}", LegalDocuments.CURRENT_VERSION)
                .replace("{{MIN_AGE}}", Integer.toString(LegalDocuments.MINIMUM_AGE));
        return SHELL.replace("{{TITLE}}", title).replace("{{BODY}}", filled);
    }

    private static final String SHELL = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>{{TITLE}} - Linkup</title>
              <style>
                body { font: 16px/1.6 system-ui, sans-serif; margin: 0; background: #f6f7f9; color: #16191d; }
                main { max-width: 42rem; margin: 0 auto; padding: 2rem 1.25rem 4rem; }
                h1 { font-size: 1.7rem; margin: 0 0 .25rem; }
                h2 { font-size: 1.15rem; margin: 2rem 0 .5rem; }
                p, li { color: #2b3138; }
                .meta { color: #5b6470; margin: 0 0 1.5rem; }
                a { color: #0e7bb8; }
                table { border-collapse: collapse; width: 100%; font-size: .95rem; }
                th, td { border: 1px solid #d6dae0; padding: .4rem .6rem; text-align: left; vertical-align: top; }
                nav { margin-top: 2.5rem; font-size: .95rem; }
              </style>
            </head>
            <body>
              <main>
                {{BODY}}
                <nav><a href="/legal/terms">Terms of Service</a> &middot; <a href="/legal/privacy">Privacy Policy</a></nav>
              </main>
            </body>
            </html>
            """;
}
