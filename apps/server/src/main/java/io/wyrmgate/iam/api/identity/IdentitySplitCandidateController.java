package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitRepository;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded Identity-owned read used only to select explicit relationships for a split correction.
 *
 * <p>The command still revalidates relationship ownership and revision inside the authoritative
 * transaction. This read intentionally exposes no provider-native payload and does not widen
 * ordinary Principal collection visibility.</p>
 */
@RestController
@RequestMapping("/api/v1/identities")
final class IdentitySplitCandidateController {

    private static final int MAX_CANDIDATES_PER_KIND = 200;

    private final IdentityQueryService identities;
    private final IdentityMergeSplitRepository relationships;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    IdentitySplitCandidateController(
            IdentityQueryService identities,
            IdentityMergeSplitRepository relationships,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.identities = identities;
        this.relationships = relationships;
        this.authorization = authorization;
        this.ids = ids;
    }

    @GetMapping("/{identityId}/split-candidates")
    ResponseEntity<SplitCandidatesResource> splitCandidates(
            @PathVariable UUID identityId,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        AdministrativeResource resource = new AdministrativeResource("identity", identityId);
        requireAllowed(actor, AdministrativePermissions.IDENTITY_READ, resource, now, correlationId);
        requireAllowed(actor, AdministrativePermissions.IDENTITY_SPLIT, resource, now, correlationId);
        if (identities.findIdentity(actor.tenant(), identityId).isEmpty()) {
            throw IdentityApiException.notFound(correlationId);
        }

        List<IdentityLink> links = relationships.findActiveLinksByIdentity(actor.tenant(), identityId);
        List<Principal> principals = relationships.findPrincipalsByIdentity(actor.tenant(), identityId);

        SplitCandidatesResource body = new SplitCandidatesResource(
                links.stream()
                        .limit(MAX_CANDIDATES_PER_KIND)
                        .map(link -> new SourceRecordCandidate(link.sourceRecordId(), link.linkedAt()))
                        .toList(),
                principals.stream()
                        .limit(MAX_CANDIDATES_PER_KIND)
                        .map(principal -> new PrincipalCandidate(
                                principal.id(),
                                principal.applicationTargetId(),
                                principal.lifecycleState().name(),
                                principal.revision()))
                        .toList(),
                links.size() > MAX_CANDIDATES_PER_KIND,
                principals.size() > MAX_CANDIDATES_PER_KIND);

        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private void requireAllowed(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    record SplitCandidatesResource(
            List<SourceRecordCandidate> sourceRecords,
            List<PrincipalCandidate> principals,
            boolean sourceRecordsTruncated,
            boolean principalsTruncated) {
    }

    record SourceRecordCandidate(UUID sourceRecordId, Instant linkedAt) {
    }

    record PrincipalCandidate(
            UUID principalId,
            UUID applicationTargetId,
            String lifecycleState,
            long revision) {
    }
}
