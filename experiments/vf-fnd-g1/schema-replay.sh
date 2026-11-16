#!/usr/bin/env bash
set -euo pipefail
# Disposable local container only. V42 raw migration chain -> equivalent schema clone.
name=vf-fnd-g1-postgres
out=/tmp/vf-fnd-g1
mkdir -p "$out"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run --detach --rm --name "$name" -e POSTGRES_PASSWORD=synthetic-only -e POSTGRES_USER=experiment -e POSTGRES_DB=source postgres:18.4-alpine
ready=0
for i in $(seq 1 60); do
  if docker exec "$name" pg_isready -h 127.0.0.1 -U experiment -d source >/dev/null 2>&1; then
    ready=$((ready+1)); if [ "$ready" -ge 2 ]; then break; fi
  else ready=0; fi
  sleep 1
done
test "$ready" -ge 2
docker exec "$name" pg_isready -h 127.0.0.1 -U experiment -d source
docker exec "$name" postgres --version
psql_db(){ local db="$1"; shift; docker exec -i "$name" psql -X -v ON_ERROR_STOP=1 -U experiment -d "$db" "$@"; }
for f in $(find apps/server/src/main/resources/db/migration -maxdepth 1 -name 'V*__*.sql' | sort -V); do
  echo "$(basename "$f")"
  psql_db source < "$f" >/dev/null
done
docker exec "$name" createdb -U experiment candidate
dump(){ docker exec "$name" pg_dump -U experiment -d "$1" --schema-only --no-owner --no-privileges | sed -E '/^\\(un)?restrict /d'; }
dump source > "$out/source-schema.sql"
psql_db candidate < "$out/source-schema.sql" >/dev/null
dump candidate > "$out/candidate-schema.sql"
diff -u "$out/source-schema.sql" "$out/candidate-schema.sql" > "$out/raw-diff.txt" || test "$?" -eq 1
# PG18 reparsing changes only deparser syntax for the observed varchar literal arrays
# cast as text[]. Normalize that exact expression, not arbitrary constraints or SQL.
python3 - "$out" <<'PY'
import pathlib, re, sys
p=pathlib.Path(sys.argv[1])
literal = r"'[A-Z0-9_]+'::character varying"
pattern = re.compile(r"\(\(ARRAY\[("+literal+r"(?:, "+literal+r")*)\]\)::text\[\]\)")
for stem in ('source','candidate'):
    data=(p/(stem+'-schema.sql')).read_text()
    data,n=pattern.subn(lambda m: 'ARRAY['+', '.join('('+v+')::text' for v in m.group(1).split(', '))+']',data)
    (p/(stem+'-normalized.sql')).write_text(data)
    print(stem,'narrow cast normalization count',n)
PY
diff -u "$out/source-normalized.sql" "$out/candidate-normalized.sql"
psql_db source <<'SQL'
INSERT INTO tenancy.tenant(id,display_name,revision,created_at,updated_at,lifecycle_state) VALUES
 ('019a7f0e-2234-7abc-8def-1234567890ab','Synthetic A',1,'2026-11-16 00:00:00+00','2026-11-16 00:00:00+00','PROVISIONING'),
 ('019a7f0e-2234-7abc-8def-1234567890ac','Synthetic B',2,'2026-11-16 00:00:00+00','2026-11-16 00:00:00+00','SUSPENDED');
INSERT INTO identity.identity(id,tenant_id,identity_type,lifecycle_state,display_name,revision,created_at,updated_at) VALUES
 ('019a7f0e-1111-7abc-8def-123456789001','019a7f0e-2234-7abc-8def-1234567890ab','PERSON','PENDING','Synthetic employee',2,now(),now()),
 ('019a7f0e-1111-7abc-8def-123456789002','019a7f0e-2234-7abc-8def-1234567890ab','PERSON','PENDING','Synthetic manager',3,now(),now()),
 ('019a7f0e-1111-7abc-8def-123456789003','019a7f0e-2234-7abc-8def-1234567890ac','PERSON','PENDING','Synthetic other',1,now(),now());
INSERT INTO identity.person_profile(identity_id,tenant_id,created_at) SELECT id,tenant_id,created_at FROM identity.identity;
INSERT INTO identity.organization(id,tenant_id,name,organization_type,revision,created_at,updated_at)
 VALUES ('019a7f0e-1111-7abc-8def-123456789004','019a7f0e-2234-7abc-8def-1234567890ab','Synthetic org','BUSINESS_UNIT',2,now(),now());
INSERT INTO identity.manager_relationship(id,tenant_id,subject_identity_id,manager_identity_id,valid_from,provenance_kind,revision,created_at,updated_at)
 VALUES ('019a7f0e-1111-7abc-8def-123456789005','019a7f0e-2234-7abc-8def-1234567890ab','019a7f0e-1111-7abc-8def-123456789001','019a7f0e-1111-7abc-8def-123456789002',now(),'MANUAL',4,now(),now());
INSERT INTO identity.organization_membership(id,tenant_id,subject_identity_id,organization_id,valid_from,provenance_kind,revision,created_at,updated_at)
 VALUES ('019a7f0e-1111-7abc-8def-123456789006','019a7f0e-2234-7abc-8def-1234567890ab','019a7f0e-1111-7abc-8def-123456789001','019a7f0e-1111-7abc-8def-123456789004',now(),'MANUAL',2,now(),now());
SQL
# Explicit dependency order, subset of V42 data, no Flyway history.
tables=(tenancy.tenant identity.identity identity.person_profile identity.organization identity.manager_relationship identity.organization_membership)
: > "$out/fixture.sql"
for t in "${tables[@]}"; do
  docker exec "$name" pg_dump -U experiment -d source --data-only --no-owner --no-privileges --table="$t" >> "$out/fixture.sql"
done
psql_db candidate --single-transaction < "$out/fixture.sql" >/dev/null
for db in source candidate; do
  for t in "${tables[@]}"; do
    printf 'select row_to_json(t)::text from (select * from %s order by 1) t;\n' "$t" | psql_db "$db" -At
  done > "$out/$db-rows.txt"
done
diff -u "$out/source-rows.txt" "$out/candidate-rows.txt"
# Replay is rejected atomically by PK/unique constraints; this is NOT an idempotent importer.
if psql_db candidate --single-transaction < "$out/fixture.sql" > "$out/replay.log" 2>&1; then
  echo "Unexpected duplicate replay success" >&2; exit 1
fi
grep -q 'duplicate key' "$out/replay.log"
# Same-capability cross-tenant manager reference must fail.
if psql_db candidate > "$out/cross-tenant.log" 2>&1 <<'SQL'
UPDATE identity.manager_relationship
 SET manager_identity_id='019a7f0e-1111-7abc-8def-123456789003'
 WHERE id='019a7f0e-1111-7abc-8def-123456789005';
SQL
then echo "Unexpected cross-tenant update success" >&2; exit 1; fi
grep -q 'foreign key constraint' "$out/cross-tenant.log"
for t in "${tables[@]}"; do
  printf 'select row_to_json(t)::text from (select * from %s order by 1) t;\n' "$t" | psql_db candidate -At
done > "$out/after-replay-rows.txt"
diff -u "$out/source-rows.txt" "$out/after-replay-rows.txt"
sha256sum "$out/source-normalized.sql" "$out/candidate-normalized.sql" "$out/source-rows.txt" "$out/candidate-rows.txt" "$out/after-replay-rows.txt"
echo "E4: V42 schema clone narrow-normalized equivalent; six-table synthetic fixture, atomic duplicate rejection, cross-tenant FK denial. No changed target schema or idempotent migration claim."
