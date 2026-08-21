package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

public interface SourceCorrelationFactSink {

    void sourceSystemCreated(TenantContext tenant, SourceSystem sourceSystem, UUID correlationId, UUID causationId);

    void sourceRecordObserved(TenantContext tenant, SourceRecord sourceRecord, UUID correlationId, UUID causationId);

    void importCompleted(TenantContext tenant, SourceImportRun run, UUID correlationId, UUID causationId);

    void identityLinkAccepted(TenantContext tenant, IdentityLink link);
}
