#!/bin/sh
set -eu

# Shared DEV must exercise the same secured browser bootstrap used by interactive installs.
# Keep the legacy startup adapter available for other deployment modes, but fail closed when
# somebody attempts to enable it in the managed DEV environment.
if [ "${IAM_DEPLOYMENT_ENVIRONMENT:-}" = "dev" ] \
    && [ "${IAM_BOOTSTRAP_INSTALLATION_ENABLED:-false}" = "true" ]; then
    echo "startup installation bootstrap is disabled for DEV; use the first-run web setup wizard" >&2
    exit 1
fi

# ADR-0045 keeps the Java application boundary file-only. Managed runtimes such as Railway
# commonly provide secrets as environment variables, so convert that deployment transport into
# a private ephemeral file before Java starts, then remove the secret from the child environment.
if [ -n "${IAM_BOOTSTRAP_WEB_SETUP_SECRET:-}" ]; then
    if [ -n "${IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE:-}" ]; then
        echo "configure only one of IAM_BOOTSTRAP_WEB_SETUP_SECRET or IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE" >&2
        exit 1
    fi

    runtime_dir="${TMPDIR:-/tmp}/wyrmgate-bootstrap"
    setup_file="${runtime_dir}/setup-access"
    old_umask=$(umask)
    umask 077
    mkdir -p "$runtime_dir"
    printf '%s' "$IAM_BOOTSTRAP_WEB_SETUP_SECRET" > "$setup_file"
    umask "$old_umask"

    export IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE="$setup_file"
    unset IAM_BOOTSTRAP_WEB_SETUP_SECRET
fi

exec "$@"
