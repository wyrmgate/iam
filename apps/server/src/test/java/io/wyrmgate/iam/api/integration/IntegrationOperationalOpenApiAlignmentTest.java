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
import org.springframework.web.bind.annotation.GetMapping;

class IntegrationOperationalOpenApiAlignmentTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void publicSchemasMatchRuntimeResourcesAndExcludeWorkerOnlyFields() throws Exception {
        JsonNode document = contract();
        assertRecordMatchesSchema(document, "ProvisioningJobResource",
                IntegrationOperationalApiModels.ProvisioningJobResource.class);
        assertRecordMatchesSchema(document, "ProvisioningTaskResource",
                IntegrationOperationalApiModels.ProvisioningTaskResource.class);
        assertRecordMatchesSchema(document, "ProvisioningAttemptResource",
                IntegrationOperationalApiModels.ProvisioningAttemptResource.class);
        assertRecordMatchesSchema(document, "ReconciliationRunResource",
                IntegrationOperationalApiModels.ReconciliationRunResource.class);

        Set<String> forbidden = Set.of(
                "idempotencyKey", "payload", "leaseId", "leaseEpoch",
                "providerErrorCode", "providerRequestId", "providerObjectId",
                "providerVersion", "metadata", "completionFingerprint",
                "checkpointStart", "checkpointEnd", "completionLeaseEpoch");
        for (String schemaName : Set.of(
                "ProvisioningJobResource", "ProvisioningTaskResource",
                "ProvisioningAttemptResource", "ReconciliationRunResource")) {
            assertThat(propertyNames(document.path("components").path("schemas").path(schemaName)))
                    .doesNotContainAnyElementsOf(forbidden);
        }
    }

    @Test
    void controllerMappingsAreReadOnlyAndMatchPublishedPaths() throws Exception {
        assertGet("listProvisioningJobs", "/provisioning-jobs");
        assertGet("getProvisioningJob", "/provisioning-jobs/{id}");
        assertGet("listProvisioningTasks", "/provisioning-tasks");
        assertGet("getProvisioningTask", "/provisioning-tasks/{id}");
        assertGet("listProvisioningAttempts", "/provisioning-tasks/{taskId}/attempts");
        assertGet("getProvisioningAttempt", "/provisioning-attempts/{id}");
        assertGet("listReconciliationRuns", "/reconciliation-runs");
        assertGet("getReconciliationRun", "/reconciliation-runs/{id}");

        String contract = contract().toString();
        assertThat(contract).doesNotContain(
                ":claim", "lease/renew", "connector-worker/v1", "requestBody");
    }

    @Test
    void listOperationsUseBoundedCursorPagination() throws Exception {
        JsonNode document = contract();
        for (String path : Set.of(
                "/api/v1/provisioning-jobs",
                "/api/v1/provisioning-tasks",
                "/api/v1/provisioning-tasks/{taskId}/attempts",
                "/api/v1/reconciliation-runs")) {
            Set<String> refs = parameterRefs(document.path("paths").path(path).path("get"));
            assertThat(refs).contains("Cursor", "Limit");
        }
        JsonNode limit = document.path("components").path("parameters").path("Limit").path("schema");
        assertThat(limit.path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("maximum").asInt()).isEqualTo(200);
        assertThat(limit.path("default").asInt()).isEqualTo(50);
    }

    private static void assertRecordMatchesSchema(
            JsonNode document, String schemaName, Class<?> recordType) {
        Set<String> runtimeFields = Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        JsonNode schema = document.path("components").path("schemas").path(schemaName);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(propertyNames(schema)).isEqualTo(runtimeFields);
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
                .collect(Collectors.toSet());
    }

    private static String tail(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    private static void assertGet(String methodName, String path) {
        var method = Arrays.stream(IntegrationOperationalStatusController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private static JsonNode contract() throws Exception {
        try (InputStream stream = IntegrationOperationalOpenApiAlignmentTest.class
                .getResourceAsStream("/contracts/openapi/integration-operations-v1.json")) {
            assertThat(stream).isNotNull();
            return JSON.readTree(stream);
        }
    }
}
