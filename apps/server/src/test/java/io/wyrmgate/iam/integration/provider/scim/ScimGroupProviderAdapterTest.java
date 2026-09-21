package io.wyrmgate.iam.integration.provider.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProviderObservation;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScimGroupProviderAdapterTest {

    private HttpServer server;
    private URI baseUri;
    private ObjectMapper json;
    private AtomicReference<String> lastPatch;

    @BeforeEach
    void start() throws IOException {
        json = new ObjectMapper().findAndRegisterModules();
        lastPatch = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUri = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2/");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void groupsBecomeEntitlementObservationsWithoutCatalogSemantics() throws Exception {
        server.createContext("/scim/v2/Groups", exchange -> respond(exchange, 200, """
                {
                  "startIndex":1,
                  "itemsPerPage":2,
                  "totalResults":2,
                  "Resources":[
                    {
                      "id":"g-1",
                      "displayName":"Finance",
                      "externalId":"ext-finance",
                      "meta":{"version":"W/\\\"1\\\""},
                      "members":[{"value":"u-1","type":"User","display":"Alice"}]
                    },
                    {
                      "id":"g-2",
                      "displayName":"Engineering",
                      "meta":{"version":"W/\\\"2\\\""}
                    }
                  ]
                }
                """));
        server.start();

        List<ProviderObservation> observed = new ArrayList<>();
        var result = adapter().discoverEntitlements(
                configuration(), "fixture-ref", null, observed::addAll);

        assertThat(result.coverage()).isEqualTo(ReconciliationCompleteness.COMPLETE);
        assertThat(result.nextCheckpoint()).isNull();
        assertThat(observed).hasSize(2);
        assertThat(observed.get(0).objectClass()).isEqualTo("ENTITLEMENT");
        assertThat(observed.get(0).providerStableId()).isEqualTo("g-1");
        assertThat(observed.get(0).observedState())
                .containsEntry("displayName", "Finance")
                .containsEntry("externalId", "ext-finance")
                .doesNotContainKey("members");
    }

    @Test
    void userMembershipsBecomeGrantObservationsAndExplicitGroupMembersAreNotPrincipals() {
        server.createContext("/scim/v2/Groups", exchange -> respond(exchange, 200, """
                {
                  "startIndex":1,
                  "itemsPerPage":1,
                  "totalResults":1,
                  "Resources":[{
                    "id":"g-1",
                    "meta":{"version":"v5"},
                    "members":[
                      {"value":"u-1","type":"User","display":"Alice"},
                      {"value":"u-2"},
                      {"value":"nested-g","type":"Group"}
                    ]
                  }]
                }
                """));
        server.start();

        List<ProviderObservation> observed = new ArrayList<>();
        var result = adapter().discoverGrants(
                configuration(), "fixture-ref", null, observed::addAll);

        assertThat(result.coverage()).isEqualTo(ReconciliationCompleteness.COMPLETE);
        assertThat(observed).hasSize(2);
        assertThat(observed).allMatch(value -> "GRANT".equals(value.objectClass()));
        assertThat(observed).extracting(value ->
                        value.observedState().get("principalProviderId"))
                .containsExactly("u-1", "u-2");
        assertThat(observed).allMatch(value ->
                "g-1".equals(value.observedState().get("entitlementProviderId")));
        assertThat(observed.get(0).providerStableId())
                .isNotEqualTo(observed.get(1).providerStableId())
                .hasSize(64);
    }

    @Test
    void addAndRemoveMembershipUseScimPatchWithConcurrencyAndNoSecretPersistence() throws Exception {
        server.createContext("/scim/v2/Groups/g-1", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PATCH");
            assertThat(exchange.getRequestHeaders().getFirst("If-Match")).isEqualTo("v7");
            lastPatch.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"id":"g-1","meta":{"version":"v8"}}
                    """);
        });
        server.start();

        var add = adapter().addGrant(
                configuration(), "fixture-ref", "g-1", "v7", "u-1", "idem-fixture");
        assertThat(add.providerStableId()).isEqualTo("g-1");
        assertThat(add.providerVersion()).isEqualTo("v8");
        assertThat(lastPatch.get()).contains("\"op\":\"Add\"")
                .contains("\"path\":\"members\"")
                .contains("\"value\":\"u-1\"");

        var remove = adapter().removeGrant(
                configuration(), "fixture-ref", "g-1", "v7", "u-1", "idem-fixture-2");
        assertThat(remove.providerStableId()).isEqualTo("g-1");
        assertThat(lastPatch.get()).contains("\"op\":\"Remove\"")
                .contains("members[value eq \\\"u-1\\\"]");
        assertThat(lastPatch.get()).doesNotContain("fixture-secret");
    }

    private ScimGroupProviderAdapter adapter() {
        return new ScimGroupProviderAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build(),
                json,
                reference -> {
                    assertThat(reference).isEqualTo("fixture-ref");
                    return "fixture-secret".toCharArray();
                });
    }

    private ScimGroupProviderAdapter.Configuration configuration() {
        return new ScimGroupProviderAdapter.Configuration(
                baseUri, 100, 10, Duration.ofSeconds(2), "X-Fixture-Idempotency");
    }

    private static void respond(
            HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
