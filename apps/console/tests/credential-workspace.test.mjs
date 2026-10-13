import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const credentialWorkspace = await readConsoleSource('workspaces/CredentialsWorkspace.tsx');

test('Credential workspace keeps Principal scope explicit without moving Principal authority into Credential', () => {
  assertSourceContains(
    credentialWorkspace,
    /Credential belongs to exactly one Principal/,
    /Active Principal context/,
    /principalId/,
    /\/credentials/,
    /Change Principal/,
  );
});

test('Credential create flow is metadata-only and accepts only the opaque SecretReference shape', () => {
  assertSourceContains(
    credentialWorkspace,
    /Metadata only — not a secret vault/,
    /PASSWORD/,
    /API_KEY/,
    /SSH_KEY/,
    /CERTIFICATE/,
    /OAUTH_CLIENT_SECRET/,
    /secretReference:\s*\{/,
    /providerType:\s*values\.providerType/,
    /referenceKey:\s*values\.referenceKey/,
    /Never paste a password, API-key value, private key, certificate private key, OAuth client secret, token/,
  );
  assertSourceExcludes(
    credentialWorkspace,
    /passwordValue|apiKeyValue|privateKeyValue|clientSecretValue|secretValue|secretUpload|retrieveSecret|downloadSecret/,
  );
});

test('Credential lifecycle presentation separates validity, revocation, compromise, and provider residue', () => {
  assertSourceContains(
    credentialWorkspace,
    /SCHEDULED/,
    /ACTIVE/,
    /EXPIRED/,
    /REVOKED/,
    /COMPROMISED/,
    /scheduler delay cannot extend validity/i,
    /provider-side residue remains separate observation or fulfillment state/,
    /Revoke credential[\s\S]*destructive: true/,
    /Mark compromised[\s\S]*destructive: true/,
    /server authorization, revision checks, and idempotency remain final/i,
  );
});

test('Credential rotation uses only accepted plan and read contracts and remains separate process state', () => {
  assertSourceContains(
    credentialWorkspace,
    /Routine rotation is a durable process, not a status edit/,
    /PLANNED CredentialRotation/,
    /\/credentials\/\$\{encodeURIComponent\(activeCredentialId\)\}:rotate/,
    /idempotent: true/,
    /\/credentials\/\$\{encodeURIComponent\(activeCredentialId\)\}\/rotations/,
    /\/credential-rotations\/\$\{encodeURIComponent\(id\)\}/,
    /processState/,
    /replacementCredentialId/,
    /failureCode/,
    /does not use the Credential If-Match revision/,
  );
  assertSourceExcludes(
    credentialWorkspace,
    /CREATING_REPLACEMENT.*path|DISTRIBUTING.*path|VERIFYING.*path|CUTOVER_COMPLETE.*path|REVOKING_OLD.*path/,
    /\/rotation[^'"`]*:(advance|complete|retry|cutover)/,
  );
});

test('Credential workspace presents explicit responsive loading, empty, error, and safety states through existing primitives', () => {
  assertSourceContains(
    credentialWorkspace,
    /state="loading"/,
    /state="empty"/,
    /state="error"/,
    /state="informational"/,
    /state="warning"/,
    /flexDirection:\s*\{ xs: 'column', sm: 'row' \}/,
  );
});
