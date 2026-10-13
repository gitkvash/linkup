/**
 * Reports: a user tells the operator that a person or a plan is abusive, unsafe or unlawful.
 * <p>
 * A module of its own because a report is about either a user or an activity and must not make
 * {@code identity} or {@code activity} depend on the other. It depends on neither, and nothing
 * depends on it: the operator reads the {@code reports} table directly (V44).
 */
package ge.kcamp.linkup.moderation;
