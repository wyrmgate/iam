package io.wyrmgate.iam.governance.domain;

import java.util.Objects;
import java.util.UUID;

public record ApprovalSubject(Kind kind, UUID id) {
    public ApprovalSubject {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public enum Kind {
        ACCESS_REQUEST_ITEM,
        ROLE_VERSION_ACTIVATION,
        GOVERNANCE_EXCEPTION,
        ADMINISTRATIVE_ELEVATION,
        CREDENTIAL_ACTION
    }
}
