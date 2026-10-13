import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const integrationWorkspace = await readConsoleSource('workspaces/IntegrationsWorkspace.tsx');

test('Integration workspace separates connector lifecycle from operational state', () => {
  assertSourceContains(
    integrationWorkspace,
    /Integration · connector lifecycle/,
    /Connector lifecycle is technical authority/,
    /Provider observations produced through a connector remain observations/,
    /Operations · read-only/,
    /Operational \/ non-authoritative/,
    /never become Access intent, desired provider state, or Catalog authority/,
  );
});

test('Connector configuration keeps non-secret configuration and opaque secret references distinct', () => {
  assertSourceContains(
    integrationWorkspace,
    /Non-secret provider configuration JSON/,
    /Never place passwords, tokens, private keys, or other secret values here/,
    /Opaque secret reference/,
    /write-only pointer/,
    /read responses expose only whether a secret is configured/,
    /Disable connector/,
    /does not offer generic status mutation/,
  );
});

test('Connector binding presentation explains scope, contract, and completeness without provider authority', () => {
  assertSourceContains(
    integrationWorkspace,
    /Binding defines connector scope and contract/,
    /ConnectorInstance, target kind, and target ID/,
    /Contract identity\/version and discovery-completeness capabilities/,
    /Principal, entitlement, and grant discovery completeness are separate capabilities/,
    /PARTIAL or UNKNOWN coverage/,
    /never authorizes destructive absence inference/,
    /Disable binding/,
  );
});

test('Worker administration remains registration-only and keeps execution protocol out of the browser', () => {
  assertSourceContains(
    integrationWorkspace,
    /Registration is separate from worker execution/,
    /external subject binding, protocol compatibility, allowed ConnectorBinding scope/,
    /Internal execution protocol stays private/,
    /Claiming work, lease renewal, observation batching, completion fencing/,
    /never browser controls/,
    /Disable worker registration/,
  );
  assertSourceExcludes(
    integrationWorkspace,
    /\/internal\/connector-worker/,
    /leaseId|leaseEpoch|completionFingerprint|checkpointPayload|taskPayload/,
  );
});

test('Integration workspace stays on accepted public administration routes and consumes operations unchanged', () => {
  assertSourceContains(
    integrationWorkspace,
    /\/connectors/,
    /\/connector-bindings/,
    /\/connector-workers/,
    /IntegrationOperationsPage/,
  );
  assertSourceExcludes(
    integrationWorkspace,
    /:enable|:claim|:complete|:renew|provider-native editor|generic status editor/,
  );
});
