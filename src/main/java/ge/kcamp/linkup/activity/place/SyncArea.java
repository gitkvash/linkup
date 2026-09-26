package ge.kcamp.linkup.activity.place;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One rectangle the sync asks Overpass about, in Overpass's own order: south, west,
 * north, east.
 */
record SyncArea(double south, double west, double north, double east) {

    SyncArea {
        if (!(south < north) || !(west < east)
                || south < -90 || north > 90 || west < -180 || east > 180) {
            throw new IllegalArgumentException(
                    "Invalid sync area " + south + "," + west + "," + north + "," + east
                            + ": expected south,west,north,east with south < north and west < east");
        }
    }

    /**
     * Parses {@code linkup.places.sync.areas}: rectangles separated by {@code ;}, each
     * {@code south,west,north,east}. Semicolons because each rectangle already uses the
     * commas. Blank entries are skipped, so a trailing {@code ;} is harmless.
     */
    static List<SyncArea> parseAll(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        return Arrays.stream(spec.split(";"))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .map(SyncArea::parse)
                .toList();
    }

    private static SyncArea parse(String entry) {
        String[] parts = entry.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "Invalid sync area '" + entry + "': expected south,west,north,east");
        }
        try {
            return new SyncArea(
                    Double.parseDouble(parts[0].strip()),
                    Double.parseDouble(parts[1].strip()),
                    Double.parseDouble(parts[2].strip()),
                    Double.parseDouble(parts[3].strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid sync area '" + entry + "': " + e.getMessage(), e);
        }
    }

    /** Locale-proof: a German JVM would otherwise write 41,6. */
    String overpassBbox() {
        return String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", south, west, north, east);
    }
}
