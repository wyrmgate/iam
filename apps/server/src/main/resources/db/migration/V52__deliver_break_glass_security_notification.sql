ALTER TABLE platform.scheduled_work
    ADD COLUMN last_error_code varchar(128) NULL;

ALTER TABLE platform.scheduled_work
    ADD CONSTRAINT scheduled_work_last_error_code_ck CHECK (
        last_error_code IS NULL OR btrim(last_error_code) <> ''
    );

COMMENT ON COLUMN platform.scheduled_work.last_error_code IS
    'Normalized technical delivery/retry code only. Domain capabilities retain business/process outcome ownership.';
