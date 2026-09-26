package ge.kcamp.linkup.activity.place;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Asks the Overpass API (a read-only query service over OpenStreetMap) for the candidate
 * places in the sync's areas. One POST per sync covers every area.
 * <p>
 * The public instance at overpass-api.de is free, needs no key, and asks only for fair
 * use: well under 10,000 queries a day, and a User-Agent that says who is asking. One
 * query a week is nowhere near that.
 * <p>
 * It is often busy, though. On the afternoon this was written it answered 504 ("the
 * server is probably too busy") to two of four identical queries. So
 * {@code linkup.places.sync.overpass-url} is a comma-separated list, tried in order until
 * one gives a complete answer. The default adds a second public instance run on the same
 * software. A self-hosted instance can go first.
 */
@Component
class OverpassClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);

    /**
     * What the query tells Overpass it may spend. Overpass admits a query only while it has
     * that much capacity free, so asking for more than needed makes a busy server say no.
     * Greater Tbilisi, Batumi and Kutaisi together take about 12 seconds.
     */
    private static final int QUERY_TIMEOUT_SECONDS = 60;

    /**
     * Every named feature of a kind {@link OsmPlaceClassifier#kindOf} knows. Filtering by
     * size and notability happens in Java: {@code out bb} reports the bounding box, but
     * Overpass QL has no way to filter on it.
     */
    private static final List<String> SELECTORS = List.of(
            "[\"natural\"=\"water\"][\"name\"]",
            "[\"leisure\"~\"^(park|garden|stadium|sports_centre|sports_hall|ice_rink|water_park)$\"][\"name\"]",
            "[\"shop\"=\"mall\"][\"name\"]",
            "[\"tourism\"~\"^(attraction|theme_park|zoo|viewpoint)$\"][\"name\"]",
            "[\"place\"=\"square\"][\"name\"]",
            "[\"historic\"~\"^(castle|fort|monastery)$\"][\"name\"]");

    static final String DEFAULT_URLS =
            "https://overpass-api.de/api/interpreter,https://overpass.private.coffee/api/interpreter";

    private final List<String> urls;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    OverpassClient(
            @Value("${linkup.places.sync.overpass-url:" + DEFAULT_URLS + "}") String urls,
            JsonMapper jsonMapper) {
        this.urls = Arrays.stream(urls.split(","))
                .map(String::strip)
                .filter(url -> !url.isEmpty())
                .toList();
        if (this.urls.isEmpty()) {
            throw new IllegalArgumentException("linkup.places.sync.overpass-url names no URL");
        }

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(QUERY_TIMEOUT_SECONDS + 30));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "Linkup/1.0 (place sync; weekly)")
                .build();
        this.jsonMapper = jsonMapper;
    }

    /**
     * The first complete answer from {@link #urls}, in order. Throws if none gives one,
     * with the last instance's failure as the cause and the earlier ones suppressed.
     */
    List<OverpassResponse.Element> fetch(List<SyncArea> areas) {
        String query = query(areas);
        RuntimeException failure = null;
        for (String url : urls) {
            try {
                return fetchFrom(url, query);
            } catch (RuntimeException e) {
                log.info("Overpass at {} failed: {}", url, summary(e));
                if (failure != null) {
                    e.addSuppressed(failure);
                }
                failure = e;
            }
        }
        throw new IllegalStateException("No Overpass instance answered (" + urls.size() + " tried)", failure);
    }

    /**
     * Throws on anything short of a complete answer: an HTTP error, a body that isn't
     * JSON, or a 200 whose remark says the query failed part-way (see
     * {@link OverpassResponse}).
     */
    private List<OverpassResponse.Element> fetchFrom(String url, String query) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query);

        String body = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Overpass returned an empty body");
        }

        OverpassResponse response = jsonMapper.readValue(body, OverpassResponse.class);
        if (response.remark() != null && response.remark().toLowerCase(Locale.ROOT).contains("error")) {
            throw new IllegalStateException("Overpass answered only in part: " + response.remark());
        }
        if (response.elements() == null) {
            throw new IllegalStateException("Overpass answer has no elements");
        }
        return response.elements();
    }

    /** A busy Overpass answers with a whole HTML page, which doesn't belong in a log line. */
    private static String summary(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return e.toString();
    }

    /**
     * {@code nwr} takes nodes, ways and relations alike, since a lake may be any of the
     * three. {@code out bb tags} returns each one's bounding box and tags, without the
     * geometry: a fraction of the size, and all the classifier needs. Not
     * {@code out center bb}: {@code center} and {@code bb} are alternatives, and asking for
     * both returns only the box. The box is enough anyway, since its middle is exactly
     * what {@code center} reports.
     */
    static String query(List<SyncArea> areas) {
        StringBuilder query = new StringBuilder()
                .append("[out:json][timeout:").append(QUERY_TIMEOUT_SECONDS).append("];\n(\n");
        for (SyncArea area : areas) {
            for (String selector : SELECTORS) {
                query.append("  nwr").append(selector).append('(').append(area.overpassBbox()).append(");\n");
            }
        }
        return query.append(");\nout bb tags;\n").toString();
    }
}
