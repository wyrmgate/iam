package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import java.time.Instant;
import java.util.Objects;

/** Default-deny Administrative Authorization evaluator for the first direct-grant slice. */
public final class AdministrativeAuthorizationService {

    private final AdministrativeAuthorizationRepository repository;
    private final GovernedActorStatusQuery governedActorStatusQuery;

    public AdministrativeAuthorizationService(
            AdministrativeAuthorizationRepository repository,
            GovernedActorStatusQuery governedActorStatusQuery) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.governedActorStatusQuery = Objects.requireNonNull(
                governedActorStatusQuery, "governedActorStatusQuery");
    }

    public AdministrativeAuthorizationDecision authorize(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(now, "now");

        if (!permission.resourceType().equals(resource.resourceType())) {
            return AdministrativeAuthorizationDecision.deny("resource_type_mismatch");
        }
        if (!governedActorStatusQuery.isAdministrativelyEligible(
                actor.tenant(), actor.identityId())) {
            return AdministrativeAuthorizationDecision.deny("actor_not_eligible");
        }

        for (AdministrativeGrant grant : repository.findCandidateGrants(
                actor.tenant(), actor.identityId(), permission)) {
            if (!grant.isEffectiveAt(now)) {
                continue;
            }
            if (matches(grant, resource)) {
                return AdministrativeAuthorizationDecision.allow();
            }
        }
        return AdministrativeAuthorizationDecision.deny("no_effective_grant");
    }

    private static boolean matches(AdministrativeGrant grant, AdministrativeResource resource) {
        if (grant.scope().type() == AdministrativeScopeType.GLOBAL) {
            return true;
        }
        if (grant.scope().type() == AdministrativeScopeType.SPECIFIC_RESOURCE) {
            return resource.resourceId() != null
                    && grant.scope().resourceType().equals(resource.resourceType())
                    && grant.scope().resourceId().equals(resource.resourceId());
        }
        // Other canonical scope kinds are modeled but remain fail-closed until their
        // owning resource hierarchy/population semantics have concrete evaluators.
        return false;
    }
}
