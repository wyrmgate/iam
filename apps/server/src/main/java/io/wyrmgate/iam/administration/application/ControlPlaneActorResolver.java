package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;

/** Resolves a cryptographically authenticated external subject to one tenant-scoped governed actor. */
public final class ControlPlaneActorResolver {

    private final ControlPlaneActorBindingRepository repository;

    public ControlPlaneActorResolver(ControlPlaneActorBindingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<AuthenticatedAdministrativeActor> resolve(ExternalAuthenticationSubject externalSubject) {
        Objects.requireNonNull(externalSubject, "externalSubject");
        return repository.findActiveByExternalSubject(externalSubject)
                .map(binding -> new AuthenticatedAdministrativeActor(
                        new TenantContext(binding.tenantId()), binding.actorIdentityId()));
    }
}
