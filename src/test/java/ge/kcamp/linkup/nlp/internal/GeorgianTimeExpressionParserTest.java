package ge.kcamp.linkup.nlp.internal;

import ge.kcamp.linkup.nlp.ParsedActivityText;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Spring context and no Docker: these run everywhere.
 */
class GeorgianTimeExpressionParserTest {

    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    private final GeorgianTimeExpressionParser parser = new GeorgianTimeExpressionParser();

    @Test
    void detectsGeorgianScript() {
        assertThat(GeorgianTimeExpressionParser.handles("ხვალ 18 საათზე")).isTrue();
        assertThat(GeorgianTimeExpressionParser.handles("Coffee tomorrow at 6")).isFalse();
        assertThat(GeorgianTimeExpressionParser.handles(null)).isFalse();
    }

    @Test
    void readsTomorrowWithAnExplicitHour() {
        ZonedDateTime now = ZonedDateTime.now(TBILISI);

        ParsedActivityText parsed = parser.parse("ყავა ხვალ 18 საათზე", TBILISI);

        assertThat(parsed.timeFound()).isTrue();
        assertThat(parsed.confidence()).isEqualTo(1.0);
        ZonedDateTime start = parsed.startTime().orElseThrow();
        assertThat(start.getZone()).isEqualTo(TBILISI);
        assertThat(start.getHour()).isEqualTo(18);
        assertThat(start.toLocalDate()).isEqualTo(now.toLocalDate().plusDays(1));
    }

    @Test
    void readsAClockTime() {
        ParsedActivityText parsed = parser.parse("შეხვედრა ხვალ 19:30", TBILISI);

        ZonedDateTime start = parsed.startTime().orElseThrow();
        assertThat(start.getHour()).isEqualTo(19);
        assertThat(start.getMinute()).isEqualTo(30);
    }

    @Test
    void treatsEveningAsPm() {
        ParsedActivityText parsed = parser.parse("ხვალ საღამოს 7-ზე", TBILISI);

        assertThat(parsed.startTime().orElseThrow().getHour()).isEqualTo(19);
    }

    @Test
    void extractsALocativePlaceAndKeepsItOutOfTheTitle() {
        ParsedActivityText parsed = parser.parse("ყავა ვაკის პარკში ხვალ 17 საათზე", TBILISI);

        assertThat(parsed.locationText()).contains("ვაკის პარკში");
        assertThat(parsed.title()).doesNotContain("პარკში");
        assertThat(parsed.title()).contains("ყავა");
    }

    @Test
    void aBareHourAlreadyPastTodayRollsToTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(TBILISI);

        // 00:30 has passed for all but the first half hour of any day.
        ParsedActivityText parsed = parser.parse("ვარჯიში 0:30", TBILISI);
        ZonedDateTime start = parsed.startTime().orElseThrow();

        assertThat(start).isAfter(now);
    }

    @Test
    void reportsNoTimeRatherThanGuessing() {
        ParsedActivityText parsed = parser.parse("ფეხბურთი მეგობრებთან", TBILISI);

        assertThat(parsed.timeFound()).isFalse();
        assertThat(parsed.startTime()).isEmpty();
        assertThat(parsed.confidence()).isZero();
        // The title must survive intact so the activity isn't left unnamed.
        assertThat(parsed.title()).isEqualTo("ფეხბურთი მეგობრებთან");
    }

    @Test
    void weekdayNameResolvesToTheNextSuchDay() {
        ZonedDateTime now = ZonedDateTime.now(TBILISI);

        ParsedActivityText parsed = parser.parse("კინო შაბათს 20 საათზე", TBILISI);
        ZonedDateTime start = parsed.startTime().orElseThrow();

        assertThat(start.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
        assertThat(start).isAfter(now);
        assertThat(start.getHour()).isEqualTo(20);
    }
}
