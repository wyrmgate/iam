package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;

public interface IdpAuthorizationCodeRepository {

    void insert(TenantContext tenant, IdpAuthorizationCode code);

    Optional<IdpAuthorizationCode> consume(
            TenantContext tenant,
            String codeHash,
            Instant consumedAt);
}
