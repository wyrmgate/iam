package io.wyrmgate.iam.api.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.credential.application.CredentialQueryService;
import io.wyrmgate.iam.credential.application.CredentialRotationService;
import io.wyrmgate.iam.credential.application.CredentialService;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.credential.persistence.JdbcCredentialBoundaryScheduler;
import io.wyrmgate.iam.credential.persistence.JdbcCredentialRepository;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CredentialApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-09-29T14:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static JdbcIdempotencyRepository idempotency;

    private TenantContext tenant;
    private TenantContext otherTenant;
    private AuthenticatedAdministrativeActor actor;
    private UUID principalId;
    private JdbcCredentialRepository repository;
    private CredentialService credentials;
    private CredentialRotationService rotations;
    private CredentialQueryService queries;
    private MockMvc authorized;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current()
                .getVersion().getVersion())
                .isEqualTo("45");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        scheduledWork =
                new JdbcScheduledWorkRepository(jdbc, ids);
        idempotency =
                new JdbcIdempotencyRepository(jdbc, ids);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    credential.credential_rotation,
                    credential.credential,
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Credential API", NOW).id());
        otherTenant = new TenantContext(
                tenants.create("Credential API other", NOW).id());
        actor = new AuthenticatedAdministrativeActor(
                tenant, ids.nextId());
        principalId = ids.nextId();

        IdentityAccessReferenceQuery identityReferences =
                new IdentityAccessReferenceQuery() {
                    @Override
                    public boolean identityExists(
                            TenantContext requestedTenant,
                            UUID identityId) {
                        return requestedTenant.equals(tenant)
                                && identityId.equals(actor.identityId());
                    }

                    @Override
                    public PrincipalReference principal(
                            TenantContext requestedTenant,
                            UUID requestedPrincipalId) {
                        return requestedTenant.equals(tenant)
                                && requestedPrincipalId.equals(principalId)
                                ? PrincipalReference.uncorrelated(
                                        ids.nextId())
                                : PrincipalReference.notFound();
                    }

                    @Override
                    public PrincipalSelection
                            selectUniqueActivePrincipal(
                                    TenantContext requestedTenant,
                                    UUID identityId,
                                    UUID applicationTargetId) {
                        return PrincipalSelection.none();
                    }
                };

        repository = new JdbcCredentialRepository(jdbc);
        credentials = new CredentialService(
                repository,
                identityReferences,
                new JdbcCredentialBoundaryScheduler(
                        scheduledWork),
                ids,
                transactions);
        rotations = new CredentialRotationService(
                repository,
                identityReferences,
                ids,
                transactions);
        queries = new CredentialQueryService(repository);
        authorized = mockMvc(
                authorization(actor, true));
    }

    @Test
    void createReplayLifecycleAndRotationAreGoverned()
            throws Exception {
        String request = """
                {
                  "principalId":"%s",
                  "kind":"API_KEY",
                  "secretReference":{
                    "providerType":"vault",
                    "referenceKey":"services/payments/api-key"
                  },
                  "validFrom":null,
                  "validUntil":null
                }
                """.formatted(principalId);

        String location = authorized.perform(
                        post("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string("ETag", "\"rev-1\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("ACTIVE"))
                .andExpect(
                        jsonPath("$.secretReference.providerType")
                                .value("vault"))
                .andExpect(
                        jsonPath("$.secretReference.referenceKey")
                                .value("services/payments/api-key"))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID credentialId = UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1));

        authorized.perform(
                        post("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(credentialId.toString()));

        authorized.perform(
                        post("/api/v1/credentials/{id}:rotate",
                                credentialId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-rotate-0001"))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.oldCredentialId")
                                .value(credentialId.toString()))
                .andExpect(
                        jsonPath("$.initiatorIdentityId")
                                .value(actor.identityId().toString()))
                .andExpect(
                        jsonPath("$.processState")
                                .value("PLANNED"));

        authorized.perform(
                        post("/api/v1/credentials/{id}:compromise",
                                credentialId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "credential-compromise-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("COMPROMISED"));

        authorized.perform(
                        post("/api/v1/credentials/{id}:revoke",
                                credentialId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "credential-revoke-stale"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        authorized.perform(
                        post("/api/v1/credentials/{id}:revoke",
                                credentialId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "credential-revoke-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("REVOKED"));
    }

    @Test
    void listIsPrincipalBoundAndDefaultDeny() throws Exception {
        credentials.create(
                tenant,
                principalId,
                CredentialKind.SSH_KEY,
                new SecretReference("vault", "ssh/a"),
                null,
                null,
                NOW);
        credentials.create(
                tenant,
                principalId,
                CredentialKind.CERTIFICATE,
                new SecretReference("vault", "cert/b"),
                null,
                null,
                NOW.plusMillis(1));

        String firstPage = authorized.perform(
                        get("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param(
                                        "principalId",
                                        principalId.toString())
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstPage)
                .get("nextCursor")
                .asText();

        authorized.perform(
                        get("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param(
                                        "principalId",
                                        principalId.toString())
                                .param("cursor", cursor)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(1));

        authorized.perform(
                        get("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param(
                                        "principalId",
                                        ids.nextId().toString())
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.fieldErrors[0].code")
                                .value("invalid_cursor"));

        MockMvc denied = mockMvc(
                authorization(actor, false));
        denied.perform(
                        get("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param(
                                        "principalId",
                                        principalId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rawSecretFieldsAreRejectedWithoutEcho()
            throws Exception {
        String raw = "DO-NOT-ECHO-RAW-SECRET";
        authorized.perform(
                        post("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-secret-negative")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "principalId":"%s",
                                          "kind":"PASSWORD",
                                          "secretReference":{
                                            "providerType":"vault",
                                            "referenceKey":"passwords/service"
                                          },
                                          "validFrom":null,
                                          "validUntil":null,
                                          "password":"%s"
                                        }
                                        """.formatted(
                                        principalId, raw)))
                .andExpect(status().isBadRequest())
                .andExpect(
                        content().string(
                                org.hamcrest.Matchers
                                        .not(org.hamcrest.Matchers
                                                .containsString(raw))));
    }

    @Test
    void missingPrincipalAndCrossTenantCredentialAreNotUsable()
            throws Exception {
        authorized.perform(
                        post("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-missing-principal")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "principalId":"%s",
                                          "kind":"API_KEY",
                                          "secretReference":{
                                            "providerType":"vault",
                                            "referenceKey":"missing"
                                          },
                                          "validFrom":null,
                                          "validUntil":null
                                        }
                                        """.formatted(ids.nextId())))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        var foreign = credentials.create(
                tenant,
                principalId,
                CredentialKind.API_KEY,
                new SecretReference("vault", "tenant/a"),
                null,
                null,
                NOW);

        AuthenticatedAdministrativeActor otherActor =
                new AuthenticatedAdministrativeActor(
                        otherTenant, ids.nextId());
        MockMvc other = mockMvc(
                authorization(otherActor, true));
        other.perform(
                        get("/api/v1/credentials/{id}",
                                foreign.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        otherActor))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeyReuseWithDifferentCreateConflicts()
            throws Exception {
        String first = """
                {
                  "principalId":"%s",
                  "kind":"API_KEY",
                  "secretReference":{
                    "providerType":"vault",
                    "referenceKey":"one"
                  },
                  "validFrom":null,
                  "validUntil":null
                }
                """.formatted(principalId);
        String second = first.replace(
                "\"referenceKey\":\"one\"",
                "\"referenceKey\":\"two\"");

        authorized.perform(
                        post("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-conflict-key")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(first))
                .andExpect(status().isCreated());

        authorized.perform(
                        post("/api/v1/credentials")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "credential-conflict-key")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(second))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("idempotency_conflict"));
    }

    private MockMvc mockMvc(
            AdministrativeAuthorizationService authorization) {
        CredentialApiMutationService mutations =
                new CredentialApiMutationService(
                        authorization,
                        credentials,
                        rotations,
                        repository,
                        idempotency,
                        transactions);
        CredentialController controller =
                new CredentialController(
                        queries,
                        mutations,
                        authorization,
                        ids,
                        testCursorCodec());
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new CredentialApiErrorHandler(ids))
                .build();
    }

    private AdministrativeAuthorizationService authorization(
            AuthenticatedAdministrativeActor allowedActor,
            boolean allow) {
        Instant created =
                Instant.parse("2026-01-01T00:00:00Z");
        AdministrativeGrant grant =
                new AdministrativeGrant(
                        ids.nextId(),
                        allowedActor.identityId(),
                        ids.nextId(),
                        new AdministrativeScope(
                                AdministrativeScopeType.GLOBAL,
                                null,
                                null),
                        AdministrativeGrantState.ACTIVE,
                        created,
                        null,
                        1,
                        created,
                        created);
        return new AdministrativeAuthorizationService(
                (requestedTenant, identityId, permission) ->
                        allow
                                && requestedTenant.equals(
                                        allowedActor.tenant())
                                && identityId.equals(
                                        allowedActor.identityId())
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant, identityId) ->
                        requestedTenant.equals(
                                allowedActor.tenant())
                                && identityId.equals(
                                        allowedActor.identityId()));
    }

    private CredentialCursorCodec testCursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "credential-api-test",
                            "SHA256withRSA",
                            pair.getPublic());
            SigningKeyProvider provider =
                    new SigningKeyProvider() {
                        @Override
                        public SigningKeyMaterial
                                currentSigningKey() {
                            return material;
                        }

                        @Override
                        public Optional<SigningKeyMaterial>
                                verificationKey(String keyId) {
                            return material.keyId().equals(keyId)
                                    ? Optional.of(material)
                                    : Optional.empty();
                        }

                        @Override
                        public byte[] sign(byte[] payload) {
                            try {
                                Signature signature =
                                        Signature.getInstance(
                                                material.signingAlgorithm());
                                signature.initSign(pair.getPrivate());
                                signature.update(payload);
                                return signature.sign();
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        }
                    };
            return new CredentialCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.systemUTC());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
