/**
 * The activity module's vocabulary: the enums its public read models are written in.
 * <p>
 * Declared a named interface so a consumer module may depend on it. Everything under a
 * module's root package is internal to Modulith by default, which is right for
 * {@code activity.internal} and wrong for these: {@code ActivityFeedItem} - the module's
 * public read model - has six of them in its own signature, so a consumer cannot read the
 * projection it is handed without naming them. The boundary held until now only because
 * {@code feed} happened to call none of the accessors that return one.
 * <p>
 * What is exposed is the vocabulary, not the machinery. Nothing that resolves, persists or
 * decides anything belongs in this package.
 */
@org.springframework.modulith.NamedInterface("enums")
package ge.kcamp.linkup.activity.enums;
