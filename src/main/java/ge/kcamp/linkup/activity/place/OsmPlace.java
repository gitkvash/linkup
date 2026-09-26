package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.activity.enums.PlaceKind;

/**
 * A place the sync would write: one OSM feature that passed {@link OsmPlaceClassifier}.
 *
 * @param osmRef  {@code way/123}, the form V31 already stores, and the key the upsert
 *                matches on
 * @param nameKa  null when OSM has no Georgian name
 * @param radiusM how far from ({@code lat}, {@code lng}) a plan still counts as being here
 */
record OsmPlace(String osmRef, String name, String nameKa, PlaceKind kind,
                double lat, double lng, int radiusM) {
}
