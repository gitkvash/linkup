package ge.kcamp.linkup.activity;

/**
 * Intermediate representation the {@link ActivityFactory} turns into a persisted
 * {@link ge.kcamp.linkup.activity.entity.Activity}. Kept as a sealed spec (rather than
 * polymorphic JPA entities) because the DB schema is a single {@code activities} table
 * with a flat {@code activity_type} discriminator column, and the RLS policies are
 * written directly against that table by name.
 */
public sealed interface ActivitySpec permits CasualPlanSpec, StructuredEventSpec {

    String title();
}
