package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one explicit Identity split. */
public record IdentitySplitOperation(
        UUID id,
        UUID sourceIdentityId,
        UUID newIdentityId,
        long sourceRevisionBefore,
        List<UUID> movedSourceRecordIds,
        List<UUID> movedPrincipalIds,
        String reason,
        UUID correlationId,
        UUID causationId,
        Instant completedAt) {

    public IdentitySplitOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceIdentityId, "sourceIdentityId");
        Objects.requireNonNull(newIdentityId, "newIdentityId");
        if (sourceIdentityId.equals(newIdentityId)) {
            throw new IllegalArgumentException("split requires a new Identity");
        }
        if (sourceRevisionBefore < 1) {
            throw new IllegalArgumentException("sourceRevisionBefore must be positive");
        }
        movedSourceRecordIds = List.copyOf(Objects.requireNonNull(movedSourceRecordIds, "movedSourceRecordIds"));
        movedPrincipalIds = List.copyOf(Objects.requireNonNull(movedPrincipalIds, "movedPrincipalIds"));
        if (movedSourceRecordIds.isEmpty() && movedPrincipalIds.isEmpty()) {
            throw new IllegalArgumentException("split must move at least one Identity-owned relationship");
        }
        if (movedSourceRecordIds.stream().distinct().count() != movedSourceRecordIds.size()
                || movedPrincipalIds.stream().distinct().count() != movedPrincipalIds.size()) {
            throw new IllegalArgumentException("split relationship selections must be unique");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("split reason must not be blank");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
