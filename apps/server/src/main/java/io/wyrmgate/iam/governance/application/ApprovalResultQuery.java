package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalResultQuery {
    Optional<ApprovalCase> findBySubject(
            TenantContext tenant, SubjectKind subjectKind, UUID subjectId);
}
