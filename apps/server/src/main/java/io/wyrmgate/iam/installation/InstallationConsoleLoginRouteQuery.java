package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.idp.session.ConsoleLoginRouteQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;

/** Resolves non-secret Console login routing only from an irreversibly sealed installation plan. */
public final class InstallationConsoleLoginRouteQuery implements ConsoleLoginRouteQuery {

    private final InstallationBootstrapRepository installation;
    private final SsoClientProtocolQuery protocolClients;

    public InstallationConsoleLoginRouteQuery(
            InstallationBootstrapRepository installation,
            SsoClientProtocolQuery protocolClients) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.protocolClients = Objects.requireNonNull(protocolClients, "protocolClients");
    }

    @Override
    public Optional<Route> resolve() {
        InstallationBootstrapPlan plan = installation.find()
                .filter(candidate -> candidate.state() == InstallationBootstrapState.SEALED)
                .orElse(null);
        if (plan == null) return Optional.empty();

        var resolved = protocolClients.resolveActive(plan.consoleClientId()).orElse(null);
        if (resolved == null) return Optional.empty();
        TenantContext expectedTenant = new TenantContext(plan.tenantId());
        if (!resolved.tenant().equals(expectedTenant)
                || !resolved.registration().id().equals(plan.consoleSsoRegistrationId())
                || !resolved.registration().applicationId().equals(plan.consoleApplicationId())) {
            return Optional.empty();
        }
        return Optional.of(new Route(plan.consoleClientId(), plan.consoleTargetId()));
    }
}
