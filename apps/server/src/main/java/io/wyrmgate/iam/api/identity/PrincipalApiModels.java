package io.wyrmgate.iam.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class PrincipalApiModels {

    private PrincipalApiModels() {
    }

    record PrincipalResource(
            UUID id,
            UUID identityId,
            UUID applicationTargetId,
            String kind,
            String nativePrincipalKey,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }

    record PrincipalPage(
            List<PrincipalResource> items,
            String nextCursor) {
    }
}
