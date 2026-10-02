package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public interface AuditExportArtifactStore {

    String write(
            TenantContext tenant,
            UUID exportId,
            ArtifactWriter writer);

    InputStream open(
            TenantContext tenant,
            String artifactReference);

    @FunctionalInterface
    interface ArtifactWriter {
        void writeTo(OutputStream output) throws Exception;
    }
}
