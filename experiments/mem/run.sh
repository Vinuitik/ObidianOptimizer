#!/usr/bin/env bash
# Usage: run.sh <A|B|end> <outdir>   (OO_VERSION tag comes from exp.env or env override)
#   A   : reset + cold start + 8 min sampling after health   (~9 min)
#   B   : heavy ingest of fixtures/lecture.mp4 + fixtures/attention.pdf (<=25 min), then stop sampler
#   end : summarize + down -v (keeps external obsopt-exp-models volume)
set -u
cd "$(dirname "$0")/../.."
PH=${1:?A|B|end}; OUT=${2:?outdir}; M=linux_scripts/mem-profile.sh; P=obsopt-exp
DC="docker compose -p $P -f bundle/compose.yml -f experiments/mem/compose.exp.yml --env-file experiments/mem/exp.env"
export OO_VERSION=${OO_VERSION:-$(grep ^OO_VERSION experiments/mem/exp.env | cut -d= -f2)}
case $PH in
A)
  $DC down -v >/dev/null 2>&1; rm -rf "$OUT"; mkdir -p "$OUT"
  experiments/mem/setup-vault.sh
  nohup $M $P "$OUT" sample >/dev/null 2>&1 & echo $! > experiments/mem/sampler.pid
  sleep 4; $M $P "$OUT" event up
  $DC up -d 2>&1 | tail -3
  $M $P "$OUT" health 600 || exit 1
  sleep ${A_SOAK:-480}; $M $P "$OUT" event soak-end ;;
B)
  cp experiments/mem/fixtures/lecture.mp4 experiments/mem/vault/resources/lecture.mp4
  cp experiments/mem/fixtures/attention.pdf experiments/mem/vault/resources/attention.pdf
  sub() { docker exec ${P}-embedder-1 curl -s -X POST localhost:8000/ingest -H 'content-type: application/json' \
          -d "{\"ref\":\"resources/$1\",\"extract_only\":true}" | tee -a "$OUT/submit.log"; echo >> "$OUT/submit.log"; }
  $M $P "$OUT" event submit-video;  J1=$(sub lecture.mp4 | python3 -I -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id") or d.get("job_id") or d)'); echo "video job: $J1"
  $M $P "$OUT" event submit-pdf;    J2=$(sub attention.pdf | python3 -I -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id") or d.get("job_id") or d)'); echo "pdf job: $J2"
  end=$(( $(date +%s) + ${B_MAX:-1500} )); d1=0; d2=0
  while [ "$(date +%s)" -lt "$end" ] && { [ $d1 = 0 ] || [ $d2 = 0 ]; }; do
    for k in 1 2; do
      eval j=\$J$k; eval dd=\$d$k; [ "$dd" = 1 ] && continue
      st=$(docker exec ${P}-embedder-1 curl -s localhost:8000/ingest/$j | python3 -I -c 'import sys,json;print(json.load(sys.stdin).get("status"))')
      case $st in DONE|FAILED) eval d$k=1; $M $P "$OUT" event "job$k-$j-$st" ;; esac
    done; sleep 5
  done
  sleep 30; $M $P "$OUT" event ingest-end ;;
end)
  touch "$OUT/.stop"; sleep 4; $M $P "$OUT" summarize; $DC down -v 2>&1 | tail -2 ;;
esac
