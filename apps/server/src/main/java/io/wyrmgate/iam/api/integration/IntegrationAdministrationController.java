package io.wyrmgate.iam.api.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.WorkerPermissionSpec;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class IntegrationAdministrationController {

    private final IntegrationAdministrationRepository repository;
    private final IntegrationAdminApiMutationService mutations;
    private final IdGenerator ids;
    private final ObjectMapper json;

    public IntegrationAdministrationController(
            IntegrationAdministrationRepository repository,
            IntegrationAdminApiMutationService mutations,
            IdGenerator ids,
            ObjectMapper json) {
        this.repository = repository;
        this.mutations = mutations;
        this.ids = ids;
        this.json = json;
    }

    @PostMapping("/connectors")
    ResponseEntity<IntegrationAdminApiModels.ConnectorResource> createConnector(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody IntegrationAdminApiModels.ConnectorCreateRequest body,
            HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        var value=mutations.createConnector(
                actor,body.connectorType(),body.runtimeId(),body.runtimeVersion(),
                body.configurationVersion(),safeMap(body.configuration()),body.secretReference(),
                key(idempotencyKey,correlationId),fingerprint(body),Instant.now(),correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG,etag(value.revision()))
                .header(HttpHeaders.LOCATION,"/api/v1/connectors/"+value.id())
                .header("X-Correlation-Id",correlationId.toString())
                .body(connector(value));
    }

    @GetMapping("/connectors/{id}")
    ResponseEntity<IntegrationAdminApiModels.ConnectorResource> getConnector(
            @PathVariable UUID id,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor,AdministrativePermissions.CONNECTOR_READ,
                new AdministrativeResource("connector",id),Instant.now(),correlationId);
        var value=repository.findConnector(actor.tenant(),id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(connector(value),value.revision(),correlationId);
    }

    @PatchMapping("/connectors/{id}")
    ResponseEntity<IntegrationAdminApiModels.ConnectorResource> updateConnector(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody IntegrationAdminApiModels.ConnectorUpdateRequest body,
            HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        long revision=revision(ifMatch,correlationId);
        var value=mutations.updateConnector(
                actor,id,body.runtimeId(),body.runtimeVersion(),body.configurationVersion(),
                safeMap(body.configuration()),body.secretReference(),revision,
                key(idempotencyKey,correlationId),fingerprint(List.of(id,revision,body)),
                Instant.now(),correlationId);
        return ok(connector(value),value.revision(),correlationId);
    }

    @PostMapping("/connectors/{id}:disable")
    ResponseEntity<IntegrationAdminApiModels.ConnectorResource> disableConnector(
            @PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        long revision=revision(ifMatch,correlationId);
        var value=mutations.disableConnector(
                actor,id,revision,key(idempotencyKey,correlationId),
                fingerprint(List.of(id,revision,"disable")),Instant.now(),correlationId);
        return ok(connector(value),value.revision(),correlationId);
    }

    @PostMapping("/connector-bindings")
    ResponseEntity<IntegrationAdminApiModels.BindingResource> createBinding(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody IntegrationAdminApiModels.BindingCreateRequest body,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        var value=mutations.createBinding(
                actor,body.connectorInstanceId(),body.targetKind(),body.targetId(),
                body.contractId(),body.contractVersion(),
                body.supportsCompletePrincipalDiscovery(),
                body.supportsCompleteEntitlementDiscovery(),
                body.supportsCompleteGrantDiscovery(),
                key(key,correlationId),fingerprint(body),Instant.now(),correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG,etag(value.revision()))
                .header(HttpHeaders.LOCATION,"/api/v1/connector-bindings/"+value.id())
                .header("X-Correlation-Id",correlationId.toString())
                .body(binding(value));
    }

    @GetMapping("/connector-bindings/{id}")
    ResponseEntity<IntegrationAdminApiModels.BindingResource> getBinding(
            @PathVariable UUID id,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor,AdministrativePermissions.CONNECTOR_BINDING_READ,
                new AdministrativeResource("connector-binding",id),Instant.now(),correlationId);
        var value=repository.findBinding(actor.tenant(),id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(binding(value),value.revision(),correlationId);
    }

    @PatchMapping("/connector-bindings/{id}")
    ResponseEntity<IntegrationAdminApiModels.BindingResource> updateBinding(
            @PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idem,
            @RequestBody IntegrationAdminApiModels.BindingUpdateRequest body,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        long revision=revision(ifMatch,correlationId);
        var value=mutations.updateBinding(actor,id,body.contractId(),body.contractVersion(),
                body.supportsCompletePrincipalDiscovery(),
                body.supportsCompleteEntitlementDiscovery(),
                body.supportsCompleteGrantDiscovery(),
                revision,key(idem,correlationId),
                fingerprint(List.of(id,revision,body)),Instant.now(),correlationId);
        return ok(binding(value),value.revision(),correlationId);
    }

    @PostMapping("/connector-bindings/{id}:disable")
    ResponseEntity<IntegrationAdminApiModels.BindingResource> disableBinding(
            @PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idem,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        long revision=revision(ifMatch,correlationId);
        var value=mutations.disableBinding(actor,id,revision,key(idem,correlationId),
                fingerprint(List.of(id,revision,"disable")),Instant.now(),correlationId);
        return ok(binding(value),value.revision(),correlationId);
    }

    @PostMapping("/connector-workers")
    ResponseEntity<IntegrationAdminApiModels.WorkerResource> createWorker(
            @RequestHeader("Idempotency-Key") String idem,
            @RequestBody IntegrationAdminApiModels.WorkerCreateRequest body,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        var value=mutations.createWorker(actor,
                new WorkerExternalSubject(body.issuer(),body.subject()),
                body.protocolMajorMin(),body.protocolMajorMax(),body.bindingScope(),
                permissions(body.permissions()),key(idem,correlationId),fingerprint(body),
                Instant.now(),correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG,etag(value.revision()))
                .header(HttpHeaders.LOCATION,"/api/v1/connector-workers/"+value.id())
                .header("X-Correlation-Id",correlationId.toString())
                .body(worker(value));
    }

    @GetMapping("/connector-workers/{id}")
    ResponseEntity<IntegrationAdminApiModels.WorkerResource> getWorker(
            @PathVariable UUID id,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor,AdministrativePermissions.CONNECTOR_WORKER_READ,
                new AdministrativeResource("connector-worker",id),Instant.now(),correlationId);
        var value=repository.findWorker(actor.tenant(),id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(worker(value),value.revision(),correlationId);
    }

    @PatchMapping("/connector-workers/{id}")
    ResponseEntity<IntegrationAdminApiModels.WorkerResource> updateWorker(
            @PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idem,
            @RequestBody IntegrationAdminApiModels.WorkerUpdateRequest body,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        long revision=revision(ifMatch,correlationId);
        var value=mutations.updateWorker(actor,id,body.protocolMajorMin(),body.protocolMajorMax(),
                body.bindingScope(),permissions(body.permissions()),revision,key(idem,correlationId),
                fingerprint(List.of(id,revision,body)),Instant.now(),correlationId);
        return ok(worker(value),value.revision(),correlationId);
    }

    @PostMapping("/connector-workers/{id}:disable")
    ResponseEntity<IntegrationAdminApiModels.WorkerResource> disableWorker(
            @PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idem,HttpServletRequest request) {
        UUID correlationId=IntegrationAdminApiRequestContext.resolve(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        long revision=revision(ifMatch,correlationId);
        var value=mutations.disableWorker(actor,id,revision,key(idem,correlationId),
                fingerprint(List.of(id,revision,"disable")),Instant.now(),correlationId);
        return ok(worker(value),value.revision(),correlationId);
    }

    private RequestFingerprint fingerprint(Object value) {
        try {
            return RequestFingerprint.sha256(json.writeValueAsBytes(value));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("request cannot be fingerprinted",invalid);
        }
    }

    private static List<WorkerPermissionSpec> permissions(
            List<IntegrationAdminApiModels.WorkerPermissionRequest> values) {
        if(values==null||values.isEmpty()) throw new IllegalArgumentException("permissions must not be empty");
        return values.stream().map(v -> new WorkerPermissionSpec(
                v.runtimeId(),v.runtimeVersion(),WorkerCapability.valueOf(v.capability()),
                v.contractId(),v.contractVersion())).toList();
    }

    private static Map<String,Object> safeMap(Map<String,Object> value) {
        return value==null?Map.of():value;
    }

    private static String key(String value,UUID correlationId) {
        if(value==null||value.isBlank()||value.length()<8||value.length()>200)
            throw IntegrationAdminApiException.validation(correlationId,
                    "Idempotency-Key must contain between 8 and 200 characters.");
        return value;
    }

    private static long revision(String value,UUID correlationId) {
        if(value==null||!value.matches("\\\"rev-[1-9][0-9]*\\\""))
            throw IntegrationAdminApiException.validation(correlationId,
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        try { return Long.parseLong(value.substring(5,value.length()-1)); }
        catch(NumberFormatException invalid) {
            throw IntegrationAdminApiException.validation(correlationId,"If-Match revision is invalid.");
        }
    }

    private static String etag(long revision) { return "\"rev-"+revision+"\""; }

    private static IntegrationAdminApiModels.ConnectorResource connector(
            IntegrationAdministrationRepository.ConnectorInstance v) {
        return new IntegrationAdminApiModels.ConnectorResource(
                v.id(),v.connectorType(),v.runtimeId(),v.runtimeVersion(),v.configurationVersion(),
                v.configuration(),v.secretConfigured(),v.lifecycleState(),v.revision(),v.createdAt(),v.updatedAt());
    }

    private static IntegrationAdminApiModels.BindingResource binding(
            IntegrationAdministrationRepository.ConnectorBinding v) {
        return new IntegrationAdminApiModels.BindingResource(
                v.id(),v.connectorInstanceId(),v.targetKind(),v.targetId(),v.contractId(),
                v.contractVersion(),
                v.supportsCompletePrincipalDiscovery(),
                v.supportsCompleteEntitlementDiscovery(),
                v.supportsCompleteGrantDiscovery(),
                v.lifecycleState(),
                v.revision(),v.createdAt(),v.updatedAt());
    }

    private static IntegrationAdminApiModels.WorkerResource worker(
            IntegrationAdministrationRepository.ConnectorWorker v) {
        return new IntegrationAdminApiModels.WorkerResource(
                v.id(),v.issuer(),v.subject(),v.state(),v.protocolMajorMin(),v.protocolMajorMax(),
                v.bindingScope(),v.permissions().stream().map(p -> new IntegrationAdminApiModels.WorkerPermissionRequest(
                        p.runtimeId(),p.runtimeVersion(),p.capability().name(),p.contractId(),p.contractVersion())).toList(),
                v.revision(),v.createdAt(),v.updatedAt());
    }

    private static <T> ResponseEntity<T> ok(T body,long revision,UUID correlationId) {
        return ResponseEntity.ok().header(HttpHeaders.ETAG,etag(revision))
                .header("X-Correlation-Id",correlationId.toString()).body(body);
    }
}
