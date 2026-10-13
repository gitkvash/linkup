-- "Report" on a person or a plan: the way a user tells whoever runs Linkup that something is
-- abusive, unsafe or unlawful. Google Play and the App Store both require one in an app where
-- people create content for each other, and the Terms promise that content and accounts can be
-- reported.
--
-- The app can only add and read back its own reports. Nothing here is written to be acted on by
-- the app: the operator reads the table as the owner (which RLS does not bind) and decides, and
-- records the outcome in `status` by hand. So the application role gets INSERT and SELECT of its
-- own rows and nothing else - no UPDATE, no DELETE - and a reporter can never see who else
-- reported the same person, or what happened to it.
--
-- Deliberately no foreign keys. A report is evidence, and it has to outlive both people in it:
-- were it to cascade, deleting an account would erase the reports against it, and a bad actor
-- could clear their record by deleting themselves, or a reporter's deletion would withdraw a
-- report they had made in good faith. For the same reason app_delete_current_account() (V33)
-- does not touch this table. target_id is a user_id or an activity_id, as target_type says; it
-- may point at something deleted since, which is a normal state for a report.
--
-- One report per reporter per target: reporting the same person or plan again changes nothing
-- (the insert is ON CONFLICT DO NOTHING), so a second tap, or a retry after a lost response, is
-- not a second report.
CREATE TABLE reports (
    report_id   UUID PRIMARY KEY,
    reporter_id UUID         NOT NULL,
    target_type VARCHAR(20)  NOT NULL,
    target_id   UUID         NOT NULL,
    reason      VARCHAR(30)  NOT NULL,
    details     VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_reports_target_type CHECK (target_type IN ('USER', 'ACTIVITY')),
    CONSTRAINT chk_reports_reason
        CHECK (reason IN ('HARASSMENT', 'SPAM', 'UNSAFE', 'ILLEGAL', 'INAPPROPRIATE', 'IMPERSONATION', 'OTHER')),
    CONSTRAINT chk_reports_status CHECK (status IN ('OPEN', 'REVIEWED', 'ACTIONED', 'DISMISSED'))
);

CREATE UNIQUE INDEX uq_reports_once ON reports (reporter_id, target_type, target_id);
CREATE INDEX idx_reports_open ON reports (created_at) WHERE status = 'OPEN';

ALTER TABLE reports ENABLE ROW LEVEL SECURITY;

CREATE POLICY reports_select_policy ON reports FOR SELECT USING (reporter_id = app_current_user());
CREATE POLICY reports_insert_policy ON reports FOR INSERT WITH CHECK (reporter_id = app_current_user());

-- V17's default privileges hand the application role all four verbs on a new table; take two back.
REVOKE UPDATE, DELETE ON reports FROM linkup_app;
