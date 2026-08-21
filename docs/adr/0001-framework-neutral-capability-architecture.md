# ADR-0001: Framework-neutral capability architecture

Status: Accepted

## Context

IAM must evolve across framework, persistence, transport and deployment choices without redefining core business semantics.

## Decision

The canonical architecture is defined in logical capabilities: Identity, Catalog, Access, Governance, Credential, Integration, Administration, Audit, plus supporting Platform capabilities.

A logical capability is not a Maven module, package, process, service, database schema or framework component. Only the owning capability mutates its authoritative state. Cross-capability collaboration uses semantic contracts rather than repository/entity sharing.

The initial runtime may be a modular monolith. Physical extraction is justified only by scaling, security, operational or team value while preserving data ownership and semantic contracts.

## Consequences

- Framework/runtime changes do not alter domain semantics.
- Cross-capability repository mutation is prohibited.
- Module/deployment topology remains an implementation mapping.
- Ports are semantic capability boundaries, not abstraction for every domain function.
