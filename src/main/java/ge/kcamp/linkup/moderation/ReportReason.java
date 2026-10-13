package ge.kcamp.linkup.moderation;

/** The choices the report sheet offers. The client sends the name; V44's check constraint repeats the list. */
public enum ReportReason {
    HARASSMENT,
    SPAM,
    UNSAFE,
    ILLEGAL,
    INAPPROPRIATE,
    IMPERSONATION,
    OTHER
}
