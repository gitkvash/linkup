package ge.kcamp.linkup.activity.dto;

public record BoundingBox(double minLat, double minLng, double maxLat, double maxLng) {

    private static final double MAX_SPAN_DEGREES = 5.0;

    /**
     * Throws {@link IllegalArgumentException}, which the global exception handler turns
     * into a 400 with this message. It used to escape unhandled and surface as a 500.
     */
    public void validate() {
        if (!inRange(minLat, -90, 90) || !inRange(maxLat, -90, 90)) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90.");
        }
        if (!inRange(minLng, -180, 180) || !inRange(maxLng, -180, 180)) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180.");
        }
        if (minLat > maxLat || minLng > maxLng) {
            throw new IllegalArgumentException("Invalid bounding box: min must be <= max.");
        }
        if ((maxLat - minLat) > MAX_SPAN_DEGREES || (maxLng - minLng) > MAX_SPAN_DEGREES) {
            throw new IllegalArgumentException(
                    "Bounding box span too large (max " + MAX_SPAN_DEGREES + " degrees). Zoom in and try again.");
        }
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }
}
