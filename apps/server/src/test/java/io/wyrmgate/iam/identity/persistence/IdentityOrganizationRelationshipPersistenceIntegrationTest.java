package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityRelationshipCommandService;
import io.wyrmgate.iam.identity.application.OrganizationCommandService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.ManagerRelationship;
import io.wyrmgate.iam.identity.domain.Organization;
import io.wyrmgate.iam.identity.domain.OrganizationMembership;
import io.wyrmgate.iam.identity.domain.OwnershipRelationship;
import io.wyrmgate.iam.identity.domain.RelationshipProvenance;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class IdentityOrganizationRelationshipPersistenceIntegrationTest {

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static IdentityCommandService identityCommands;
    private static OrganizationCommandService organizationCommands;
    private static IdentityRelationshipCommandService relationshipCommands;
    private static JdbcOrganizationRepository organizations;
    private static JdbcIdentityRelationshipRepository relationships;

    @BeforeAll
    static void startPostgresAndMigrateFromEmptyDatabase() {
        SharedPostgresIntegrationDatabase.reset();
        var dataSource = SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        TransactionExecutor transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        JdbcIdentityRepository identityRepository = new JdbcIdentityRepository(jdbc);
        identityCommands = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(new JdbcOutboxRepository(jdbc), ids),
                ids,
                transactions);
        organizations = new JdbcOrganizationRepository(jdbc);
        relationships = new JdbcIdentityRelationshipRepository(jdbc);
        organizationCommands = new OrganizationCommandService(
                organizations, ids, transactions);
        relationshipCommands = new IdentityRelationshipCommandService(
                identityRepository,
                organizations,
                relationships,
                ids,
                transactions);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    tenancy.tenant
                CASCADE
                """);
    }

    @Test
    void persistsTenantLocalAcyclicOrganizationHierarchyWithRevisionFencing() {
        Instant now = Instant.parse("2026-11-10T08:00:00Z");
        TenantContext tenant = tenant("Organization Tenant", now);

        Organization company = organizationCommands.create(
                tenant, "Wyrmgate", "COMPANY", null, now);
        Organization engineering = organizationCommands.create(
                tenant, "Engineering", "DEPARTMENT", company.id(), now.plusSeconds(1));
        Organization platform = organizationCommands.create(
                tenant, "Platform", "TEAM", engineering.id(), now.plusSeconds(2));

        assertThat(organizations.findById(tenant, platform.id()))
                .get()
                .extracting(Organization::parentId)
                .isEqualTo(engineering.id());

        assertThatThrownBy(() -> organizationCommands.changeParent(
                        tenant,
                        company.id(),
                        platform.id(),
                        company.revision(),
                        now.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");

        Organization renamed = organizationCommands.changeMetadata(
                tenant,
                engineering.id(),
                "Engineering & Platform",
                "DEPARTMENT",
                engineering.revision(),
                now.plusSeconds(4));
        assertThat(renamed.revision()).isEqualTo(2);

        assertThatThrownBy(() -> organizationCommands.changeMetadata(
                        tenant,
                        engineering.id(),
                        "Stale",
                        "DEPARTMENT",
                        engineering.revision(),
                        now.plusSeconds(5)))
                .isInstanceOf(StaleWriteException.class);
    }

    @Test
    void persistsTypedTemporalRelationshipsAndEndsThemWithOptimisticRevision() {
        Instant now = Instant.parse("2026-11-10T09:00:00Z");
        TenantContext tenant = tenant("Relationship Tenant", now);
        Identity person = identity(tenant, IdentityType.PERSON, "Person", now);
        Identity manager = identity(tenant, IdentityType.PERSON, "Manager", now.plusMillis(1));
        Identity service = identity(tenant, IdentityType.SERVICE, "Service", now.plusMillis(2));
        Organization organization = organizationCommands.create(
                tenant, "Operations", "DEPARTMENT", null, now.plusMillis(3));

        ManagerRelationship managerRelationship = relationshipCommands.assignManager(
                tenant,
                person.id(),
                manager.id(),
                now,
                null,
                RelationshipProvenance.manual(),
                now.plusSeconds(1));
        OrganizationMembership membership = relationshipCommands.addOrganizationMembership(
                tenant,
                person.id(),
                organization.id(),
                now,
                null,
                RelationshipProvenance.manual(),
                now.plusSeconds(1));
        OwnershipRelationship ownership = relationshipCommands.assignOwner(
                tenant,
                service.id(),
                person.id(),
                now,
                null,
                RelationshipProvenance.manual(),
                now.plusSeconds(1));

        assertThat(managerRelationship.effectiveAt(now.plusSeconds(2))).isTrue();
        assertThat(membership.effectiveAt(now.plusSeconds(2))).isTrue();
        assertThat(ownership.effectiveAt(now.plusSeconds(2))).isTrue();

        ManagerRelationship ended = relationshipCommands.endManagerRelationship(
                tenant,
                managerRelationship.id(),
                now.plusSeconds(10),
                managerRelationship.revision(),
                now.plusSeconds(3));
        assertThat(ended.revision()).isEqualTo(2);
        assertThat(ended.validUntil()).isEqualTo(now.plusSeconds(10));
        assertThat(ended.effectiveAt(now.plusSeconds(11))).isFalse();

        assertThatThrownBy(() -> relationshipCommands.endManagerRelationship(
                        tenant,
                        managerRelationship.id(),
                        now.plusSeconds(9),
                        managerRelationship.revision(),
                        now.plusSeconds(4)))
                .isInstanceOf(StaleWriteException.class);
    }

    @Test
    void rejectsCrossTenantRelationshipsAndUnknownSourceProvenance() {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        TenantContext first = tenant("First Tenant", now);
        TenantContext second = tenant("Second Tenant", now);
        Identity firstIdentity = identity(first, IdentityType.PERSON, "First", now);
        Identity firstOwner = identity(first, IdentityType.PERSON, "First Owner", now.plusMillis(1));
        Identity secondIdentity = identity(second, IdentityType.PERSON, "Second", now);

        assertThatThrownBy(() -> relationshipCommands.assignManager(
                        first,
                        firstIdentity.id(),
                        secondIdentity.id(),
                        now,
                        null,
                        RelationshipProvenance.manual(),
                        now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identity does not exist");

        assertThatThrownBy(() -> relationshipCommands.assignOwner(
                        first,
                        firstIdentity.id(),
                        firstOwner.id(),
                        now,
                        null,
                        RelationshipProvenance.sourceRecord(UUID.randomUUID()),
                        now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("relationship SourceRecord does not exist");
    }

    private static TenantContext tenant(String name, Instant now) {
        return new TenantContext(tenants.createActive(name, now).id());
    }

    private static Identity identity(
            TenantContext tenant,
            IdentityType type,
            String displayName,
            Instant now) {
        IdentityProfile profile = switch (type) {
            case PERSON -> new IdentityProfile.PersonProfile();
            case SERVICE -> new IdentityProfile.ServiceProfile();
            case WORKLOAD -> new IdentityProfile.WorkloadProfile();
        };
        return identityCommands.create(
                tenant,
                type,
                profile,
                IdentityLifecycleState.ACTIVE,
                displayName,
                now,
                ids.nextId(),
                null);
    }
}
