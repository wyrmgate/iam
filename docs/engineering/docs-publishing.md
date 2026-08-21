# Documentation publishing

## Purpose

Sprint 14 establishes CI/CD for the curated public documentation site without changing documentation authority.

The public site is a rendered publication surface. It is not a second architecture, requirements, or engineering source of truth.

## Source layout

- `docs/public/` — content eligible for public publication.
- `docs-site/` — Docusaurus rendering configuration and dependencies.
- repository architecture/domain/security/API/ADR Markdown — private implementation-facing canonical documentation unless explicitly curated into `docs/public/`.
- formal specifications — controlled documents in the formal Google Drive package; they are not automatically published.

Customer-only and internal-only publication surfaces may be added later, but they must remain explicitly separated from public content.

## CI contract

`.github/workflows/docs.yml` runs for changes to the public docs source, Docusaurus site, workflow, or this publishing contract.

Every pull request must:

1. install the exact direct Docusaurus/React dependency versions declared by `docs-site/package.json`;
2. run `npm run build`;
3. fail on broken Docusaurus links;
4. avoid entering the protected `docs` deployment environment.

The current baseline does not commit an npm lockfile for the docs site. Direct dependencies are exact, but transitive dependency resolution is not yet fully reproducible. A generated and reviewed lockfile should be added before the docs toolchain is treated as release-grade supply-chain input.

## Publication contract

Publication is intentionally disabled by default.

A `main` build publishes only when repository/environment variable `DOCS_DEPLOY_ENABLED` equals `true`.

The protected GitHub Environment `docs` then requires:

- secret `CLOUDFLARE_API_TOKEN`;
- secret `CLOUDFLARE_ACCOUNT_ID`;
- variable `CLOUDFLARE_PAGES_PROJECT`.

The workflow uses Cloudflare Wrangler Action v4 and Wrangler v4-compatible Pages deployment commands. Cloudflare credentials are never stored in repository content or Docusaurus configuration.

## Public-content security boundary

Public documentation must not contain:

- passwords, private keys, tokens, authorization headers, signing material, recovery codes, provider credentials, or secret values;
- customer or tenant data;
- internal-only administrative/security procedures that would materially weaken defensive controls;
- private infrastructure addresses or host access details;
- unpublished commercial or implementation material merely because it exists elsewhere in the repository.

Publication review is therefore a content-classification decision, not just a successful static-site build.

## Domain and architecture authority

If public documentation summarizes a canonical concept, the canonical repository ADR/formal specification remains authoritative. Public text should be corrected when it drifts; public text must not silently redefine IAM semantics.

## Local validation

From `docs-site/`:

```bash
npm install --no-package-lock --no-audit --no-fund
npm run build
```

The generated site is written to `docs-site/build/` and is not committed.

## Activation checklist

Before setting `DOCS_DEPLOY_ENABLED=true`:

1. create the Cloudflare Pages project;
2. create/configure the `docs` GitHub Environment;
3. add the Cloudflare account ID and scoped API token as environment secrets;
4. set `CLOUDFLARE_PAGES_PROJECT`;
5. verify the intended public hostname and DNS configuration;
6. review all files under `docs/public/` as externally distributable content;
7. run the Docs CI/CD workflow successfully on `main`.

Custom-domain/DNS activation is external infrastructure work and is intentionally not performed by the repository scaffold itself.
