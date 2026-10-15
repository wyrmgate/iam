#!/bin/sh
set -eu

is_true() {
    case "${1:-}" in
        true|TRUE|True|1|yes|YES|Yes|on|ON|On) return 0 ;;
        *) return 1 ;;
    esac
}

deployment_environment="${IAM_DEPLOYMENT_ENVIRONMENT:-}"
startup_bootstrap="${IAM_BOOTSTRAP_INSTALLATION_ENABLED:-false}"
web_bootstrap="${IAM_BOOTSTRAP_WEB_ENABLED:-false}"
setup_secret="${IAM_BOOTSTRAP_WEB_SETUP_SECRET:-}"
setup_secret_file="${IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE:-}"

if [ "$deployment_environment" = "dev" ] && is_true "$startup_bootstrap"; then
    echo "managed DEV requires the first-run web setup wizard; IAM_BOOTSTRAP_INSTALLATION_ENABLED must be false" >&2
    exit 64
fi

if is_true "$web_bootstrap"; then
    has_secret=false
    has_file=false
    [ -n "$setup_secret" ] && has_secret=true
    [ -n "$setup_secret_file" ] && has_file=true

    if [ "$has_secret" = "$has_file" ]; then
        echo "configure exactly one of IAM_BOOTSTRAP_WEB_SETUP_SECRET or IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE when web setup is enabled" >&2
        exit 64
    fi

    if [ "$has_secret" = true ]; then
        bootstrap_dir="$(mktemp -d /tmp/wyrmgate-bootstrap.XXXXXX)"
        chmod 700 "$bootstrap_dir"
        secret_pipe="$bootstrap_dir/setup-secret"
        mkfifo -m 600 "$secret_pipe"

        (
            unset IAM_BOOTSTRAP_WEB_SETUP_SECRET
            printf '%s' "$setup_secret" > "$secret_pipe"
            unset setup_secret
            rm -f "$secret_pipe"
            rmdir "$bootstrap_dir" 2>/dev/null || true
        ) &

        export IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE="$secret_pipe"
    fi
fi

# IAM_BOOTSTRAP_WEB_SETUP_SECRET is a container/deployment adapter input, not an
# application property. Never expose the raw provider-managed value to the JVM.
unset IAM_BOOTSTRAP_WEB_SETUP_SECRET
unset setup_secret

exec "$@"
