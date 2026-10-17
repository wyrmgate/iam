# Tenant Management Console presentation contract

## Status and scope

This document defines the implementation-facing Console presentation for Tenant Management. It is a user-interface and composition contract only. It does not redefine Tenant ownership, Administration authority, Platform Owner authority, capability ownership, API semantics, Tenant lifecycle, or cross-Tenant access.

ADR-0049 and `docs/architecture/platform-authority-and-application-tenancy.md` remain authoritative for Platform Owner versus Tenant Owner semantics and Tenant isolation.

## Product model

The Console presents **Tenant Management** as one coherent product concept with two different security contexts:

- `/tenant` is the server-resolved current Tenant management surface for the authenticated actor;
- `/platform/tenants/:tenantId` is a Platform Owner deployment-inspection surface for one selected Tenant resource.

The shared visual vocabulary does not merge those authority contexts. Opening a Platform Tenant detail does not switch authenticated Tenant context, create tenant-scoped authority, impersonate a tenant actor, or make the selected route identifier authoritative security context.

## Current Tenant management

`/tenant` renders canonical Tenant metadata and bounded tenant-scoped Console read projections. The current Tenant comes from the server-derived administrative context; the browser does not choose it.

Canonical Tenant metadata remains limited to:

- `id`;
- `displayName`;
- `revision`;
- `createdAt`;
- `updatedAt`.

The page must not invent Tenant status/lifecycle, code, description, subscription, domain ownership, or mutation operations until those semantics are separately accepted and implemented.

### Authority-aware destinations

Tenant Management navigation is derived from the existing server-projected `CurrentAdministrativeAuthority` and the shared Console route registry. The page may expose implemented Tenant-relevant destinations such as Identity, Principal, Catalog, Access, Governance work, Credential, Integration, Administration, and Audit only when the same route-visibility projection makes them available.

Route visibility is a convenience optimization only. Operation-time server authorization remains final for every read and command. Hiding a destination does not grant or revoke authority, and showing a destination never guarantees that every resource or operation inside it is authorized.

Unsupported pseudo-destinations are not rendered as disabled tabs. In particular, Organization management and Tenant Settings remain absent until a real Console/API contract exists.

## Platform Tenant inspection

`/platform/tenants/:tenantId` uses the same restrained Tenant object-detail vocabulary so operators recognize that they are inspecting a Tenant. The route remains deployment-scoped and read-only.

Every detail request independently requires current viable `PLATFORM_OWNER` authority. The selected Tenant ID is only the requested deployment resource. It does not become current Tenant context and does not grant tenant-scoped Administration authority.

Platform inspection therefore exposes only canonical Tenant metadata. Tenant-owned Identity, Principal, Access, Governance, Credential, Integration, Administration, and Audit workspaces are not linked from this inspection mode unless a future accepted contract explicitly defines cross-Tenant semantic queries and authorization for those owning capabilities.

## Responsive behavior

The page follows `docs/engineering/console-enterprise-ui-standard.md`.

### Desktop and tablet

- Tenant identity/context appears in a restrained object header.
- Tenant Management destinations use a horizontal section rail when space allows.
- Summary metrics use bounded responsive grids rather than a fixed six-column layout at intermediate widths.
- Canonical Tenant metadata and explicit Administration/operations signals use definition and bounded-surface layouts.
- High-information tables remain tables with bounded overflow.

### Mobile

- The object header reduces nonessential metadata while retaining Tenant identity.
- Tenant area navigation becomes one labeled mobile selector rather than a horizontally scrolling strip.
- Summary tiles stack to preserve readable labels and values.
- Recent Audit activity and Identity Source summaries use compact record presentations that retain the same core semantic information; the desktop table remains the desktop/tablet presentation.
- Controls respect the shared minimum mobile target.
- No authority-relevant distinction is removed solely to fit the viewport.

## Governed data and visualization

Tenant Management may visualize only exact governed server projections. Identity-type and Application-origin distributions may use the shared `DonutChart` because the server exposes bounded categorical breakdowns.

Per-application Access aggregation remains intentionally absent. `AccessAssignment` remains authoritative access intent and `EffectiveAccess` remains a projection; the Console must not infer application totals from truncated Catalog or entitlement data.

Operational and owner-continuity signals are shown individually and are not collapsed into a synthetic Tenant health score.

## Non-goals

This presentation change does not add:

- Tenant create/edit/delete or lifecycle operations;
- browser-controlled Tenant switching;
- generic `SUPER_ADMIN` authority;
- Platform Owner inheritance of tenant-scoped authority;
- cross-Tenant Identity/Access/Governance/Credential/Integration/Audit access;
- impersonation;
- Organization management;
- Tenant Settings;
- unsupported search, total-count, or trend semantics.
