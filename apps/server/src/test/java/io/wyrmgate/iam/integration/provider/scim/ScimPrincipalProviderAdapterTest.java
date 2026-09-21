package io.wyrmgate.iam.integration.provider.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.PrincipalObservation;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.provider.ConnectorSecretProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScimPrincipalProviderAdapterTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<RecordedRequest> requests = new ArrayList<>();
    private HttpServer server;
    private URI baseUri;
    private AtomicInteger activeStatus;

    @BeforeEach
    void startServer() throws IOException {
        requests.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2/");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void discoveryPaginatesAndProducesCompleteNormalizedPrincipalObservations() {
        server.createContext("/scim/v2/Users", exchange -> {
            requests.add(record(exchange));
            String query = exchange.getRequestURI().getQuery();
            if (query.contains("startIndex=1")) {
                respond(exchange, 200, """
                        {
                          "totalResults": 3,
                          "startIndex": 1,
                          "itemsPerPage": 2,
                          "Resources": [
                            {
                              "id": "u-1",
                              "userName": "alice",
                              "displayName": "Alice",
                              "active": true,
                              "name": {"givenName": "Alice", "familyName": "Example"},
                              "meta": {"version": "v1"}
                            },
                            {
                              "id": "u-2",
                              "userName": "bob",
                              "active": true,
                              "meta": {"version": "v2"}
                            }
                          ]
                        }
                        """);
            } else {
                respond(exchange, 200, """
                        {
                          "totalResults": 3,
                          "startIndex": 3,
                          "itemsPerPage": 1,
                          "Resources": [
                            {
                              "id": "u-3",
                              "userName": "carol",
                              "active": false,
                              "externalId": "hr-3",
                              "meta": {"version": "v3"}
                            }
                          ]
                        }
                        """);
            }
        });
        server.start();

        var batches = new ArrayList<List<PrincipalObservation>>();
        var result = adapter().discoverPrincipals(
                configuration(), "test-ref", null, batches::add);

        assertThat(result.coverage()).isEqualTo(ReconciliationCompleteness.COMPLETE);
        assertThat(result.nextCheckpoint()).isNull();
        assertThat(result.observations()).isEqualTo(3);
        assertThat(batches).hasSize(2);
        assertThat(batches.getFirst().getFirst().providerStableId()).isEqualTo("u-1");
        assertThat(batches.getFirst().getFirst().providerVersion()).isEqualTo("v1");
        assertThat(batches.getFirst().getFirst().observedState())
                .containsEntry("userName", "alice")
                .containsEntry("displayName", "Alice")
                .doesNotContainKey("schemas");
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request ->
                assertThat(request.authorization()).isEqualTo("Bearer connector-secret"));
    }

    @Test
    void resumedOrBoundedDiscoveryNeverClaimsCompleteCoverage() {
        server.createContext("/scim/v2/Users", exchange -> {
            requests.add(record(exchange));
            respond(exchange, 200, """
                    {
                      "totalResults": 10,
                      "startIndex": 5,
                      "itemsPerPage": 2,
                      "Resources": [
                        {"id": "u-5", "userName": "five"},
                        {"id": "u-6", "userName": "six"}
                      ]
                    }
                    """);
        });
        server.start();

        var limited = new ScimPrincipalProviderAdapter.Configuration(
                baseUri, 2, 1, Duration.ofSeconds(2), null);
        var result = adapter().discoverPrincipals(
                limited, "test-ref", "5", ignored -> {});

        assertThat(result.coverage()).isEqualTo(ReconciliationCompleteness.PARTIAL);
        assertThat(result.nextCheckpoint()).isEqualTo("7");
        assertThat(result.observations()).isEqualTo(2);
    }

    @Test
    void createUpdateAndDisablePreserveStableProviderIdentityVersionAndConditionalMutation() {
        AtomicInteger sequence = new AtomicInteger();
        server.createContext("/scim/v2/Users", exchange -> {
            requests.add(record(exchange));
            int current = sequence.incrementAndGet();
            if (current == 1) {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                respond(exchange, 201, """
                        {"id":"provider-123","userName":"alice","meta":{"version":"v7"}}
                        """, Map.of("X-Request-ID", "req-create"));
            } else {
                throw new AssertionError("unexpected collection request");
            }
        });
        server.createContext("/scim/v2/Users/provider-123", exchange -> {
            requests.add(record(exchange));
            int current = sequence.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("PATCH");
            assertThat(exchange.getRequestHeaders().getFirst("If-Match")).isEqualTo("v7");
            if (current == 2) {
                respond(exchange, 200, """
                        {"id":"provider-123","meta":{"version":"v8"}}
                        """);
            } else if (current == 3) {
                respond(exchange, 200, """
                        {"id":"provider-123","active":false,"meta":{"version":"v9"}}
                        """);
            } else {
                throw new AssertionError("unexpected principal request");
            }
        });
        server.start();

        var adapter = adapter();
        var configuration = new ScimPrincipalProviderAdapter.Configuration(
                baseUri, 100, 100, Duration.ofSeconds(2), "Idempotency-Key");

        var created = adapter.createPrincipal(
                configuration,
                "test-ref",
                new ScimPrincipalProviderAdapter.PrincipalWrite(
                        "alice", "Alice Example", "hr-1", true),
                "op-create");
        assertThat(created.providerStableId()).isEqualTo("provider-123");
        assertThat(created.providerVersion()).isEqualTo("v7");
        assertThat(created.providerRequestId()).isEqualTo("req-create");

        var updated = adapter.updatePrincipal(
                configuration,
                "test-ref",
                "provider-123",
                "v7",
                new ScimPrincipalProviderAdapter.PrincipalWrite(
                        "alice", "Alice Updated", "hr-1", true),
                "op-update");
        assertThat(updated.providerStableId()).isEqualTo("provider-123");
        assertThat(updated.providerVersion()).isEqualTo("v8");

        var disabled = adapter.disablePrincipal(
                configuration,
                "test-ref",
                "provider-123",
                "v7",
                "op-disable");
        assertThat(disabled.providerStableId()).isEqualTo("provider-123");
        assertThat(disabled.providerVersion()).isEqualTo("v9");

        assertThat(requests).hasSize(3);
        assertThat(requests).extracting(RecordedRequest::idempotencyKey)
                .containsExactly("op-create", "op-update", "op-disable");
        assertThat(requests.get(2).body())
                .contains("\"path\":\"active\"")
                .contains("false");
    }

    @Test
    void providerFailuresAreNormalizedWithoutLeakingSecretOrProviderDetail() {
        activeStatus = new AtomicInteger(429);
        AtomicReference<String> detail =
                new AtomicReference<>("secret detail connector-secret");
        server.createContext("/scim/v2/Users", exchange -> {
            requests.add(record(exchange));
            respond(exchange, activeStatus.get(), """
                    {
                      "schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],
                      "status":"%d",
                      "scimType":"provider-code",
                      "detail":"%s"
                    }
                    """.formatted(activeStatus.get(), detail.get()),
                    activeStatus.get() == 429
                            ? Map.of("Retry-After", "17")
                            : Map.of());
        });
        server.start();

        assertCategory(429, ScimProviderException.FailureCategory.RATE_LIMITED, 17);
        assertCategory(503, ScimProviderException.FailureCategory.TRANSIENT, null);
        assertCategory(401, ScimProviderException.FailureCategory.AUTHENTICATION, null);
        assertCategory(403, ScimProviderException.FailureCategory.AUTHORIZATION, null);
        assertCategory(400, ScimProviderException.FailureCategory.VALIDATION, null);
    }

    private void assertCategory(
            int httpStatus,
            ScimProviderException.FailureCategory category,
            Integer retryAfter) {
        activeStatus.set(httpStatus);
        assertThatThrownBy(() -> adapter().discoverPrincipals(
                        configuration(), "test-ref", null, ignored -> {}))
                .isInstanceOfSatisfying(ScimProviderException.class, error -> {
                    assertThat(error.category()).isEqualTo(category);
                    assertThat(error.retryAfterSeconds()).isEqualTo(retryAfter);
                    assertThat(error.providerErrorCode()).isEqualTo("provider-code");
                    assertThat(error.getMessage()).doesNotContain("connector-secret");
                    assertThat(error.getMessage()).doesNotContain("secret detail");
                });
    }

    private ScimPrincipalProviderAdapter adapter() {
        ConnectorSecretProvider secretProvider = reference -> {
            assertThat(reference).isEqualTo("test-ref");
            return "connector-secret".toCharArray();
        };
        return new ScimPrincipalProviderAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build(),
                json,
                secretProvider);
    }

    private ScimPrincipalProviderAdapter.Configuration configuration() {
        return new ScimPrincipalProviderAdapter.Configuration(
                baseUri, 2, 100, Duration.ofSeconds(2), null);
    }

    private RecordedRequest record(HttpExchange exchange) throws IOException {
        return new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body) throws IOException {
        respond(exchange, status, body, Map.of());
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body,
            Map<String,String> headers) throws IOException {
        headers.forEach((name, value) ->
                exchange.getResponseHeaders().set(name, value));
        exchange.getResponseHeaders().set(
                "Content-Type", "application/scim+json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record RecordedRequest(
            String method,
            String authorization,
            String idempotencyKey,
            String body) {}
}
