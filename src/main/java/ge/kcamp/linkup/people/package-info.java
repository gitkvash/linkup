/**
 * How the caller sees people: someone else's profile, and their own year in stats.
 * <p>
 * A module of its own because both views are made of every other module's data - who a
 * person is ({@code identity}), how the two are connected ({@code social}) and what they
 * did ({@code activity}) - and none of those may depend on the others in the direction
 * this would need. Nothing depends on this module.
 */
package ge.kcamp.linkup.people;
