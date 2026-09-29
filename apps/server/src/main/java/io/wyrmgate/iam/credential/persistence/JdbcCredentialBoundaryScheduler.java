package io.wyrmgate.iam.credential.persistence;

import io.wyrmgate.iam.credential.application.CredentialBoundaryScheduler;
import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;

public final class JdbcCredentialBoundaryScheduler
        implements CredentialBoundaryScheduler {

    public static final String HANDLER_TYPE =
            "credential-validity-boundary";

    private final JdbcScheduledWorkRepository scheduledWork;

    public JdbcCredentialBoundaryScheduler(
            JdbcScheduledWorkRepository scheduledWork) {
        this.scheduledWork = Objects.requireNonNull(
                scheduledWork, "scheduledWork");
    }

    @Override
    public void scheduleBoundaries(
            TenantContext tenant,
            Credential credential,
            Instant now) {
        SubjectReference subject =
                new SubjectReference(
                        "credential",
                        credential.id(),
                        credential.revision());

        if (credential.validFrom() != null
                && credential.validFrom().isAfter(now)) {
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    credential.id() + ":valid-from",
                    subject,
                    credential.validFrom(),
                    now);
        }
        if (credential.validUntil() != null
                && credential.validUntil().isAfter(now)) {
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    credential.id() + ":valid-until",
                    subject,
                    credential.validUntil(),
                    now);
        }
    }
}
