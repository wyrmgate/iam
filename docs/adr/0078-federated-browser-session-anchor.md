# ADR-0078: Federated browser-session anchor

Status: Accepted

## Decision

V38 adds mutually exclusive local Credential and federated trust/link anchors to the existing Platform browser session. No synthetic Credential, second AuthUser, or new authoritative identity state. Federated session requires exact current FederationTrust/FederatedSubjectLink, Tenant, Principal and Identity revisions and proof validity from the ADR-0071 resolver. Session idle/absolute expiry is capped by proof expiry; token auth_time uses the actual upstream authentication time. Revalidate anchor on every browser/session/code redemption read; trust/link retirement or revision changes invalidate session, as do Tenant/Identity/Principal changes or proof expiry. Password-change requires local Credential anchor. Session creation still requires Audit evidence; failed evidence revokes unreleased session. No raw external subject, assertion, upstream token or verifier stored in session.

Existing local session rows remain unchanged and retain exact Credential revision validation. Public federation endpoints remain disabled until the next durable callback slice explicitly enables them.
