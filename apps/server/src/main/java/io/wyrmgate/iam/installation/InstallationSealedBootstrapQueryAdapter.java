package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.administration.application.SealedInstallationBootstrapQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;

/** Installation-owned semantic adapter used by Administration's explicit legacy adoption command. */
public final class InstallationSealedBootstrapQueryAdapter implements SealedInstallationBootstrapQuery {

    private final InstallationBootstrapRepository repository;

    public InstallationSealedBootstrapQueryAdapter(InstallationBootstrapRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<Candidate> findSealedInstallation() {
        return repository.find()
                .filter(plan -> plan.state() == InstallationBootstrapState.SEALED)
                .filter(plan -> plan.sealedAt() != null)
                .map(plan -> new Candidate(
                        new TenantContext(plan.tenantId()),
                        plan.identityId(),
                        plan.principalId(),
                        plan.correlationId()));
    }
}
