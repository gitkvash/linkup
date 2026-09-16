package ge.kcamp.linkup.activity.web;

import org.springframework.stereotype.Component;

/**
 * Maps a client-supplied map zoom level to PostGIS ST_ClusterDBSCAN tuning parameters,
 * so the client only ever sends "zoom" and the server owns the clustering behavior.
 */
@Component
public class ZoomClusterResolver {

    public record ClusterParams(double epsMeters, int minPoints) {
    }

    public ClusterParams resolve(int zoom) {
        // Coarser zoom (zoomed out) -> larger radius, more aggressive clustering.
        double epsMeters = switch (Math.max(0, Math.min(zoom, 20))) {
            case 0, 1, 2, 3, 4, 5 -> 50_000;
            case 6, 7, 8 -> 20_000;
            case 9, 10, 11 -> 5_000;
            case 12, 13, 14 -> 1_000;
            case 15, 16 -> 200;
            default -> 20;
        };
        return new ClusterParams(epsMeters, 2);
    }
}
