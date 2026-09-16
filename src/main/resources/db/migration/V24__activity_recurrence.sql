-- Lets a plan say that it repeats ("football every Tuesday") instead of being a single
-- point in time. The rule is stored on the activity itself and nothing is materialised:
-- one row is still one activity, with one participant list and one start_time, and the
-- client renders "Every 2 weeks until 12 Dec" from these three columns. Generating an
-- occurrence per repetition would need a series id, a scheduler and its own RLS policy,
-- and none of that is needed to answer "does this happen again?".
--
-- All three are nullable together: NULL repeat_freq is the default and means one-off,
-- which is what every plan created before this column existed was. repeat_until NULL
-- alongside a frequency means "no end date".

-- repeat_interval is INTEGER rather than the SMALLINT its 1..52 range would justify:
-- the entity maps it to a Java Integer, and Hibernate's schema validation refuses the
-- int2/INTEGER mismatch outright at startup. A Short on the field would ripple through
-- the DTO, the command and the read model to save two bytes a row.
ALTER TABLE activities
    ADD COLUMN repeat_freq     VARCHAR(10),
    ADD COLUMN repeat_interval INTEGER,
    ADD COLUMN repeat_until    TIMESTAMPTZ;

-- Both directions, same reasoning as chk_activities_group_visibility: an interval or an
-- end date with no frequency is a leftover from a form the user changed their mind on
-- and would render as a repeat rule that never repeats; a frequency with no interval has
-- no answer to "every how many?".
ALTER TABLE activities
    ADD CONSTRAINT chk_activities_repeat CHECK (
        (repeat_freq IS NULL AND repeat_interval IS NULL AND repeat_until IS NULL)
        OR (repeat_freq IN ('DAILY', 'WEEKLY', 'MONTHLY')
            AND repeat_interval BETWEEN 1 AND 52)
    );
