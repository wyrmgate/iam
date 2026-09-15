package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.ControlPlaneActorBinding;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Optional;

/** Administration-owned persistence port for trusted external-subject actor bindings. */
public interface ControlPlaneActorBindingRepository {

    Optional<ControlPlaneActorBinding> findActiveByExternalSubject(ExternalAuthenticationSubject externalSubject);

    boolean insertIfExternalSubjectUnbound(TenantContext tenant, ControlPlaneActorBinding binding);
}
