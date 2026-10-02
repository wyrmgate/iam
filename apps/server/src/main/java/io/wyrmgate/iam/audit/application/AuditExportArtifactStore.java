package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Typed external Audit artifact-store boundary.
 *
 * Export and archive artifacts share the same deployment storage edge while retaining separate
 * deterministic object namespaces and Audit-owned metadata.
 */
public interface AuditExportArtifactStore {

    String write(
            TenantContext tenant,
            UUID exportId,
            ArtifactWriter writer);

    String writeArchive(
            TenantContext tenant,
            UUID archiveSegmentId,
            ArtifactWriter writer);

    InputStream open(
            TenantContext tenant,
            String artifactReference);

    @FunctionalInterface
    interface ArtifactWriter {
        void writeTo(OutputStream output) throws Exception;
    }
}
