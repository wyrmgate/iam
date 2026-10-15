# First-run web setup security boundary

ADR-0045 adds an optional browser adapter for the ADR-0044 fresh-install bootstrap. This surface exists only before the deployment bootstrap is permanently sealed and is not ordinary IAM authentication or Administration authorization.

## Trust model

The deployment operator remains the root of trust for installation. Enabling web setup requires both:

- `iam.bootstrap.web.enabled=true`; and
- an operator-controlled setup-secret file configured by `iam.bootstrap.web.setup-secret-file`.

The setup secret is installation-scoped proof only. It never represents a Tenant actor, Identity, Principal, Credential or Administration authority. It cannot authorize `/api/v1/**` control-plane operations.

The first administrator password is a separate Credential secret and must never be reused as the setup proof.

Managed container runtimes may transport the setup proof to the Wyrmgate container through a provider-managed secret environment variable. The official server entrypoint may materialize that value into a private runtime file before Java starts, unset the plaintext variable, and pass only the file path into the application. This does not change the Java application's file-only trusted boundary.

## Browser session

A valid setup proof is exchanged for a short-lived signed setup-session cookie. The source setup secret is reduced to derived proof/signing material at server startup and is not retained in plaintext.

The setup session:

- defaults to 15 minutes and cannot be configured beyond one hour;
- uses a `__Host-` cookie with `Secure`, `HttpOnly`, `SameSite=Strict` and `Path=/`;
- is checked by the installation web adapter on every setup mutation;
- becomes ineffective when the durable installation state is `SEALED`;
- is not accepted by the ordinary control-plane authentication filters.

Setup mutation also uses an independent `__Host-wyrmgate_setup_csrf` double-submit token and `X-Wyrmgate-Setup-CSRF` header. Normal Console CSRF state does not authorize setup and setup CSRF state does not authorize normal authenticated mutations.

## Network boundary

The adapter is same-origin only and does not broaden CORS. Production-like use requires HTTPS because the setup cookies are `Secure` `__Host-` cookies.

Operators should restrict initial network reachability as appropriate for their deployment environment. The setup proof is the application-level authorization boundary; network restriction is defense in depth, not a replacement for the proof.

## Data minimization

`GET /api/setup/status` is intentionally available before ordinary authentication so Console can decide whether first-run setup is required. Responses are `no-store` and limited to:

- `UNINITIALIZED`, `IN_PROGRESS` or `SEALED`;
- the current durable bootstrap phase;
- frozen non-secret human setup inputs;
- the trusted Console redirect URI;
- current validity of the presented setup-session cookie.

The status projection must not expose internal object IDs, Credential IDs, verifier material, generated secret material, correlation identifiers, setup proof or administrator password.

Error responses use stable generic codes/messages and do not reflect internal exception messages.

## Trusted versus browser input

Trusted deployment configuration owns the Console redirect/public routing. Browser requests cannot supply or override it.

Before the durable plan exists, browser input is limited to the first Tenant display name and first administrator display name, username and password. After `PLANNED`, non-secret values are frozen by the durable installation plan and the browser may only resupply the original password needed for Credential verification/resume.

## Secret handling

The setup-secret file may contain a single trailing newline. At least 32 characters are required. The server derives separate SHA-256 context-separated values for proof comparison and setup-session signing, then wipes the plaintext character buffer.

When the official container receives `IAM_BOOTSTRAP_WEB_SETUP_SECRET`, the entrypoint writes it under an ephemeral runtime directory with a restrictive `077` umask, exports the resulting path as `IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE`, and removes `IAM_BOOTSTRAP_WEB_SETUP_SECRET` from the environment inherited by Java. Configuring both transport forms fails closed.

Browser proof and administrator password request records use mutable character arrays server-side and are wiped after handling. The installation service separately copies/wipes password material while passing it only to Credential-owned enrollment/verification.

No setup proof or administrator password may be logged, audited, included in events or persisted in `platform.installation_bootstrap`.

## Seal and recovery

The deployment bootstrap seal remains irreversible. A successful `VERIFIED -> SEALED` transition clears setup cookies. Keeping or recreating setup-secret transport material cannot reopen setup.

Loss of all administrators after sealing is a recovery concern and must use a separately governed recovery mechanism. Deleting or modifying the installation seal is not a supported recovery procedure.
