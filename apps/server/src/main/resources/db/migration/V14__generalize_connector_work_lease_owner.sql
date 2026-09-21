ALTER TABLE platform.connector_work_lease
    RENAME COLUMN session_id TO execution_owner_id;

COMMENT ON COLUMN platform.connector_work_lease.execution_owner_id IS
    'Opaque technical execution owner. Remote connector workers use negotiated session IDs; in-process executors use stable runtime owner IDs.';
