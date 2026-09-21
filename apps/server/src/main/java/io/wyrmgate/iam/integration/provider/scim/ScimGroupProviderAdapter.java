package io.wyrmgate.iam.integration.provider.scim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorPayloadGuard;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProviderObservation;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.provider.ConnectorSecretProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * SCIM 2.0 provider-edge adapter for Group observations and user membership mutation.
 *
 * <p>SCIM Groups remain provider observations. This adapter never creates Catalog Entitlement
 * authority or AccessAssignment intent.
 */
public final class ScimGroupProviderAdapter {

    public static final String RUNTIME_ID = "scim-2";
    public static final String RUNTIME_VERSION = "1.0";
    public static final String CONTRACT_ID = "scim-2.group";
    public static final int CONTRACT_VERSION = 1;

    private static final TypeReference<Map<String,Object>> MAP_TYPE = new TypeReference<>() {};

    private final HttpClient http;
    private final ObjectMapper json;
    private final ConnectorSecretProvider secrets;

    public ScimGroupProviderAdapter(
            HttpClient http,
            ObjectMapper json,
            ConnectorSecretProvider secrets) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    public DiscoveryResult discoverEntitlements(
            Configuration configuration,
            String secretReference,
            String checkpoint,
            Consumer<List<ProviderObservation>> batchConsumer) {
        return discoverGroups(
                configuration,
                secretReference,
                checkpoint,
                batchConsumer,
                DiscoveryMode.ENTITLEMENT);
    }

    public DiscoveryResult discoverGrants(
            Configuration configuration,
            String secretReference,
            String checkpoint,
            Consumer<List<ProviderObservation>> batchConsumer) {
        return discoverGroups(
                configuration,
                secretReference,
                checkpoint,
                batchConsumer,
                DiscoveryMode.GRANT);
    }

