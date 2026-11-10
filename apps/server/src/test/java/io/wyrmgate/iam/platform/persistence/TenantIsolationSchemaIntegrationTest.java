package io.wyrmgate.iam.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies Tenant isolation plus explicit deployment ownership under ADR-0049/ADR-0051. */
class TenantIsolationSchemaIntegrationTest {

    private static final Set<String> APPLICATION_SCHEMAS = Set.of(
            "identity",
            "catalog",
            "access",
            "governance",
            "credential",
            "integration",
            "administration",
            "audit",
            "platform",
            "tenancy");

    /**
     * Explicit deployment-owned tables accepted by architecture. Adding to this set is an
     * architecture change, not a workaround for a missing tenant_id.
     */
    private static final Set<String> DEPLOYMENT_OWNED_TABLES = Set.of(
            "administration.platform_owner_authority",
            "catalog.platform_application_offering",
            "catalog.platform_application_tenant_admission",
            "catalog.platform_application_binding");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void resetDatabase() {
        jdbc = SharedPostgresIntegrationDatabase.jdbc();
    }

    @Test
    void everyTenantOwnedApplicationTableHasRequiredTenantId() {
        List<TableTenantColumn> tables = applicationTables();

        assertThat(tables).isNotEmpty();
        assertThat(tables)
                .filteredOn(table -> isTenantRoot(table.schema(), table.table()))
                .singleElement()
                .satisfies(table -> {
                    assertThat(table.hasTenantId()).isFalse();
                    assertThat(table.tenantIdNotNull()).isFalse();
                });

        assertThat(tables)
                .filteredOn(table -> !isTenantRoot(table.schema(), table.table()))
                .filteredOn(table -> !isDeploymentOwned(table.schema(), table.table()))
                .allSatisfy(table -> {
                    assertThat(table.hasTenantId())
                            .as("%s.%s has tenant_id", table.schema(), table.table())
                            .isTrue();
                    assertThat(table.tenantIdNotNull())
                            .as("%s.%s tenant_id is NOT NULL", table.schema(), table.table())
                            .isTrue();
                });
    }

    @Test
    void deploymentOwnedTablesAreExplicitAndNeverUseNullableTenantOwnership() {
        List<TableTenantColumn> deploymentOwned = applicationTables().stream()
                .filter(table -> isDeploymentOwned(table.schema(), table.table()))
                .toList();

        assertThat(deploymentOwned)
                .extracting(table -> table.schema() + "." + table.table())
                .containsExactlyInAnyOrderElementsOf(DEPLOYMENT_OWNED_TABLES);

        assertThat(deploymentOwned)
                .filteredOn(TableTenantColumn::hasTenantId)
                .allSatisfy(table -> assertThat(table.tenantIdNotNull())
                        .as("%s.%s tenant reference is NOT NULL", table.schema(), table.table())
                        .isTrue());

        assertThat(deploymentOwned)
                .filteredOn(table -> !table.hasTenantId())
                .extracting(table -> table.schema() + "." + table.table())
                .containsExactlyInAnyOrder(
                        "administration.platform_owner_authority",
                        "catalog.platform_application_offering");
    }

    @Test
    void sameCapabilityForeignKeysCarryTenantOnBothSidesUnlessDeploymentOwned() {
        List<ForeignKeyShape> foreignKeys = foreignKeys();

        assertThat(foreignKeys)
                .filteredOn(fk -> fk.sourceSchema().equals(fk.targetSchema()))
                .filteredOn(fk -> !isTenantRootAnchor(fk))
                .filteredOn(fk -> !isDeploymentOwned(fk.sourceSchema(), fk.sourceTable()))
                .filteredOn(fk -> !isDeploymentOwned(fk.targetSchema(), fk.targetTable()))
                .allSatisfy(fk -> {
                    assertThat(fk.sourceColumns())
                            .as("%s source columns", fk.describe())
                            .contains("tenant_id");
                    assertThat(fk.targetColumns())
                            .as("%s target columns", fk.describe())
                            .contains("tenant_id");
                });
    }

    @Test
    void tenantRootForeignKeysAreEitherTenantOwnershipOrExplicitDeploymentReferences() {
        List<ForeignKeyShape> tenantRootAnchors = foreignKeys().stream()
                .filter(fk -> fk.targetSchema().equals("tenancy") && fk.targetTable().equals("tenant"))
                .toList();

        assertThat(tenantRootAnchors).isNotEmpty();
        assertThat(tenantRootAnchors).allSatisfy(fk -> {
            assertThat(fk.targetColumns()).as(fk.describe()).containsExactly("id");
            if (isDeploymentOwned(fk.sourceSchema(), fk.sourceTable())) {
                boolean approvedReference = fk.sourceColumns().equals(List.of("tenant_id"))
                        || fk.sourceColumns().equals(List.of("actor_tenant_id"));
                assertThat(approvedReference).as(fk.describe()).isTrue();
            } else {
                assertThat(fk.sourceColumns()).as(fk.describe()).containsExactly("tenant_id");
            }
        });
    }

