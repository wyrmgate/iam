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
    assert document.get("x-wyrmgate-contract-status") == "contract-first-not-runtime-exposed"
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
        "schema-defined-not-yet-wired-to-external-delivery"
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

    changed_payload = schemas["IdentityMetadataChangedPayload"]
    assert "value" not in json.dumps(changed_payload, sort_keys=True).lower(), (
        "metadata-changed event must signal changed fields without publishing PII values"
    )


def main() -> None:
    verify_openapi(load_json(OPENAPI))
    verify_asyncapi(load_json(ASYNCAPI))
    print("API contracts verified")


if __name__ == "__main__":
    main()
