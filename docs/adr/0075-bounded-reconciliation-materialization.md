# ADR-0075: Durable bounded reconciliation materialization

Status: Accepted

## Decision

Provider completion and technical materialization are distinct. Persist the exact completion fingerprint/lease epoch, reported outcome, verified coverage candidate and Integration-owned materialization phase/cursor. A run remains RUNNING with effective UNKNOWN until materialization finishes. Once completion is accepted no further discovery claims/append are allowed; exact completion replay remains valid. Process at most 200 staged positive observations (stable provider-ID keyset) and at most 200 unseen absences per transaction. Small runs may finish in the initial completion transaction; a scheduler resumes remaining work one bounded transaction at a time. Each step locks the run, then checks current binding/connector revisions and newer accepted generation under the binding lock. Stale work terminates with UNKNOWN and no further materialization. No provider call occurs during materialization. Grant absence uses exact principal/entitlement provider pair rather than mutable grant stable ID.

Positive observations are current technical knowledge as each bounded page commits; absence pages only start after all positives and verified COMPLETE coverage. Final outcome/completeness and downstream semantic fact are committed together. If a newer accepted generation or configuration change interrupts materialization, retain already committed technical evidence, stop further changes and publish the final UNKNOWN result. No authority is rewritten. Operational RUNNING includes materialization; completion acknowledgement is not a claim of materialization success.

## Consequences

V35 stores phase, cursor and pending outcome/coverage without inventing historical state. Existing small-run completion remains compatible; large-run readers must poll until finalized. Richer discovery scope and provider traversal semantics remain separate.
