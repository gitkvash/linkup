package ge.kcamp.linkup.activity.enums;

/**
 * Where a "suggest another time" request stands (V39). {@code PENDING} is the only open
 * state; the rest record how it ended: answered by the host ({@code ACCEPTED},
 * {@code DECLINED}), taken back by the proposer ({@code WITHDRAWN}, or by proposing
 * again), or overtaken because another suggestion moved the plan first ({@code SUPERSEDED}).
 */
public enum TimeProposalStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    WITHDRAWN,
    SUPERSEDED
}
