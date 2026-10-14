# Identity golden-page implementation target

## Status

Implementation-facing Console UX note for issue #405. This document does **not** redefine Identity domain semantics, capability ownership, authorization, or provider behavior. `docs/api/identity-contracts.md`, accepted ADRs, current domain/security documentation, and the formal specification package remain authoritative.

## Collection target

`/identities` is the governed-subject discovery workbench. The approved visual target is the Identity golden-page mockup reviewed with #405 and uses the same dense enterprise-control-plane grammar as the IAM Operations overview:

- dark persistent labeled navigation rail on desktop and compact branded header/drawer on mobile;
- bounded main work canvas rather than an edge-to-edge sparse CRUD surface;
- `IDENTITY · GOVERNED SUBJECTS` context, strong page title, concise operator-facing purpose copy, Refresh and deliberate Create Identity action;
- one cohesive discovery strip with display-name search, exact type, exact lifecycle, exact-ID support lookup, and clear-filters action;
- a scan-efficient desktop table and a purpose-built compact mobile list;
- compact semantic lifecycle badges; and
- integrated cursor pagination.

Developer implementation commentary must not appear in the operator product. Unsupported controls must not be presented as working UI.

## Bounded discovery query

The collection discovery slice extends the existing authoritative Identity list read with three optional query filters while preserving collection authorization and `createdAt + id` cursor ordering:

- `q` — trimmed, case-insensitive display-name **prefix** search, maximum 300 characters;
- `type` — exact `PERSON`, `SERVICE`, or `WORKLOAD`;
- `lifecycle` — exact `PENDING`, `ACTIVE`, `SUSPENDED`, `INACTIVE`, or `DECOMMISSIONED`.

Filters compose with AND semantics. Filtering is query behavior only; it does not create a new aggregate, projection authority, or browser-side IAM semantics. Filtered cursors are signed, tenant-bound, time-bounded, and additionally bound to the normalized filter tuple so a cursor cannot be replayed against a different discovery query.

The unfiltered collection contract and existing unfiltered cursor form remain supported. The filtered mappings are a compatibility extension of `GET /api/v1/identities`; they do not change Identity ownership or mutation contracts.

## Responsive acceptance

Desktop keeps the governed-subject name and stable ID together as the primary row anchor, with type, lifecycle, update time, and action secondary. Mobile does not squeeze the desktop table; it renders each Identity as a compact row-card with name, type, truncated ID, lifecycle, update time, and direct-open affordance.

The application shell itself must remain reachable without horizontal page overflow, preserve keyboard focus visibility and mobile drawer behavior, and continue using the server-derived administrative-authority projection only for navigation noise reduction. Operation-time server authorization remains final.
