#!/usr/bin/env bash
set -euo pipefail
# Copy only the actual unchanged IAM value types into the disposable test source tree.
src=apps/server/src/main/java
dst=experiments/vf-fnd-g1/src/test/java
for f in io/wyrmgate/iam/platform/tenant/TenantContext.java io/wyrmgate/iam/platform/id/IdGenerator.java io/wyrmgate/iam/platform/id/UuidV7Generator.java; do
  mkdir -p "$dst/$(dirname "$f")"
  cp "$src/$f" "$dst/$f"
  sha256sum "$src/$f"
done
