#!/bin/sh
set -eu

entrypoint="apps/server/docker-entrypoint.sh"

fail() {
    echo "$*" >&2
    exit 1
}

# Shared DEV must never fall back to the startup/headless bootstrap adapter.
if env \
    IAM_DEPLOYMENT_ENVIRONMENT=dev \
    IAM_BOOTSTRAP_INSTALLATION_ENABLED=true \
    sh "$entrypoint" sh -c 'exit 0' >/tmp/wyrmgate-entrypoint.out 2>/tmp/wyrmgate-entrypoint.err; then
    fail "managed DEV accepted IAM_BOOTSTRAP_INSTALLATION_ENABLED=true"
fi
grep -Fq 'managed DEV requires the first-run web setup wizard' /tmp/wyrmgate-entrypoint.err

# A provider-managed web setup secret is materialized as a private one-use file boundary,
# while the raw provider variable is removed before the application process is exec'd.
env \
    IAM_DEPLOYMENT_ENVIRONMENT=dev \
    IAM_BOOTSTRAP_WEB_ENABLED=true \
    IAM_BOOTSTRAP_WEB_SETUP_SECRET='setup-fixture-value-with-more-than-thirty-two-characters' \
    sh "$entrypoint" sh -c '
        test -z "${IAM_BOOTSTRAP_WEB_SETUP_SECRET:-}"
        test -n "${IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE:-}"
        test -p "$IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE"
        value="$(cat "$IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE")"
        test "$value" = "setup-fixture-value-with-more-than-thirty-two-characters"
    '

# Web setup must have exactly one source at the deployment adapter boundary.
if env \
    IAM_BOOTSTRAP_WEB_ENABLED=true \
    IAM_BOOTSTRAP_WEB_SETUP_SECRET='setup-fixture-value-with-more-than-thirty-two-characters' \
    IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE=/tmp/already-materialized \
    sh "$entrypoint" sh -c 'exit 0' >/tmp/wyrmgate-entrypoint.out 2>/tmp/wyrmgate-entrypoint.err; then
    fail "web setup accepted both secret sources"
fi
grep -Fq 'configure exactly one of IAM_BOOTSTRAP_WEB_SETUP_SECRET or IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE' /tmp/wyrmgate-entrypoint.err

rm -f /tmp/wyrmgate-entrypoint.out /tmp/wyrmgate-entrypoint.err
