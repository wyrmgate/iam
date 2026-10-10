package io.wyrmgate.iam.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

class IntegrationAdminOpenApiAlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mutationSchemasMatchRuntimeRequestRecords() throws Exception {
        JsonNode document = contract();

        assertRecordMatchesSchema(document, "ConnectorCreateRequest",
                IntegrationAdminApiModels.ConnectorCreateRequest.class);
        assertRecordMatchesSchema(document, "ConnectorUpdateRequest",
                IntegrationAdminApiModels.ConnectorUpdateRequest.class);
        assertRecordMatchesSchema(document, "ConnectorBindingCreateRequest",
                IntegrationAdminApiModels.BindingCreateRequest.class);
        assertRecordMatchesSchema(document, "ConnectorBindingUpdateRequest",
                IntegrationAdminApiModels.BindingUpdateRequest.class);
        assertRecordMatchesSchema(document, "ConnectorWorkerCreateRequest",
                IntegrationAdminApiModels.WorkerCreateRequest.class);
        assertRecordMatchesSchema(document, "ConnectorWorkerUpdateRequest",
                IntegrationAdminApiModels.WorkerUpdateRequest.class);
        assertRecordMatchesSchema(document, "WorkerPermission",
                IntegrationAdminApiModels.WorkerPermissionRequest.class);

        JsonNode schemas = document.path("components").path("schemas");
        assertThat(propertyNames(schemas.path("ConnectorUpdateRequest")))
                .doesNotContain("connectorType", "id", "revision", "lifecycleState");
        assertThat(propertyNames(schemas.path("ConnectorBindingUpdateRequest")))
                .doesNotContain("connectorInstanceId", "targetKind", "targetId", "lifecycleState");
        assertThat(propertyNames(schemas.path("ConnectorWorkerUpdateRequest")))
                .doesNotContain("issuer", "subject", "state", "id", "revision");
        assertThat(propertyNames(schemas.path("ConnectorResource")))
                .doesNotContain("secretReference");
        assertThat(schemas.path("ConnectorCreateRequest").path("properties")
                .path("secretReference").path("writeOnly").asBoolean()).isTrue();
        assertThat(schemas.path("ConnectorUpdateRequest").path("properties")
                .path("secretReference").path("writeOnly").asBoolean()).isTrue();
    }

    @Test
    void everyMutationCarriesItsConcurrencyIdempotencyAndTypedBodyContract() throws Exception {
        JsonNode document = contract();

        Map<String, String> creates = Map.of(
                "/api/v1/connectors", "ConnectorCreateRequest",
                "/api/v1/connector-bindings", "ConnectorBindingCreateRequest",
                "/api/v1/connector-workers", "ConnectorWorkerCreateRequest");
        creates.forEach((path, schema) -> {
            JsonNode operation = document.path("paths").path(path).path("post");
            assertThat(parameterRefs(operation)).containsExactly("IdempotencyKey");
            assertThat(requestBodyRef(operation)).isEqualTo(schema);
            assertThat(operation.path("responses").has("409")).isTrue();
        });

        Map<String, String> updates = Map.of(
                "/api/v1/connectors/{id}", "ConnectorUpdateRequest",
                "/api/v1/connector-bindings/{id}", "ConnectorBindingUpdateRequest",
                "/api/v1/connector-workers/{id}", "ConnectorWorkerUpdateRequest");
        updates.forEach((path, schema) -> {
            JsonNode operation = document.path("paths").path(path).path("patch");
            assertThat(parameterRefs(operation))
                    .containsExactly("ResourceId", "IfMatch", "IdempotencyKey");
            assertThat(requestBodyRef(operation)).isEqualTo(schema);
            assertThat(operation.path("responses").has("409")).isTrue();
            assertThat(operation.path("responses").has("412")).isTrue();
        });

        for (String path : Set.of(
                "/api/v1/connectors/{id}:disable",
                "/api/v1/connector-bindings/{id}:disable",
                "/api/v1/connector-workers/{id}:disable",
                "/api/v1/entitlement-observation-mappings/{id}:unmap")) {
            JsonNode operation = document.path("paths").path(path).path("post");
            assertThat(parameterRefs(operation))
                    .containsExactly("ResourceId", "IfMatch", "IdempotencyKey");
            assertThat(operation.has("requestBody")).isFalse();
            assertThat(operation.path("responses").has("409")).isTrue();
            assertThat(operation.path("responses").has("412")).isTrue();
        }

        assertThat(document.path("components").path("parameters").path("IfMatch")
                .path("schema").path("pattern").asText())
                .isEqualTo("^\"rev-[1-9][0-9]*\"$");
    }

    @Test
    void controllerMappingsRemainAlignedWithPublishedControlPlanePaths() throws Exception {
        assertPost("createConnector", "/connectors");
        assertPatch("updateConnector", "/connectors/{id}");
        assertPost("disableConnector", "/connectors/{id}:disable");
        assertPost("createBinding", "/connector-bindings");
        assertPatch("updateBinding", "/connector-bindings/{id}");
        assertPost("disableBinding", "/connector-bindings/{id}:disable");
        assertPost("createWorker", "/connector-workers");
        assertPatch("updateWorker", "/connector-workers/{id}");
        assertPost("disableWorker", "/connector-workers/{id}:disable");
    }

    private static void assertRecordMatchesSchema(
            JsonNode document, String schemaName, Class<?> recordType) {
        Set<String> runtimeFields = Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        JsonNode schema = document.path("components").path("schemas").path(schemaName);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(propertyNames(schema)).isEqualTo(runtimeFields);
        assertThat(StreamSupport.stream(schema.path("required").spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toSet()))
                .isSubsetOf(runtimeFields);
    }

    private static Set<String> propertyNames(JsonNode schema) {
        return StreamSupport.stream(
                        ((Iterable<Map.Entry<String, JsonNode>>) () ->
                                schema.path("properties").fields()).spliterator(), false)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static Set<String> parameterRefs(JsonNode operation) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .map(node -> tail(node.path("$ref").asText()))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String requestBodyRef(JsonNode operation) {
        return tail(operation.path("requestBody").path("$ref").asText());
    }

    private static String tail(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    private static void assertPost(String methodName, String path) {
        var method = Arrays.stream(IntegrationAdministrationController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private static void assertPatch(String methodName, String path) {
        var method = Arrays.stream(IntegrationAdministrationController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        PatchMapping mapping = method.getAnnotation(PatchMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private static JsonNode contract() throws Exception {
        try (InputStream stream = IntegrationAdminOpenApiAlignmentTest.class
                .getResourceAsStream("/contracts/openapi/integration-admin-v1.json")) {
            assertThat(stream).isNotNull();
            return JSON.readTree(stream);
        }
    }
}
