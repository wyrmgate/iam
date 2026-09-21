package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorBinding;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorInstance;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorWorker;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.WorkerPermissionSpec;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository.EntitlementObservationMapping;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class IntegrationAdminApiMutationService {

    private final AdministrativeAuthorizationService authorization;
    private final IntegrationAdministrationCommandService commands;
    private final IntegrationAdministrationRepository repository;
    private final IntegrationEntitlementMappingService mappingCommands;
    private final IntegrationEntitlementMappingRepository mappings;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    IntegrationAdminApiMutationService(
            AdministrativeAuthorizationService authorization,
            IntegrationAdministrationCommandService commands,
            IntegrationAdministrationRepository repository,
            IntegrationEntitlementMappingService mappingCommands,
            IntegrationEntitlementMappingRepository mappings,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = authorization;
        this.commands = commands;
        this.repository = repository;
        this.mappingCommands = mappingCommands;
        this.mappings = mappings;
        this.idempotency = idempotency;
        this.transactions = transactions;
    }

    ConnectorInstance createConnector(
            AuthenticatedAdministrativeActor actor,
            String connectorType, String runtimeId, String runtimeVersion,
            long configurationVersion, Map<String,Object> configuration, String secretReference,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return transactions.required(() -> {
            require(actor, AdministrativePermissions.CONNECTOR_CREATE,
                    AdministrativeResource.collection("connector"), now, correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector.create.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayConnector(actor,r,correlationId);
            ConnectorInstance created=commands.createConnector(
                    actor.tenant(),connectorType,runtimeId,runtimeVersion,configurationVersion,
                    configuration,secretReference,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector.create.v1",key,fingerprint,
                    "connector",created.id(),now);
            return created;
        });
    }

    ConnectorInstance updateConnector(
            AuthenticatedAdministrativeActor actor, UUID id,
            String runtimeId,String runtimeVersion,long configurationVersion,
            Map<String,Object> configuration,String secretReference,long expectedRevision,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_UPDATE,
                    new AdministrativeResource("connector",id),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector.update.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayConnector(actor,r,correlationId);
            ConnectorInstance updated=commands.updateConnector(actor.tenant(),id,runtimeId,runtimeVersion,
                    configurationVersion,configuration,secretReference,expectedRevision,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector.update.v1",key,fingerprint,
                    "connector",updated.id(),now);
            return updated;
        });
    }

    ConnectorInstance disableConnector(
            AuthenticatedAdministrativeActor actor, UUID id,long expectedRevision,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_DISABLE,
                    new AdministrativeResource("connector",id),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector.disable.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayConnector(actor,r,correlationId);
            ConnectorInstance value=commands.disableConnector(actor.tenant(),id,expectedRevision,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector.disable.v1",key,fingerprint,
                    "connector",value.id(),now);
            return value;
        });
    }

    ConnectorBinding createBinding(
            AuthenticatedAdministrativeActor actor, UUID connectorId,String targetKind,UUID targetId,
            String contractId,int contractVersion,
            boolean completePrincipal,boolean completeEntitlement,boolean completeGrant,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_BINDING_CREATE,
                    AdministrativeResource.collection("connector-binding"),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector-binding.create.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayBinding(actor,r,correlationId);
            ConnectorBinding value=commands.createBinding(actor.tenant(),connectorId,targetKind,targetId,
                    contractId,contractVersion,
                    completePrincipal,completeEntitlement,completeGrant,
                    now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector-binding.create.v1",key,fingerprint,
                    "connector-binding",value.id(),now);
            return value;
        });
    }

    ConnectorBinding updateBinding(
            AuthenticatedAdministrativeActor actor,UUID id,String contractId,int contractVersion,
            boolean completePrincipal,boolean completeEntitlement,boolean completeGrant,
            long expectedRevision,String key,RequestFingerprint fingerprint,
            Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_BINDING_UPDATE,
                    new AdministrativeResource("connector-binding",id),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector-binding.update.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayBinding(actor,r,correlationId);
            ConnectorBinding value=commands.updateBinding(actor.tenant(),id,contractId,contractVersion,
                    completePrincipal,completeEntitlement,completeGrant,
                    expectedRevision,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector-binding.update.v1",key,fingerprint,
                    "connector-binding",value.id(),now);
            return value;
        });
    }

    ConnectorBinding disableBinding(
            AuthenticatedAdministrativeActor actor,UUID id,long expectedRevision,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_BINDING_DISABLE,
                    new AdministrativeResource("connector-binding",id),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector-binding.disable.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayBinding(actor,r,correlationId);
            ConnectorBinding value=commands.disableBinding(actor.tenant(),id,expectedRevision,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector-binding.disable.v1",key,fingerprint,
                    "connector-binding",value.id(),now);
            return value;
        });
    }

    EntitlementObservationMapping createEntitlementMapping(
            AuthenticatedAdministrativeActor actor,
            UUID connectorBindingId,
            String providerStableId,
            UUID entitlementId,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(actor, AdministrativePermissions.ENTITLEMENT_OBSERVATION_MAPPING_CREATE,
                    AdministrativeResource.collection("entitlement-observation-mapping"),
                    now, correlationId);
            var r = idempotency.register(
                    actor.tenant(),
                    "api.entitlement-observation-mapping.create.v1",
                    key, fingerprint, now, null);
            if (r.kind() == RegistrationKind.REPLAY) {
                return replayMapping(actor, r, correlationId);
            }
            EntitlementObservationMapping value = mappingCommands.map(
                    actor.tenant(), connectorBindingId, providerStableId,
                    entitlementId, now, correlationId);
            idempotency.complete(
                    actor.tenant(),
                    "api.entitlement-observation-mapping.create.v1",
                    key, fingerprint,
                    "entitlement-observation-mapping", value.id(), now);
            return value;
        });
    }

    EntitlementObservationMapping unmapEntitlement(
            AuthenticatedAdministrativeActor actor,
            UUID mappingId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(actor, AdministrativePermissions.ENTITLEMENT_OBSERVATION_MAPPING_RETIRE,
                    new AdministrativeResource("entitlement-observation-mapping", mappingId),
                    now, correlationId);
            var r = idempotency.register(
                    actor.tenant(),
                    "api.entitlement-observation-mapping.retire.v1",
                    key, fingerprint, now, null);
            if (r.kind() == RegistrationKind.REPLAY) {
                return replayMapping(actor, r, correlationId);
            }
            EntitlementObservationMapping value = mappingCommands.unmap(
                    actor.tenant(), mappingId, expectedRevision, now, correlationId);
            idempotency.complete(
                    actor.tenant(),
                    "api.entitlement-observation-mapping.retire.v1",
                    key, fingerprint,
                    "entitlement-observation-mapping", value.id(), now);
            return value;
        });
    }

    ConnectorWorker createWorker(
            AuthenticatedAdministrativeActor actor, WorkerExternalSubject subject,int min,int max,
            List<UUID> scope,List<WorkerPermissionSpec> permissions,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_WORKER_CREATE,
                    AdministrativeResource.collection("connector-worker"),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector-worker.create.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayWorker(actor,r,correlationId);
            ConnectorWorker value=commands.createWorker(actor.tenant(),subject,min,max,scope,permissions,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector-worker.create.v1",key,fingerprint,
                    "connector-worker",value.id(),now);
            return value;
        });
    }

    ConnectorWorker updateWorker(
            AuthenticatedAdministrativeActor actor,UUID id,int min,int max,List<UUID> scope,
            List<WorkerPermissionSpec> permissions,long expectedRevision,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_WORKER_UPDATE,
                    new AdministrativeResource("connector-worker",id),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector-worker.update.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayWorker(actor,r,correlationId);
            ConnectorWorker value=commands.updateWorker(actor.tenant(),id,min,max,scope,permissions,
                    expectedRevision,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector-worker.update.v1",key,fingerprint,
                    "connector-worker",value.id(),now);
            return value;
        });
    }

    ConnectorWorker disableWorker(
            AuthenticatedAdministrativeActor actor,UUID id,long expectedRevision,
            String key,RequestFingerprint fingerprint,Instant now,UUID correlationId) {
        return transactions.required(() -> {
            require(actor,AdministrativePermissions.CONNECTOR_WORKER_DISABLE,
                    new AdministrativeResource("connector-worker",id),now,correlationId);
            var r=idempotency.register(actor.tenant(),"api.connector-worker.disable.v1",key,fingerprint,now,null);
            if(r.kind()==RegistrationKind.REPLAY) return replayWorker(actor,r,correlationId);
            ConnectorWorker value=commands.disableWorker(actor.tenant(),id,expectedRevision,now,correlationId);
            idempotency.complete(actor.tenant(),"api.connector-worker.disable.v1",key,fingerprint,
                    "connector-worker",value.id(),now);
            return value;
        });
    }

    void requireRead(AuthenticatedAdministrativeActor actor, AdministrativePermission permission,
            AdministrativeResource resource, Instant now, UUID correlationId) {
        require(actor,permission,resource,now,correlationId);
    }

    private void require(AuthenticatedAdministrativeActor actor, AdministrativePermission permission,
            AdministrativeResource resource, Instant now, UUID correlationId) {
        if(!authorization.authorize(actor,permission,resource,now).allowed())
            throw IntegrationAdminApiException.forbidden(correlationId);
    }

    private ConnectorInstance replayConnector(AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration r,UUID correlationId) {
        requireCompleted(r,"connector",correlationId);
        return repository.findConnector(actor.tenant(),r.resourceId())
                .orElseThrow(() -> new IllegalStateException("idempotent connector result no longer exists"));
    }

    private ConnectorBinding replayBinding(AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration r,UUID correlationId) {
        requireCompleted(r,"connector-binding",correlationId);
        return repository.findBinding(actor.tenant(),r.resourceId())
                .orElseThrow(() -> new IllegalStateException("idempotent binding result no longer exists"));
    }

    private EntitlementObservationMapping replayMapping(
            AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration r,
            UUID correlationId) {
        requireCompleted(r, "entitlement-observation-mapping", correlationId);
        return mappings.find(actor.tenant(), r.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent mapping result no longer exists"));
    }

    private ConnectorWorker replayWorker(AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration r,UUID correlationId) {
        requireCompleted(r,"connector-worker",correlationId);
        return repository.findWorker(actor.tenant(),r.resourceId())
                .orElseThrow(() -> new IllegalStateException("idempotent worker result no longer exists"));
    }

    private static void requireCompleted(JdbcIdempotencyRepository.Registration r,String type,UUID correlationId) {
        if(!"COMPLETED".equals(r.operationState()))
            throw IntegrationAdminApiException.conflict(correlationId,"idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        if(!type.equals(r.resourceType())||r.resourceId()==null)
            throw new IllegalStateException("completed idempotency result type mismatch");
    }
}
