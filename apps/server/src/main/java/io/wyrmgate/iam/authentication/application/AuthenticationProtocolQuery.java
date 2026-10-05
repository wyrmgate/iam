package io.wyrmgate.iam.authentication.application;

import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;

/** Authentication-owned global lookup used only to establish tenant context at the SSO protocol edge. */
public interface AuthenticationProtocolQuery {

    Optional<ResolvedClient> findActiveClientByProtocolClientId(String protocolClientId);

    Optional<ResolvedSession> findActiveSessionBySecretDigest(String sessionSecretDigest);

    record ResolvedClient(TenantContext tenant, AuthenticationClient client) {
        public ResolvedClient {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(client, "client");
        }
    }

    record ResolvedSession(TenantContext tenant, AuthenticationSession session) {
        public ResolvedSession {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(session, "session");
        }
    }
}