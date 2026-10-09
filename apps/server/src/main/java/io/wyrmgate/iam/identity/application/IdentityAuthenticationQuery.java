package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity-owned semantic query used by first-party authentication.
 *
 * <p>The caller receives only an authentication-eligible subject projection; lifecycle and
 * correlation failure reasons remain inside Identity so a public login surface cannot turn them
 * into account-enumeration details.
 */
public interface IdentityAuthenticationQuery {

    Optional<AuthenticationSubject> resolveEligible(
            TenantContext tenant,
            UUID applicationTargetId,
            String nativePrincipalKey);

    Optional<AuthenticationSubject> eligibleSubject(
            TenantContext tenant,
            UUID principalId);

    record AuthenticationSubject(
            UUID principalId,
            UUID identityId,
            long principalRevision,
            long identityRevision) {
        public AuthenticationSubject {
            if (principalId == null) throw new IllegalArgumentException("principalId is required");
            if (identityId == null) throw new IllegalArgumentException("identityId is required");
            if (principalRevision < 1) throw new IllegalArgumentException("principalRevision must be positive");
            if (identityRevision < 1) throw new IllegalArgumentException("identityRevision must be positive");
        }
    }
}
