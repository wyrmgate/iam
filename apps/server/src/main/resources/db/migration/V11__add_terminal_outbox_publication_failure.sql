ALTER TABLE platform.outbox_event
    DROP CONSTRAINT outbox_event_publication_state,
    DROP CONSTRAINT outbox_event_published_state_consistent;

ALTER TABLE platform.outbox_event
    ADD CONSTRAINT outbox_event_publication_state
        CHECK (publication_state IN ('PENDING', 'PUBLISHED', 'FAILED')),
    ADD CONSTRAINT outbox_event_published_state_consistent CHECK (
        (publication_state = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (publication_state IN ('PENDING', 'FAILED') AND published_at IS NULL)
    ),
    ADD CONSTRAINT outbox_event_failed_state_consistent CHECK (
        publication_state <> 'FAILED'
        OR (next_attempt_at IS NULL AND last_error_code IS NOT NULL AND btrim(last_error_code) <> '')
    );
