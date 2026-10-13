import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const catalog = await readConsoleSource('workspaces/CatalogWorkspace.tsx');

test('Catalog workspace makes Application hierarchy and governed parent scope explicit', () => {
  assertSourceContains(
    catalog,
    /Select an Application in Targets or Entitlements/,
    /Application Targets/,
    /belonging to exactly one governed Application/,
    /An Entitlement may optionally be scoped to a compatible ApplicationTarget/,
    /Known \$\{config\.label\} ID/,
    /server authorization still decides access/,
    /apiRequest<CatalogPage<CatalogParent>>\(withCursor\(config\.path/,
    /Load more \$\{config\.plural\}/,
  );
});

test('Catalog workspace preserves Business and Application Role semantics', () => {
  assertSourceContains(
    catalog,
    /BUSINESS Roles are organizational access packages that may span applications/,
    /APPLICATION Roles are bound to one Application/,
    /BUSINESS may span applications; APPLICATION belongs to exactly one Application/,
    /Required by the server for APPLICATION Roles/,
    /Role type, Application binding, and code are immutable after creation/,
  );
});

test('RoleVersion composition remains typed, complete, versioned, and lifecycle-driven', () => {
  assertSourceContains(
    catalog,
    /parseRoleVersionMembers/,
    /ENTITLEMENT:<id>/,
    /APPLICATION_ROLE:<id>/,
    /kind !== 'ENTITLEMENT' && kind !== 'APPLICATION_ROLE'/,
    /complete typed composition snapshot/,
    /Validate re-checks current references and moves DRAFT to READY/,
    /Activate re-checks READY, makes it ACTIVE, and supersedes any prior ACTIVE version/,
    /ACTIVE and SUPERSEDED content is immutable/,
    /label: 'Validate draft'/,
    /label: 'Activate version'/,
  );
});

test('Catalog refinement keeps provider observation and authority boundaries visible', () => {
  assertSourceContains(
    catalog,
    /provider observations never become Catalog authority implicitly/,
    /provider discovery remains observation, not Catalog maintenance/,
    /The browser does not infer authority from this selection/,
    /arbitrary JSON and in-place member patching are not supported/,
  );
  assertSourceExcludes(
    catalog,
    /localStorage|sessionStorage/,
    /Authorization:\s*['"`]/,
    /VITE_.*BACKEND|BACKEND_ORIGIN/,
    /@mui\//,
    /\/observed-entitlements|\/observed-grants/,
  );
});
