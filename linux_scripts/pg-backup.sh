#!/usr/bin/env bash
# pg-backup.sh - dump the obsidian Postgres DB (custom format), optionally verify by restore.
#
#   ./linux_scripts/pg-backup.sh                 # -> backups/pre-vaults-<ts>.dump (+ .counts sidecars)
#   ./linux_scripts/pg-backup.sh --verify [file] # restore into a throwaway postgres, compare
#                                                # per-table row counts; non-zero on mismatch
#                                                # (file defaults to the newest backups/pre-vaults-*.dump)
# Read-only on the live DB. Needs the compose stack's postgres up.
# From a git worktree set COMPOSE_PROJECT_NAME to the live project (e.g. obidianoptimizer).
#
# The live DB can be written while the dump runs (card regen etc.), so a restored table count must
# lie within [min,max] of the counts taken at dump start, dump end and verify time. Idle DB => exact.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

DB=obsidian; USR=obsidian
IMAGE="paradedb/paradedb:latest"
mkdir -p backups

pg() { docker compose exec -T postgres "$@"; }

# "schema.table|count" for every user table, sorted (exact counts via query_to_xml).
counts_sql="SELECT schemaname||'.'||relname||'|'||(xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', schemaname, relname), false, true, '')))[1]::text AS t
            FROM pg_stat_user_tables ORDER BY 1"

if [[ "${1:-}" != "--verify" ]]; then
  OUT="backups/pre-vaults-$(date +%Y%m%d-%H%M%S).dump"
  pg psql -U "$USR" -d "$DB" -At -c "$counts_sql" > "$OUT.counts"
  pg pg_dump -U "$USR" -d "$DB" -Fc > "$OUT"
  pg psql -U "$USR" -d "$DB" -At -c "$counts_sql" > "$OUT.counts.end"
  [[ -s "$OUT" ]] || { echo "[pg-backup] empty dump" >&2; rm -f "$OUT"; exit 1; }
  echo "[pg-backup] wrote $OUT ($(du -h "$OUT" | cut -f1))"
  exit 0
fi

FILE="${2:-$(ls -1t backups/pre-vaults-*.dump 2>/dev/null | head -1)}"
[[ -f "$FILE" ]] || { echo "[verify] no dump file" >&2; exit 2; }
NAME="pgverify-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
echo "[verify] restoring $FILE into throwaway $NAME ..."
docker run -d --name "$NAME" -e POSTGRES_DB=$DB -e POSTGRES_USER=$USR -e POSTGRES_PASSWORD=verify "$IMAGE" >/dev/null
sleep 5   # image may restart postgres once during init
for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -U "$USR" -d "$DB" >/dev/null 2>&1 && break; sleep 1
done
# Extension schemas already exist in the fresh image: those 3 harmless errors are expected.
docker exec -i "$NAME" pg_restore -U "$USR" -d "$DB" --no-owner < "$FILE" 2>"$FILE.restore.log" || \
  echo "[verify] pg_restore reported warnings (see $FILE.restore.log)"

restored=$(docker exec "$NAME" psql -U "$USR" -d "$DB" -At -c "$counts_sql")
now=$(pg psql -U "$USR" -d "$DB" -At -c "$counts_sql")

range=$(cat "$FILE.counts" "$FILE.counts.end" <(echo "$now") | awk -F'|' '
  { v=$2+0; if (!($1 in lo)) { lo[$1]=v; hi[$1]=v } else { if (v<lo[$1]) lo[$1]=v; if (v>hi[$1]) hi[$1]=v } }
  END { for (t in lo) print t "|" lo[t] "|" hi[t] }' | sort)

bad=$(awk -F'|' 'NR==FNR { lo[$1]=$2; hi[$1]=$3; next }
  { v=$2+0; if (!($1 in lo) || v<lo[$1] || v>hi[$1]) print "  " $1 " restored=" v " expected=[" lo[$1] "," hi[$1] "]" }' \
  <(echo "$range") <(echo "$restored"))
n_restored=$(echo "$restored" | wc -l)
n_expected=$(echo "$range" | wc -l)

if [[ -z "$bad" && "$n_restored" -eq "$n_expected" ]]; then
  echo "[verify] OK - $n_restored tables, restored row counts match live"
else
  echo "[verify] MISMATCH (tables restored=$n_restored expected=$n_expected):" >&2
  echo "$bad" >&2
  exit 1
fi
