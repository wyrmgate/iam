ALTER TABLE administration.administrative_break_glass_obligation
    ADD COLUMN notification_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN notification_next_attempt_at timestamptz NULL,
    ADD COLUMN notification_lease_until timestamptz NULL,
    ADD COLUMN notification_last_attempt_at timestamptz NULL,
    ADD COLUMN notification_last_error_code varchar(128) NULL;

UPDATE administration.administrative_break_glass_obligation
SET notification_next_attempt_at = created_at
WHERE obligation_type = 'SECURITY_NOTIFICATION'
  AND state = 'PENDING'
  AND notification_next_attempt_at IS NULL;

ALTER TABLE administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_notification_attempt_count_ck CHECK (
        notification_attempt_count >= 0
    );

ALTER TABLE administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_notification_error_code_ck CHECK (
        notification_last_error_code IS NULL
        OR btrim(notification_last_error_code) <> ''
    );

ALTER TABLE administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_notification_shape_ck CHECK (
        (
            obligation_type = 'POST_USE_REVIEW'
            AND notification_attempt_count = 0
            AND notification_next_attempt_at IS NULL
            AND notification_lease_until IS NULL
            AND notification_last_attempt_at IS NULL
            AND notification_last_error_code IS NULL
        )
        OR
        (
            obligation_type = 'SECURITY_NOTIFICATION'
            AND (
                (state = 'PENDING' AND notification_next_attempt_at IS NOT NULL)
                OR
                (state = 'COMPLETED'
                    AND notification_next_attempt_at IS NULL
                    AND notification_lease_until IS NULL)
                OR
                (state = 'MANUAL_REQUIRED'
                    AND notification_next_attempt_at IS NULL
                    AND notification_lease_until IS NULL
                    AND notification_last_error_code IS NOT NULL)
            )
        )
    );

CREATE INDEX administrative_break_glass_notification_due_idx
    ON administration.administrative_break_glass_obligation
       (notification_next_attempt_at, id)
    WHERE obligation_type = 'SECURITY_NOTIFICATION'
      AND state = 'PENDING';

COMMENT ON COLUMN administration.administrative_break_glass_obligation.notification_attempt_count IS
    'Technical at-least-once delivery attempt count for SECURITY_NOTIFICATION only.';
COMMENT ON COLUMN administration.administrative_break_glass_obligation.notification_lease_until IS
    'Technical delivery lease only; never affects break-glass authority validity.';
COMMENT ON COLUMN administration.administrative_break_glass_obligation.notification_last_error_code IS
    'Normalized technical delivery code only; remote bodies and secret-bearing exception detail are not stored.';
