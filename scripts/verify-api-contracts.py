#!/usr/bin/env python3
"""Lightweight repository contract checks for Wyrmgate OpenAPI/AsyncAPI artifacts.

This intentionally uses only the Python standard library so CI can validate checked-in
contracts without introducing a second package manager or generated dependency tree.
It verifies Wyrmgate-specific invariants in addition to JSON syntax; full standards
linting can be added later when a repository-wide contract toolchain is selected.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "apps/server/src/main/resources/contracts/openapi/identity-v1.json"
ASYNCAPI = ROOT / "apps/server/src/main/resources/contracts/asyncapi/identity-events-v1.json"
CONNECTOR_WORKER_OPENAPI = ROOT / "apps/server/src/main/resources/contracts/openapi/connector-worker-v1.json"
INTEGRATION_ADMIN_OPENAPI = ROOT / "apps/server/src/main/resources/contracts/openapi/integration-admin-v1.json"


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} must contain a JSON object")
    return value


def parameters(operation: dict) -> list[dict]:
    result: list[dict] = []
    for parameter in operation.get("parameters", []):
        if isinstance(parameter, dict):
            result.append(parameter)
    return result


def ref_names(operation: dict) -> set[str]:
    names: set[str] = set()
    for parameter in parameters(operation):
        ref = parameter.get("$ref")
        if isinstance(ref, str):
            names.add(ref.rsplit("/", 1)[-1])
        elif isinstance(parameter.get("name"), str):
            names.add(parameter["name"])
    return names


def verify_openapi(document: dict) -> None:
    version = document.get("openapi")
    assert isinstance(version, str) and version.startswith("3.1."), (
        "Identity OpenAPI must stay on the approved 3.1.x toolchain for this slice"
    )
    assert document.get("x-wyrmgate-contract-status") == "runtime-exposed-authenticated-authorized"
    assert document.get("security"), "public contract must declare transport authentication"

    paths = document.get("paths")
    assert isinstance(paths, dict)
    expected_paths = {
        "/identities",
        "/identities/{identityId}",
        "/identities/{identityId}/canonical-attributes",
    }
    assert expected_paths.issubset(paths), "required Identity semantic resources are missing"

    list_operation = paths["/identities"]["get"]
    list_params = ref_names(list_operation)
    assert {"Cursor", "Limit"}.issubset(list_params), "Identity list must use cursor pagination"

    create_operation = paths["/identities"]["post"]
    assert "IdempotencyKey" in ref_names(create_operation), "create must require causal idempotency"
    assert create_operation.get("x-wyrmgate-administrative-permission") == "identity:create"

    update_operation = paths["/identities/{identityId}"]["patch"]
    update_params = ref_names(update_operation)
    assert {"IfMatch", "IdempotencyKey"}.issubset(update_params), (
        "authoritative update must require revision concurrency and causal idempotency"
    )
    update_schema = (
        update_operation["requestBody"]["content"]["application/json"]["schema"]["$ref"]
    )
    assert update_schema.endswith("/UpdateIdentityMetadataRequest")

    serialized = json.dumps(document, sort_keys=True).lower()
    for forbidden in (
        "password",
        "privatekey",
        "private_key",
        "refreshtoken",
        "refresh_token",
        "secretvalue",
        "secret_value",
    ):
        assert forbidden not in serialized, f"secret-shaped field leaked into OpenAPI: {forbidden}"

    schemas = document["components"]["schemas"]
    metadata_properties = schemas["UpdateIdentityMetadataRequest"]["properties"]
    assert set(metadata_properties) == {"displayName"}, (
        "lifecycle/status changes must not be smuggled through generic PATCH"
    )
    assert "CanonicalAttributeView" in schemas
    canonical = json.dumps(schemas["CanonicalAttributeView"], sort_keys=True)
    for persistence_name in ("candidateId", "sourcePayload", "rawPayload"):
        assert persistence_name not in canonical, (
            "canonical attribute API must not expose persistence/observation internals"
        )


def verify_asyncapi(document: dict) -> None:
    assert document.get("asyncapi") == "3.1.0"
    assert document.get("x-wyrmgate-publication-status") == (
        "runtime-signed-webhook-adapter-implemented-activation-required"
    )
    assert document.get("x-wyrmgate-transport") == (
        "semantic contract is transport-neutral; ADR-0013 runtime adapter is one signed HTTPS webhook destination"
    )

    operations = document.get("operations")
    assert isinstance(operations, dict) and operations
    for name, operation in operations.items():
        assert operation.get("action") == "send", f"{name} must describe Wyrmgate-emitted facts"

    schemas = document["components"]["schemas"]
    for envelope_name in ("IdentityCreatedEnvelope", "IdentityMetadataChangedEnvelope"):
        envelope = schemas[envelope_name]
        required = set(envelope.get("required", []))
        expected = {
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "tenantId",
            "resource",
            "correlationId",
            "payload",
        }
        assert expected.issubset(required), f"{envelope_name} is missing causal/event envelope fields"

    serialized = json.dumps(document, sort_keys=True).lower()
    for forbidden in (
        "password",
        "privatekey",
        "private_key",
        "refreshtoken",
        "refresh_token",
        "secretvalue",
        "secret_value",
        "sourcerecordid",
        "mappingversionid",
        "authorityruleversionid",
    ):
        assert forbidden not in serialized, f"public event contract leaked sensitive/internal field: {forbidden}"

    expected_channels = {
        "identityCreatedV1": "iam.identity.created.v1",
        "identityMetadataChangedV1": "iam.identity.metadata-changed.v1",
    }
    channels = document.get("channels", {})
    for channel_name, address in expected_channels.items():
        assert channels[channel_name].get("address") == address, (
            f"{channel_name} must preserve its exact v1 public address"
        )

    for schema_name in (
        "IdentityCreatedPayload",
        "IdentityMetadataChangedPayload",
        "IdentityCreatedEnvelope",
        "IdentityMetadataChangedEnvelope",
        "ResourceReference",
    ):
        assert schemas[schema_name].get("additionalProperties") is False, (
            f"{schema_name} must remain closed under the ADR-0013 exact-version policy"
        )

    changed_payload = schemas["IdentityMetadataChangedPayload"]
    changed_properties = changed_payload.get("properties", {})
    assert set(changed_properties) == {"changedFields"}, (
        "metadata-changed event must signal changed fields without publishing PII values"
    )
    changed_field_items = changed_properties["changedFields"]["items"]
    assert changed_field_items.get("enum") == ["displayName"], (
        "metadata-changed event must expose only the semantic field name in this slice"
    )



def verify_connector_worker_openapi(document: dict) -> None:
    version = document.get("openapi")
    assert isinstance(version, str) and version.startswith("3.1."), (
        "connector-worker OpenAPI must stay on the approved 3.1.x contract family"
    )
    assert document.get("x-wyrmgate-contract-status") == (
        "runtime-exposed-dedicated-bearer-server-authorized"
    )
    assert document.get("x-wyrmgate-protocol-major") == 1
    session_response = document["components"]["schemas"]["SessionResponse"]
    assert "acceptedRuntimes" in set(session_response.get("required", [])), (
        "session negotiation must return the server-accepted runtime/schema compatibility set"
    )
    assert document.get("security") == [{"workerBearer": []}], (
        "all connector-worker operations must require the dedicated worker bearer boundary"
    )

    paths = document.get("paths", {})
    expected_paths = {
        "/sessions",
        "/sessions/{sessionId}/work:claim",
        "/sessions/{sessionId}/work/{workId}/lease:renew",
        "/sessions/{sessionId}/work/{workId}/observations",
        "/sessions/{sessionId}/work/{workId}:complete",
    }
    assert expected_paths == set(paths), (
        "OD-004 v1 must expose only the accepted session/claim/renew/observe/complete operations"
    )

    schemas = document["components"]["schemas"]
    for schema_name in (
        "SessionRequest",
        "ConnectorRuntimeAdvertisement",
        "ContractSchemaSupport",
        "SessionResponse",
        "ClaimWorkRequest",
        "ClaimWorkResponse",
        "LeasedWorkItem",
        "LeaseToken",
        "ObservationBatch",
        "ConnectorObservation",
        "WorkCompletion",
        "NormalizedWorkResult",
    ):
        assert schemas[schema_name].get("additionalProperties") is False, (
            f"{schema_name} must remain a closed OD-004 v1 core schema"
        )

    leased_required = set(schemas["LeasedWorkItem"]["required"])
    assert {
        "workId",
        "operationId",
        "lease",
        "tenantId",
        "connectorBindingId",
        "workKind",
        "contractId",
        "contractVersion",
        "idempotencyKey",
        "correlationId",
        "payload",
    }.issubset(leased_required), "leased work must preserve causal, tenant, binding, fencing and schema context"

    lease_required = set(schemas["LeaseToken"]["required"])
    assert {"leaseId", "leaseEpoch", "leaseExpiresAt"} == lease_required, (
        "OD-004 fencing requires leaseId + monotonically increasing epoch + expiry"
    )

    completion = schemas["WorkCompletion"]
    assert set(completion["properties"]["outcome"]["enum"]) == {
        "SUCCEEDED",
        "FAILED_RETRYABLE",
        "FAILED_FINAL",
        "SUPERSEDED",
        "SKIPPED",
    }
    assert "discoveryCoverage" in completion["properties"], (
        "remote discovery must report coverage evidence explicitly"
    )
    assert document.get("x-wyrmgate-completeness-authority") == (
        "worker-reported discovery coverage is evidence only; Integration owns effective reconciliation/import completeness"
    )

    property_names: set[str] = set()

    def collect_property_names(value: object) -> None:
        if isinstance(value, dict):
            properties = value.get("properties")
            if isinstance(properties, dict):
                property_names.update(str(name).lower() for name in properties)
            for child in value.values():
                collect_property_names(child)
        elif isinstance(value, list):
            for child in value:
                collect_property_names(child)

    collect_property_names(schemas)
    for forbidden in (
        "password",
        "privatekey",
        "private_key",
        "refreshtoken",
        "refresh_token",
        "secretvalue",
        "secret_value",
        "clientsecret",
        "client_secret",
    ):
        assert forbidden not in property_names, (
            f"connector-worker ordinary wire contract leaked secret-shaped field: {forbidden}"
        )



def verify_integration_admin_openapi(document: dict) -> None:
    assert document.get("openapi", "").startswith("3.1.")
    expected_paths = {
        "/api/v1/connectors",
        "/api/v1/connectors/{id}",
        "/api/v1/connectors/{id}:disable",
        "/api/v1/connector-bindings",
        "/api/v1/connector-bindings/{id}",
        "/api/v1/connector-bindings/{id}:disable",
        "/api/v1/connector-workers",
        "/api/v1/connector-workers/{id}",
        "/api/v1/connector-workers/{id}:disable",
    }
    assert set(document.get("paths", {})) == expected_paths
    assert document.get("security") == [{"bearerAuth": []}]
    assert document.get("x-wyrmgate-idempotency") == "all mutations require Idempotency-Key"
    assert document.get("x-wyrmgate-concurrency") == (
        "updates and disable operations require strong revision If-Match"
    )
    schemas = document["components"]["schemas"]
    assert "secretReference" not in schemas["ConnectorResource"]["properties"]
    secret_reference = schemas["ConnectorCreateRequest"]["properties"]["secretReference"]
    assert secret_reference.get("writeOnly") is True
    for name in ("ConnectorResource", "ConnectorCreateRequest",
                 "ConnectorBindingResource", "ConnectorWorkerResource", "WorkerPermission"):
        assert schemas[name].get("additionalProperties") is False

def main() -> None:
    verify_openapi(load_json(OPENAPI))
    verify_asyncapi(load_json(ASYNCAPI))
    verify_connector_worker_openapi(load_json(CONNECTOR_WORKER_OPENAPI))
    verify_integration_admin_openapi(load_json(INTEGRATION_ADMIN_OPENAPI))
    print("API contracts verified")


if __name__ == "__main__":
    main()
