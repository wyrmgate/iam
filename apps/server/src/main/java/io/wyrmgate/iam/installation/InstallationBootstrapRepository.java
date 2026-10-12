package io.wyrmgate.iam.installation;

import java.time.Instant;
import java.util.Optional;

/** Deployment-local persistence for the single fresh-install bootstrap process. */
public interface InstallationBootstrapRepository {

    Optional<InstallationBootstrapPlan> find();

    boolean createPlan(InstallationBootstrapPlan plan);

    boolean advance(
            InstallationBootstrapState expected,
            InstallationBootstrapState next,
            Instant now);
}
