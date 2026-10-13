package ge.kcamp.linkup;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The page behind a shared plan's link, {@code https://<host>/p/<id>}.
 * <p>
 * A message app turns an {@code https} link into something tappable and a custom-scheme one
 * ({@code linkup://}) into plain text, so the shared link is the former. Opened on a phone
 * with Linkup, Android hands it straight to the app; this page is for the other cases - a
 * browser, a desktop, a phone without the app - and hands the tap on to the app by its own
 * scheme.
 * <p>
 * It is open to everyone and says nothing about the plan, not even whether it exists: the
 * link is only an address, and whether the person holding it may see the plan is decided,
 * as ever, by {@code GET /api/v1/activities/{id}} once the app is open and signed in.
 * Putting a title here would turn every shared link into a way to read a private plan.
 */
@RestController
public class ShareLandingController {

    @GetMapping(value = "/p/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> plan(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .body(page(id));
    }

    // A UUID has no characters that need escaping in HTML or in the script string.
    private static String page(UUID id) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="robots" content="noindex">
                  <title>Open in Linkup</title>
                  <style>
                    body { font: 16px/1.5 system-ui, sans-serif; margin: 0; min-height: 100vh;
                           display: grid; place-items: center; background: #f6f7f9; color: #16191d; }
                    main { max-width: 22rem; padding: 2rem; text-align: center; }
                    h1 { font-size: 1.4rem; margin: 0 0 .5rem; }
                    p { margin: 0 0 1.5rem; color: #5b6470; }
                    a.open { display: inline-block; padding: .8rem 1.6rem; border-radius: 999px;
                             background: #16191d; color: #fff; text-decoration: none; font-weight: 600; }
                    small { display: block; margin-top: 1.5rem; color: #8a929d; }
                  </style>
                </head>
                <body>
                  <main>
                    <h1>Someone shared a plan with you</h1>
                    <p>Open it in Linkup to see the details and say if you're coming.</p>
                    <a class="open" id="open" href="linkup://app/p/%1$s">Open in Linkup</a>
                    <small>Don't have Linkup yet? Ask whoever sent you this link.</small>
                  </main>
                  <script>
                    // Try the app straight away; the button is there for when the browser blocks it.
                    setTimeout(function () { window.location.href = "linkup://app/p/%1$s"; }, 150);
                  </script>
                </body>
                </html>
                """.formatted(id);
    }
}
