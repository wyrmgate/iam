# Tenant Management Console presentation contract

## Status and scope

This document defines the implementation-facing Console presentation for Tenant Management. It is a user-interface and composition contract only. It does not redefine Tenant ownership, Administration authority, Platform Owner authority, capability ownership, API semantics, Tenant lifecycle, or cross-Tenant access.

ADR-0049 and `docs/architecture/platform-authority-and-application-tenancy.md` remain authoritative for Platform Owner versus Tenant Owner semantics and Tenant isolation.

## Product model

The Console presents Tenant information through two visually related but security-distinct contexts:

- `/tenant` is the server-resolved current Tenant management surface for the authenticated actor;
- `/platform/tenants/:tenantId` is a Platform Owner deployment-inspection surface for one selected Tenant resource.

Shared visual vocabulary does not merge those authority contexts. Opening a Platform Tenant detail does not switch authenticated Tenant context, create tenant-scoped authority, impersonate a Tenant actor, or make the selected route identifier authoritative security context.

## Current Tenant Management

`/tenant` renders canonical Tenant metadata and bounded tenant-scoped Console read projections. The current Tenant comes from server-derived context; the browser does not choose it.

Canonical Tenant metadata remains limited to:

- `id`;
- `displayName`;
- `revision`;
- `createdAt`;
- `updatedAt`.

The page must not invent Tenant status/lifecycle, code, description, subscription, domain ownership, mutation operations, synthetic health/risk scores, trends, or approximate cross-capability metrics.

### Information hierarchy

Tenant Management follows the shared Enterprise UI Standard and scans in this order:

1. shared current-Tenant object/context header;
2. compact related-workspace orientation where useful;
3. governed operational summary;
4. canonical Tenant information;
5. administrative and Tenant Owner continuity;
6. governance and operational attention;
7. exact bounded governed distributions;
8. recent Audit activity and Identity source information.

The page should prefer fewer, denser shared surfaces over a wall of one-value cards. Scalar values that belong to the same operating summary are grouped within one surface rather than each receiving decorative card chrome.

### Navigation

The global `ShellChrome` remains the primary Console navigation. Tenant Management must not reproduce the full application information architecture as a second page-local tab or section system.

A compact related-workspace orientation row may expose a small subset of implemented Tenant-relevant destinations such as Identity, Access, Governance, Integration, and Administration. Visibility is derived from the same server-projected `CurrentAdministrativeAuthority` navigation hints and shared route registry used by the shell.

Route visibility is a convenience optimization only. Operation-time server authorization remains final for every read and command. Hiding a destination does not grant or revoke authority, and showing a destination never guarantees that every resource or operation inside it is authorized.

Unsupported pseudo-destinations are not rendered. Organization management, Tenant Settings, Tenant switching, impersonation, and generic Tenant Actions remain absent until explicit canonical contracts exist.

## Governed summary and attention

The operational summary may show only current bounded projections already exposed by the server, such as active governed Identities, active Catalog Applications, active AccessAssignments, configured Identity sources, and active Connector instances.

Administrative continuity remains separate from governance and operational attention:

- Tenant Owner continuity is derived from the Administration-owned owner projection;
- current administrative eligibility is server-derived;
- pending approvals/reviews remain Governance work signals;
- provisioning and reconciliation failures remain Integration operational signals.

These values are not collapsed into a synthetic Tenant health score. `AccessAssignment` remains authoritative access intent. `EffectiveAccess` remains a derived projection. Per-application access totals remain absent until an exact bounded semantic aggregation contract exists.

## Governed visualization

Charts are permitted only for exact bounded governed distributions. The current Identity-type and Application-origin distributions may use the shared `DonutChart` because the server exposes their categorical breakdowns directly.

Charts must keep visible textual values and labels and must not introduce inferred trends, risk scores, approximated totals, or provider observations presented as IAM authority.

## Recent activity and source information

Recent activity is Audit-owned evidence/activity and is not presented as an exhaustive cross-capability history unless the server contract says so.

Identity sources remain Identity-owned SourceSystems. Integration connector/binding/reconciliation information is operational posture and must remain visibly distinct from authoritative source identity.

Activity and source rows remain genuine tables when column relationships matter. On narrow screens they stay inside the shared `DataTable` horizontal-overflow container instead of forcing page-level horizontal scroll or automatically converting every row into a decorative card.

## Responsive behavior

The page follows `docs/engineering/console-enterprise-ui-standard.md`.

### Desktop

- use the shared wide page bound and shared header/surface grid;
- group related scalar values in one governed summary surface rather than standalone cards;
- use two-column composition only where paired information benefits scanning;
- keep canonical metadata in definition-style rows;
- keep high-information data tabular;
- align section titles, actions, state indicators, and metadata with shared primitives.

### Tablet

- preserve the enterprise information hierarchy rather than treating tablet as large mobile;
- collapse multi-column regions deliberately while retaining information;
- allow tables to scroll inside their own containers;
- reduce local chrome before removing authority-relevant content.

### Mobile

- no avoidable page-level horizontal scroll;
- shared controls retain the approximately 44px effective target baseline;
- Tenant context and important state remain visible;
- long technical identifiers wrap safely;
- related-workspace links wrap rather than becoming a second navigation drawer or selector;
- relational tables use contained horizontal scrolling where their column relationships matter;
- charts retain visible textual values;
- loading, empty, informational, and error states remain usable through shared `StatePanel` behavior.

## Platform Tenant inspection

`/platform/tenants/:tenantId` uses the same shared object-detail vocabulary as other Console detail surfaces so operators recognize the Tenant resource, but its information architecture must make the security context unmistakable.

The header identifies **Platform · Tenant inspection** and the surface is explicitly read-only and deployment-scoped. A visible inspection-boundary state explains that the route Tenant identifier is only the requested Platform resource and is not authenticated current-Tenant context.

Every detail request independently requires current viable `PLATFORM_OWNER` authority. Platform inspection exposes only canonical Tenant metadata. Tenant-owned Identity, Principal, Access, Governance, Credential, Integration, Administration, and Audit workspaces are not linked from this route unless a future accepted contract explicitly defines cross-Tenant semantic queries and authorization for those owning capabilities.

The page uses the same shared `PageHeader`, `StatePanel`, `Surface`, definition-row rhythm, responsive actions, and technical-ID wrapping as the rest of the Console rather than maintaining separate desktop/mobile header implementations.

## Non-goals

This presentation contract does not add:

- Tenant create/edit/delete or lifecycle operations;
- browser-controlled Tenant switching;
- generic `SUPER_ADMIN` authority;
- Platform Owner inheritance of tenant-scoped authority;
- cross-Tenant Identity/Access/Governance/Credential/Integration/Audit access;
- impersonation;
- Organization management;
- Tenant Settings;
- unsupported search, total-count, trend, health, risk, or subscription semantics;
- approximate per-application Access aggregation.
