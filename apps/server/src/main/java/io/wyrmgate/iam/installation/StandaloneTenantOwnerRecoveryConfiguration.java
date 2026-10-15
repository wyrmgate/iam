package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryNotEligibleException;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryResult;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryService;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit, local-only ADR-0047 compatibility command for a sealed standalone installation. */
@Configuration
public class StandaloneTenantOwnerRecoveryConfiguration {

    static final String ACTION = "recover-standalone-tenant-owner";
    static final String RESOURCE = "installation-bootstrap";
    static final String ENABLE_OPTION = "iam.recovery.standalone-tenant-owner.enabled";

    @Bean
    @ConditionalOnProperty(
            prefix = "iam.recovery.standalone-tenant-owner",
            name = "enabled",
            havingValue = "true")
    ApplicationRunner standaloneTenantOwnerRecoveryRunner(
            Environment environment,
            InstallationBootstrapRepository installationRepository,
            StandaloneTenantOwnerRecoveryService recoveryService,
            SecurityAuditPort securityAudit,
            IdGenerator ids) {
        return args -> {
            requireExplicitCommandArgument(args);
            requireNonWeb(environment);
            InstallationBootstrapPlan plan = installationRepository.find()
                    .orElseThrow(() -> new StandaloneTenantOwnerRecoveryNotEligibleException(
                            "installation_bootstrap_plan_missing"));
            TenantContext tenant = new TenantContext(plan.tenantId());
            Instant now = Instant.now();

            if (plan.state() != InstallationBootstrapState.SEALED) {
                appendAudit(securityAudit, ids, tenant, plan, now, AuditOutcome.DENIED);
                throw new StandaloneTenantOwnerRecoveryNotEligibleException("installation_not_sealed");
            }
            if (plan.bootstrapVersion() != InstallationBootstrapService.BOOTSTRAP_VERSION) {
                appendAudit(securityAudit, ids, tenant, plan, now, AuditOutcome.DENIED);
                throw new StandaloneTenantOwnerRecoveryNotEligibleException("bootstrap_version_not_supported");
            }

            try {
                StandaloneTenantOwnerRecoveryResult result = recoveryService.recover(
                        tenant,
                        plan.identityId(),
                        plan.principalId(),
                        plan.correlationId(),
                        now);
                appendAudit(securityAudit, ids, tenant, plan, now, AuditOutcome.SUCCESS);
                System.out.println("Standalone tenant-owner recovery: " + result.status());
            } catch (StandaloneTenantOwnerRecoveryNotEligibleException exception) {
                appendAudit(securityAudit, ids, tenant, plan, now, AuditOutcome.DENIED);
                throw exception;
            } catch (RuntimeException exception) {
                appendAudit(securityAudit, ids, tenant, plan, now, AuditOutcome.FAILURE);
                throw exception;
            }
        };
    }

    private static void requireExplicitCommandArgument(ApplicationArguments args) {
        List<String> values = args.getOptionValues(ENABLE_OPTION);
        if (!args.containsOption(ENABLE_OPTION)
                || values == null
                || values.size() != 1
                || !"true".equalsIgnoreCase(values.get(0))) {
            throw new StandaloneTenantOwnerRecoveryNotEligibleException(
                    "recovery_requires_explicit_command_argument");
        }
    }

    private static void requireNonWeb(Environment environment) {
        String webType = environment.getProperty("spring.main.web-application-type");
        if (webType == null || !"none".equalsIgnoreCase(webType.trim())) {
            throw new StandaloneTenantOwnerRecoveryNotEligibleException(
                    "recovery_requires_spring.main.web-application-type=none");
        }
    }

    private static void appendAudit(
            SecurityAuditPort audit,
            IdGenerator ids,
            TenantContext tenant,
            InstallationBootstrapPlan plan,
            Instant now,
            AuditOutcome outcome) {
        audit.append(
                tenant,
                new AuditRecordDraft(
                        ids.nextId(),
                        now,
                        plan.identityId(),
                        ACTION,
                        RESOURCE,
                        plan.tenantId(),
                        outcome,
                        plan.correlationId(),
                        null));
    }
}
