#!/usr/bin/env bash
set -euo pipefail
# Disposable DB only. No external host/user data. Current V42 migration chain -> clean schema clone.
name=vf-fnd-g1-postgres
out=/tmp/vf-fnd-g1
mkdir -p "$out"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run --detach --rm --name "$name" -e POSTGRES_PASSWORD=synthetic-only -e POSTGRES_USER=experiment -e POSTGRES_DB=source postgres:18.4-alpine
for i in $(seq 1 60); do
  if docker exec "$name" pg_isready -U experiment -d source >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$name" pg_isready -U experiment -d source
psql_db(){ docker exec -i "$name" psql -X -v ON_ERROR_STOP=1 -U experiment -d "$1"; }
for f in $(find apps/server/src/main/resources/db/migration -maxdepth 1 -name 'V*__*.sql' | sort -V); do
  echo "$(basename "$f")"
  psql_db source < "$f" >/dev/null
done
docker exec "$name" createdb -U experiment candidate
dump(){ docker exec "$name" pg_dump -U experiment -d "$1" --schema-only --no-owner --no-privileges | sed -E '/^\\(un)?restrict /d'; }
dump source > "$out/source-schema.sql"
psql_db candidate < "$out/source-schema.sql" >/dev/null
dump candidate > "$out/candidate-schema.sql"
diff -u "$out/source-schema.sql" "$out/candidate-schema.sql"
psql_db source <<'SQL'
INSERT INTO tenancy.tenant(id,display_name,revision,created_at,updated_at,lifecycle_state) VALUES
 ('019a7f0e-2234-7abc-8def-1234567890ab','Synthetic A',1,'2026-11-16Z','2026-11-16Z','PROVISIONING'),
 ('019a7f0e-2234-7abc-8def-1234567890ac','Synthetic B',2,'2026-11-16Z','2026-11-16Z','SUSPENDED');
SQL
# Data-only fixture, not customer migration and not Flyway history.
docker exec "$name" pg_dump -U experiment -d source --data-only --no-owner --no-privileges --table=tenancy.tenant > "$out/tenant-fixture.sql"
psql_db candidate < "$out/tenant-fixture.sql" >/dev/null
for db in source candidate; do
  printf "select id,display_name,revision,lifecycle_state from tenancy.tenant order by id;\n" | psql_db "$db" > "$out/$db-tenant.txt"
done
diff -u "$out/source-tenant.txt" "$out/candidate-tenant.txt"
printf "select count(*) from tenancy.tenant;\n" | psql_db candidate
sha256sum "$out/source-schema.sql" "$out/candidate-schema.sql"
echo "E4: schema replay + two synthetic tenant rows equivalent; no changed target schema or Flyway equivalence claim."
