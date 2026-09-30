package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Principal;
import java.util.List;
import java.util.UUID;

/** Bounded Principal query positions/results for public and internal read adapters. */
public final class PrincipalQueryModels {

    private PrincipalQueryModels() {
    }

    public record PrincipalPosition(UUID id) {
        public PrincipalPosition {
            if (id == null) {
                throw new IllegalArgumentException("id must not be null");
            }
        }
    }

    public record PrincipalPage(
            List<Principal> items,
            PrincipalPosition nextPosition) {
        public PrincipalPage {
            items = List.copyOf(items);
        }
    }
}