    public ProvisioningResult addGrant(
            Configuration configuration,
            String secretReference,
            String providerGroupId,
            String providerGroupVersion,
            String providerPrincipalId,
            String idempotencyKey) {
        Map<String,Object> body = Map.of(
                "schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"),
                "Operations", List.of(Map.of(
                        "op", "Add",
                        "path", "members",
                        "value", List.of(Map.of(
                                "value", required(providerPrincipalId, "providerPrincipalId"))))));
        return mutateGroup(
                configuration,
                secretReference,
                providerGroupId,
                providerGroupVersion,
                body,
                idempotencyKey);
    }

    public ProvisioningResult removeGrant(
            Configuration configuration,
            String secretReference,
            String providerGroupId,
            String providerGroupVersion,
            String providerPrincipalId,
            String idempotencyKey) {
        String member = required(providerPrincipalId, "providerPrincipalId");
        String path = "members[value eq \"" + escapeFilterString(member) + "\"]";
        Map<String,Object> body = Map.of(
                "schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"),
                "Operations", List.of(Map.of(
                        "op", "Remove",
                        "path", path)));
        return mutateGroup(
                configuration,
                secretReference,
                providerGroupId,
                providerGroupVersion,
                body,
                idempotencyKey);
    }

    private DiscoveryResult discoverGroups(
            Configuration configuration,
            String secretReference,
            String checkpoint,
            Consumer<List<ProviderObservation>> batchConsumer,
            DiscoveryMode mode) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(batchConsumer, "batchConsumer");

        int startIndex = parseCheckpoint(checkpoint);
        boolean startedAtBeginning = startIndex == 1;
        int pages = 0;
        int observed = 0;
        char[] secret = secrets.resolve(secretReference);
        try {
            while (pages < configuration.maxPagesPerExecution()) {
                URI uri = groupsUri(
                        configuration.baseUri(), startIndex, configuration.pageSize());
                HttpResponse<String> response =
                        send(request(configuration, secret, uri).GET().build());
                Map<String,Object> document =
                        successDocument(response, "SCIM Group discovery failed");
                List<Map<String,Object>> resources = resourceList(document.get("Resources"));
                List<ProviderObservation> observations = new ArrayList<>();
                for (Map<String,Object> group : resources) {
                    if (mode == DiscoveryMode.ENTITLEMENT) {
                        observations.add(entitlementObservation(group));
                    } else {
                        observations.addAll(grantObservations(group));
                    }
                }
                if (!observations.isEmpty()) {
                    batchConsumer.accept(List.copyOf(observations));
                    observed += observations.size();
                }

                int pageStart = integer(document.get("startIndex"), startIndex);
                int itemsPerPage = integer(document.get("itemsPerPage"), resources.size());
                int totalResults = integer(document.get("totalResults"), -1);
                pages++;

                if (resources.isEmpty()) {
                    return new DiscoveryResult(
                            observed,
                            startedAtBeginning
                                    ? ReconciliationCompleteness.COMPLETE
                                    : ReconciliationCompleteness.PARTIAL,
                            null);
                }
                int next = pageStart + Math.max(itemsPerPage, resources.size());
                if (totalResults >= 0 && next > totalResults) {
                    return new DiscoveryResult(
                            observed,
                            startedAtBeginning
                                    ? ReconciliationCompleteness.COMPLETE
                                    : ReconciliationCompleteness.PARTIAL,
                            null);
                }
                startIndex = next;
            }
            return new DiscoveryResult(
                    observed,
                    ReconciliationCompleteness.PARTIAL,
                    Integer.toString(startIndex));
        } finally {
            ConnectorSecretProvider.destroy(secret);
        }
    }

    private ProvisioningResult mutateGroup(
            Configuration configuration,
            String secretReference,
            String providerGroupId,
            String providerGroupVersion,
            Map<String,Object> body,
            String idempotencyKey) {
        ConnectorPayloadGuard.requireSecretFree(body);
        char[] secret = secrets.resolve(secretReference);
        try {
            HttpRequest.Builder builder = request(
                    configuration,
                    secret,
                    groupUri(configuration.baseUri(), providerGroupId))
                    .header("Content-Type", "application/scim+json");
            if (providerGroupVersion != null && !providerGroupVersion.isBlank()) {
                builder.header("If-Match", providerGroupVersion);
            }
            if (configuration.idempotencyHeader() != null
                    && !configuration.idempotencyHeader().isBlank()
                    && idempotencyKey != null
                    && !idempotencyKey.isBlank()) {
                builder.header(configuration.idempotencyHeader(), idempotencyKey);
            }
            String encoded;
            try {
                encoded = json.writeValueAsString(body);
            } catch (JsonProcessingException invalid) {
                throw new IllegalArgumentException(
                        "SCIM Group PATCH payload is not JSON serializable", invalid);
            }
            HttpResponse<String> response = send(builder
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(encoded))
                    .build());
            Map<String,Object> document =
                    successDocument(response, "SCIM Group membership provisioning failed");
            String providerObjectId = string(document.get("id"));
            if (providerObjectId == null || providerObjectId.isBlank()) {
                providerObjectId = providerGroupId;
            }
            String version = nestedString(document, "meta", "version");
            if (version == null) {
                version = response.headers().firstValue("ETag").orElse(null);
            }
            return new ProvisioningResult(
                    providerObjectId,
                    version,
                    requestId(response));
        } finally {
            ConnectorSecretProvider.destroy(secret);
        }
    }

    private static ProviderObservation entitlementObservation(Map<String,Object> group) {
        String groupId = required(string(group.get("id")), "provider group id");
        Map<String,Object> state = new LinkedHashMap<>();
        copyIfPresent(group, state, "displayName");
        copyIfPresent(group, state, "externalId");
        ConnectorPayloadGuard.requireSecretFree(state);
        return new ProviderObservation(
                "ENTITLEMENT",
                groupId,
                nestedString(group, "meta", "version"),
                state);
    }

    private static List<ProviderObservation> grantObservations(Map<String,Object> group) {
        String groupId = required(string(group.get("id")), "provider group id");
        String groupVersion = nestedString(group, "meta", "version");
        Object rawMembers = group.get("members");
        if (rawMembers == null) return List.of();
        if (!(rawMembers instanceof List<?> members)) {
            throw invalidResponse("SCIM Group members must be a collection");
        }

        List<ProviderObservation> observations = new ArrayList<>();
        for (Object raw : members) {
            if (!(raw instanceof Map<?,?> member)) {
                throw invalidResponse("SCIM Group member must be an object");
            }
            String type = string(member.get("type"));
            if (type != null && !"User".equalsIgnoreCase(type)) {
                continue;
            }
            String principalId = required(
                    string(member.get("value")), "provider group member id");
            Map<String,Object> state = new LinkedHashMap<>();
            state.put("principalProviderId", principalId);
            state.put("entitlementProviderId", groupId);
            if (type != null) state.put("memberType", type);
            Object display = member.get("display");
            if (display != null) state.put("memberDisplay", display);
            ConnectorPayloadGuard.requireSecretFree(state);
            observations.add(new ProviderObservation(
                    "GRANT",
                    grantStableId(groupId, principalId),
                    groupVersion,
                    state));
        }
        return observations;
    }

    private HttpRequest.Builder request(
            Configuration configuration, char[] secret, URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(configuration.requestTimeout())
                .header("Accept", "application/scim+json, application/json")
                .header("Authorization", "Bearer " + new String(secret));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw transientFailure("SCIM provider call interrupted");
        } catch (IOException io) {
            throw transientFailure("SCIM provider call failed");
        }
    }

    private Map<String,Object> successDocument(
            HttpResponse<String> response, String message) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw providerFailure(response, message);
        }
        if (response.body() == null || response.body().isBlank()) return Map.of();
        try {
            return json.readValue(response.body(), MAP_TYPE);
        } catch (JsonProcessingException invalid) {
            throw new ScimProviderException(
                    ScimProviderException.FailureCategory.PROVIDER,
                    "invalid_response",
                    requestId(response),
                    null,
                    status,
                    "SCIM provider returned an invalid JSON response");
        }
    }

    private ScimProviderException providerFailure(
            HttpResponse<String> response, String message) {
        int status = response.statusCode();
        Map<String,Object> error = Map.of();
        if (response.body() != null && !response.body().isBlank()) {
            try {
                error = json.readValue(response.body(), MAP_TYPE);
            } catch (JsonProcessingException ignored) {
                error = Map.of();
            }
        }
        String scimType = string(error.get("scimType"));
        String providerCode =
                scimType != null ? scimType : string(error.get("status"));
        if (providerCode == null) providerCode = "http_" + status;

        ScimProviderException.FailureCategory category = switch (status) {
            case 400, 409, 412, 422 ->
                    ScimProviderException.FailureCategory.VALIDATION;
            case 401 -> ScimProviderException.FailureCategory.AUTHENTICATION;
            case 403 -> ScimProviderException.FailureCategory.AUTHORIZATION;
            case 429 -> ScimProviderException.FailureCategory.RATE_LIMITED;
            case 501 -> ScimProviderException.FailureCategory.UNSUPPORTED;
            default -> status >= 500
                    ? ScimProviderException.FailureCategory.TRANSIENT
                    : ScimProviderException.FailureCategory.PROVIDER;
        };
        return new ScimProviderException(
                category,
                providerCode,
                requestId(response),
                status == 429 ? retryAfter(response) : null,
                status,
                message);
    }

    private static ScimProviderException transientFailure(String message) {
        return new ScimProviderException(
                ScimProviderException.FailureCategory.TRANSIENT,
                "transport_failure",
                null,
                null,
                0,
                message);
    }

    private static ScimProviderException invalidResponse(String message) {
        return new ScimProviderException(
                ScimProviderException.FailureCategory.PROVIDER,
                "invalid_response",
                null,
                null,
                200,
                message);
    }

    private static List<Map<String,Object>> resourceList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw invalidResponse("SCIM provider returned an invalid Resources collection");
        }
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?,?> raw)) {
                throw invalidResponse("SCIM provider returned an invalid Group resource");
            }
            Map<String,Object> mapped = new LinkedHashMap<>();
            raw.forEach((key, itemValue) ->
                    mapped.put(String.valueOf(key), itemValue));
            result.add(mapped);
        }
        return result;
    }

    private static String grantStableId(String groupId, String principalId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] group = groupId.getBytes(StandardCharsets.UTF_8);
            byte[] principal = principalId.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(group.length).array());
            digest.update(group);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(principal.length).array());
            digest.update(principal);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String escapeFilterString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void copyIfPresent(
            Map<String,Object> source, Map<String,Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private static int parseCheckpoint(String checkpoint) {
        if (checkpoint == null || checkpoint.isBlank()) return 1;
        try {
            int value = Integer.parseInt(checkpoint);
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "SCIM checkpoint must be a positive startIndex");
        }
    }

    private static int integer(Object value, int defaultValue) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String nestedString(
            Map<String,Object> source, String outer, String inner) {
        Object nested = source.get(outer);
        if (!(nested instanceof Map<?,?> map)) return null;
        return string(map.get(inner));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static URI groupsUri(URI baseUri) {
        return ensureTrailingSlash(baseUri).resolve("Groups");
    }

    private static URI groupsUri(URI baseUri, int startIndex, int count) {
        URI groups = groupsUri(baseUri);
        return URI.create(groups + "?startIndex=" + startIndex + "&count=" + count);
    }

    private static URI groupUri(URI baseUri, String providerGroupId) {
        String encoded = URLEncoder.encode(
                required(providerGroupId, "providerGroupId"), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ensureTrailingSlash(baseUri).resolve("Groups/" + encoded);
    }

    private static URI ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue("X-Request-ID")
                .or(() -> response.headers().firstValue("Request-ID"))
                .orElse(null);
    }

    private static Integer retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private enum DiscoveryMode {
        ENTITLEMENT,
        GRANT
    }

    public record Configuration(
            URI baseUri,
            int pageSize,
            int maxPagesPerExecution,
            Duration requestTimeout,
            String idempotencyHeader) {
        public Configuration {
            Objects.requireNonNull(baseUri, "baseUri");
            if (!List.of("http", "https").contains(
                    baseUri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("SCIM baseUri must use HTTP(S)");
            }
            if (pageSize < 1 || pageSize > 1000) {
                throw new IllegalArgumentException(
                        "SCIM pageSize must be between 1 and 1000");
            }
            if (maxPagesPerExecution < 1 || maxPagesPerExecution > 10_000) {
                throw new IllegalArgumentException(
                        "SCIM maxPagesPerExecution is outside limits");
            }
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "SCIM requestTimeout must be positive");
            }
            if (idempotencyHeader != null
                    && !idempotencyHeader.isBlank()
                    && !idempotencyHeader.matches("[A-Za-z0-9-]+")) {
                throw new IllegalArgumentException(
                        "SCIM idempotencyHeader is invalid");
            }
        }
    }

    public record DiscoveryResult(
            int observations,
            ReconciliationCompleteness coverage,
            String nextCheckpoint) {}

    public record ProvisioningResult(
            String providerStableId,
            String providerVersion,
            String providerRequestId) {}
}
