#!/usr/bin/env bash
# Memory profiler for a compose project (used by experiments/mem, see its README).
# Usage: mem-profile.sh <project> <outdir> <phase> [args]
#   sample           loop every 2s until <outdir>/.stop exists; appends samples.csv; kill switch
#   event NAME...    append "<epoch> NAME" to events.log
#   health [secs]    poll $HEALTH_URL (default http://127.0.0.1:8091/api/health) until 200; logs health-ok
#   summarize        write summary.md from samples.csv + events.log
# Kill switch: host MemAvailable < ABORT_MB (default 700) -> `docker compose -p <project> stop`, event ABORT.
set -u
P=${1:?project}; OUT=${2:?outdir}; PHASE=${3:?phase}; shift 3
ABORT_MB=${ABORT_MB:-700}; INTERVAL=${INTERVAL:-2}
HEALTH_URL=${HEALTH_URL:-http://127.0.0.1:8091/api/health}
mkdir -p "$OUT"; CSV="$OUT/samples.csv"; EV="$OUT/events.log"
avail_mb() { awk '/MemAvailable/{printf "%d", $2/1024}' /proc/meminfo; }
to_mb() { awk -v s="$1" 'BEGIN{n=s+0; if(s~/GiB/)n*=1024; else if(s~/KiB/)n/=1024; else if(s~/B$/&&s!~/MiB/)n/=1048576; printf "%.1f", n}'; }
case "$PHASE" in
event) echo "$(date +%s) $*" >> "$EV" ;;
health)
  T=${1:-900}; end=$(( $(date +%s) + T ))
  while [ "$(date +%s)" -lt "$end" ]; do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$HEALTH_URL")" = 200 ]; then
      echo "$(date +%s) health-ok" >> "$EV"; echo "health-ok"; exit 0; fi
    sleep 2; done
  echo "$(date +%s) health-timeout" >> "$EV"; exit 1 ;;
sample)
  [ -f "$CSV" ] || echo "epoch,container,mem_mb,limit_mb,host_avail_mb,oom_killed,restarts" > "$CSV"
  rm -f "$OUT/.stop"
  while [ ! -f "$OUT/.stop" ]; do
    now=$(date +%s); av=$(avail_mb)
    if [ "$av" -lt "$ABORT_MB" ]; then
      docker compose -p "$P" stop >/dev/null 2>&1
      echo "$now ABORT host MemAvailable=${av}MB" >> "$EV"; echo "ABORT at $now ($av MB)" >&2; exit 2
    fi
    ids=$(docker ps -aq --filter "label=com.docker.compose.project=$P")
    if [ -n "$ids" ]; then
      declare -A ST=()
      while IFS='|' read -r n o r; do ST[$n]="$o,$r"; done < <(docker inspect -f '{{.Name}}|{{.State.OOMKilled}}|{{.RestartCount}}' $ids | sed 's#^/##')
      while IFS='|' read -r n usage; do
        mem=${usage%% / *}; lim=${usage##* / }
        echo "$now,$n,$(to_mb "$mem"),$(to_mb "$lim"),$av,${ST[$n]:-false,0}" >> "$CSV"
      done < <(docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}' $ids 2>/dev/null)
      unset ST
    fi
    sleep "$INTERVAL"
  done ;;
summarize)
  OUT="$OUT" python3 -I - <<'PY'
import csv, os, collections
out = os.environ["OUT"]
rows = list(csv.DictReader(open(out + "/samples.csv")))
events = []
for l in open(out + "/events.log"):
    p = l.split(None, 1)
    if p: events.append((int(p[0]), p[1].strip() if len(p) > 1 else ""))
events.sort()
t0 = int(rows[0]["epoch"]) if rows else 0
rel = lambda t: t - t0
def ev_at(t, win=30):
    near = [f"{n}(t{rel(e):+d}s)" for e, n in events if abs(e - t) <= win]
    prev = [n for e, n in events if e <= t]
    return "; ".join(near) or ("after " + prev[-1] if prev else "-")
by = collections.defaultdict(list)
for r in rows: by[r["container"]].append(r)
L = ["# Memory summary", "", f"samples: {len(rows)}, span: {rel(int(rows[-1]['epoch']))}s" if rows else "no samples", "",
     "## Per-container peak", "", "container | peak MB | limit MB | t(s) | events within 30s", "--- | --- | --- | --- | ---"]
tot = collections.defaultdict(float)
for r in rows: tot[r["epoch"]] += float(r["mem_mb"])
for c, rs in sorted(by.items()):
    p = max(rs, key=lambda r: float(r["mem_mb"]))
    L.append(f"{c} | {float(p['mem_mb']):.0f} | {float(p['limit_mb']):.0f} | {rel(int(p['epoch']))} | {ev_at(int(p['epoch']))}")
if tot:
    pe = max(tot, key=tot.get)
    L += ["", f"**Sum of containers peak: {tot[pe]:.0f} MB at t={rel(int(pe))}s** ({ev_at(int(pe))})"]
    per = {r["container"]: float(r["mem_mb"]) for r in rows if r["epoch"] == pe}
    L.append("composition: " + ", ".join(f"{k}={v:.0f}" for k, v in sorted(per.items())))
if rows:
    m = min(rows, key=lambda r: int(r["host_avail_mb"]))
    L += ["", f"**Host min MemAvailable: {m['host_avail_mb']} MB at t={rel(int(m['epoch']))}s** ({ev_at(int(m['epoch']))})"]
oom = sorted({r["container"] for r in rows if r["oom_killed"] == "true"})
rst = {c: max(int(r["restarts"]) for r in rs) for c, rs in by.items()}
L += ["", f"OOM-killed containers: {', '.join(oom) or 'none'}",
      "Restarts: " + (", ".join(f"{c}={n}" for c, n in rst.items() if n) or "none")]
ev = dict()
for e, n in events: ev.setdefault(n.split()[0], e)
if "up" in ev and "health-ok" in ev:
    L.append(f"Time to /api/health 200: {ev['health-ok'] - ev['up']}s")
L += ["", "## Events", ""] + [f"- t={rel(e):>5}s  {n}" for e, n in events]
open(out + "/summary.md", "w").write("\n".join(L) + "\n")
print("\n".join(L))
PY
  ;;
*) echo "unknown phase $PHASE" >&2; exit 1 ;;
esac
