package io.wyrmgate.iam.credential.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CredentialQueryModels {
    private CredentialQueryModels() {}

    public record CredentialPosition(
            Instant createdAt,
            UUID id) {}

    public record RotationPosition(
            Instant createdAt,
            UUID id) {}

    public record Page<T,P>(
            List<T> items,
            P nextPosition) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