    @Test
    void tenantRootCarriesRequiredCanonicalLifecycleState() {
        Integer nullable = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'tenancy'
                  AND table_name = 'tenant'
                  AND column_name = 'lifecycle_state'
                  AND is_nullable = 'YES'
                """,
                Integer.class);
        assertThat(nullable).isZero();
    }

    @Test
    void crossCapabilityForeignKeysOnlyReferenceTenancyTenant() {
        List<ForeignKeyShape> crossCapability = foreignKeys().stream()
                .filter(fk -> !fk.sourceSchema().equals(fk.targetSchema()))
                .toList();

        assertThat(crossCapability).isNotEmpty();
        assertThat(crossCapability).allSatisfy(fk -> {
            assertThat(fk.targetSchema()).as(fk.describe()).isEqualTo("tenancy");
            assertThat(fk.targetTable()).as(fk.describe()).isEqualTo("tenant");
            assertThat(fk.targetColumns()).as(fk.describe()).containsExactly("id");
            if (!isDeploymentOwned(fk.sourceSchema(), fk.sourceTable())) {
                assertThat(fk.sourceColumns()).as(fk.describe()).containsExactly("tenant_id");
            }
        });
    }

    private static List<TableTenantColumn> applicationTables() {
        return jdbc.query(
                """
                SELECT n.nspname,
                       c.relname,
                       a.attname IS NOT NULL AS has_tenant_id,
                       COALESCE(a.attnotnull, false) AS tenant_id_not_null
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_attribute a
                  ON a.attrelid = c.oid
                 AND a.attname = 'tenant_id'
                 AND a.attnum > 0
                 AND NOT a.attisdropped
                WHERE c.relkind IN ('r', 'p')
                  AND n.nspname = ANY (?)
                ORDER BY n.nspname, c.relname
                """,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", APPLICATION_SCHEMAS.toArray())),
                (rs, rowNum) -> new TableTenantColumn(
                        rs.getString("nspname"),
                        rs.getString("relname"),
                        rs.getBoolean("has_tenant_id"),
                        rs.getBoolean("tenant_id_not_null")));
    }

    private static boolean isTenantRoot(String schema, String table) {
        return schema.equals("tenancy") && table.equals("tenant");
    }

    private static boolean isDeploymentOwned(String schema, String table) {
        return DEPLOYMENT_OWNED_TABLES.contains(schema + "." + table);
    }

    private static boolean isTenantRootAnchor(ForeignKeyShape fk) {
        return fk.targetSchema().equals("tenancy")
                && fk.targetTable().equals("tenant")
                && fk.sourceColumns().equals(List.of("tenant_id"))
                && fk.targetColumns().equals(List.of("id"));
    }

    private static List<ForeignKeyShape> foreignKeys() {
        return jdbc.query(
                """
                SELECT source_ns.nspname AS source_schema,
                       source_table.relname AS source_table,
                       constraint_row.conname AS constraint_name,
                       target_ns.nspname AS target_schema,
                       target_table.relname AS target_table,
                       ARRAY(
                           SELECT source_attribute.attname
                           FROM unnest(constraint_row.conkey) WITH ORDINALITY key_column(attnum, ordinal_position)
                           JOIN pg_attribute source_attribute
                             ON source_attribute.attrelid = constraint_row.conrelid
                            AND source_attribute.attnum = key_column.attnum
                           ORDER BY key_column.ordinal_position
                       ) AS source_columns,
                       ARRAY(
                           SELECT target_attribute.attname
                           FROM unnest(constraint_row.confkey) WITH ORDINALITY key_column(attnum, ordinal_position)
                           JOIN pg_attribute target_attribute
                             ON target_attribute.attrelid = constraint_row.confrelid
                            AND target_attribute.attnum = key_column.attnum
                           ORDER BY key_column.ordinal_position
                       ) AS target_columns
                FROM pg_constraint constraint_row
                JOIN pg_class source_table ON source_table.oid = constraint_row.conrelid
                JOIN pg_namespace source_ns ON source_ns.oid = source_table.relnamespace
                JOIN pg_class target_table ON target_table.oid = constraint_row.confrelid
                JOIN pg_namespace target_ns ON target_ns.oid = target_table.relnamespace
                WHERE constraint_row.contype = 'f'
                  AND source_ns.nspname = ANY (?)
                  AND target_ns.nspname = ANY (?)
                ORDER BY source_ns.nspname, source_table.relname, constraint_row.conname
                """,
                ps -> {
                    Object[] schemas = APPLICATION_SCHEMAS.toArray();
                    ps.setArray(1, ps.getConnection().createArrayOf("text", schemas));
                    ps.setArray(2, ps.getConnection().createArrayOf("text", schemas));
                },
                (rs, rowNum) -> new ForeignKeyShape(
                        rs.getString("source_schema"),
                        rs.getString("source_table"),
                        rs.getString("constraint_name"),
                        rs.getString("target_schema"),
                        rs.getString("target_table"),
                        List.of((String[]) rs.getArray("source_columns").getArray()),
                        List.of((String[]) rs.getArray("target_columns").getArray())));
    }

    private record TableTenantColumn(
            String schema,
            String table,
            boolean hasTenantId,
            boolean tenantIdNotNull) {}

    private record ForeignKeyShape(
            String sourceSchema,
            String sourceTable,
            String constraintName,
            String targetSchema,
            String targetTable,
            List<String> sourceColumns,
            List<String> targetColumns) {

        String describe() {
            return sourceSchema + "." + sourceTable + " constraint " + constraintName
                    + " -> " + targetSchema + "." + targetTable;
        }
    }
}
