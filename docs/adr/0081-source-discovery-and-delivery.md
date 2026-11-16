# ADR-0081 Source discovery and bounded Identity delivery

Status: Accepted

Integration schedules explicit FULL or INCREMENTAL reconciliation against a current active binding/configuration. FULL has no start checkpoint; INCREMENTAL requires a checkpoint and never implies destructive absence. The initial SCIM profile uses its positive startIndex checkpoint. Arbitrary provider filters are not a supported scope contract. The reconcile command is tenant-scoped, optimistic and causally idempotent, with dedicated permission and audit.

SOURCE_SYSTEM bindings use PRINCIPAL discovery and an existing same-tenant Identity SourceSystem, with one active binding per source required for delivery. Finalized current positive evidence creates durable source delivery. Each page invokes Identity-owned startImport/observe/completeImport semantic commands with a cursor in one local transaction. Integration never writes Identity tables, links or business state. Source absence remains UNTRUSTED even on COMPLETE; an authorized Identity policy must explicitly establish absence trust. Newer accepted scans and changed binding/configuration fence delivery; a started superseded import completes PARTIAL. Delivery is bounded, restartable and does not call the provider. Connector-native observations remain technical evidence, not governed Principal creation.
