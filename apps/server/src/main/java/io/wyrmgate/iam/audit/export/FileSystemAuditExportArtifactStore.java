package io.wyrmgate.iam.audit.export;

import io.wyrmgate.iam.audit.application.AuditExportArtifactStore;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Deployment adapter for ADR-0034 artifacts.
 *
 * The configured root must itself provide the durability/shared-storage properties required by the
 * deployment. Local ephemeral filesystem storage is not a production HA/DR guarantee.
 */
public final class FileSystemAuditExportArtifactStore implements AuditExportArtifactStore {

    private final Path root;

    public FileSystemAuditExportArtifactStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public String write(
            TenantContext tenant,
            UUID exportId,
            ArtifactWriter writer) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(writer, "writer");

        String reference = tenant.tenantId() + "/" + exportId + ".ndjson";
        Path target = resolve(reference);
        Path directory = target.getParent();
        Path temporary = directory.resolve(exportId + ".tmp");
        try {
            Files.createDirectories(directory);
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writer.writeTo(output);
                output.flush();
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return reference;
        } catch (Exception failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original export failure; stale temp cleanup is operational work.
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof IOException io) throw new UncheckedIOException(io);
            throw new IllegalStateException("audit export artifact write failed", failure);
        }
    }

    @Override
    public InputStream open(
            TenantContext tenant,
            String artifactReference) {
        Objects.requireNonNull(tenant, "tenant");
        if (artifactReference == null || artifactReference.isBlank()) {
            throw new IllegalArgumentException("artifactReference must not be blank");
        }
        String tenantPrefix = tenant.tenantId() + "/";
        if (!artifactReference.startsWith(tenantPrefix)) {
            throw new IllegalArgumentException("artifact reference does not belong to tenant");
        }
        try {
            return Files.newInputStream(resolve(artifactReference), StandardOpenOption.READ);
        } catch (IOException error) {
            throw new UncheckedIOException("audit export artifact is unavailable", error);
        }
    }

    private Path resolve(String reference) {
        Path resolved = root.resolve(reference).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("invalid artifact reference");
        }
        return resolved;
    }
}
