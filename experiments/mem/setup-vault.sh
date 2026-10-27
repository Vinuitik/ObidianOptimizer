#!/usr/bin/env bash
# Rebuild the scratch test vault (300 notes, read-only copy from the real vault) + fixtures.
# Containers write root-owned files into vault/, so wipe through docker.
set -eu
cd "$(dirname "$0")"; N=${SRC_VAULT:-/home/victor/Desktop/NewLife}
mkdir -p fixtures vault
docker run --rm -v "$PWD/vault:/v" alpine sh -c 'rm -rf /v/* /v/.[!.]*' 2>/dev/null || true
mkdir -p vault/_reports vault/_workspace vault/resources
(cd "$N" && find . -name '*.md' -not -path './resources/*' -not -path './_workspace/*' -not -path './_reports/*' \
   -not -path './.obsidian/*' -not -path './_trash/*' -not -path './_inbox/*' | sort | head -300 | tar cf - -T -) | tar xf - -C vault
[ -f fixtures/lecture.mp4 ] || cp "$N/resources/videos/QueensXQBUS501x-V000400_DTH.mp4" fixtures/lecture.mp4   # 14.3MB, 288s lecture
[ -f fixtures/attention.pdf ] || cp "$N/resources/pdf/1706.03762.pdf" fixtures/attention.pdf                    # 2.2MB, 15 pages
echo "vault notes: $(find vault -name '*.md' | wc -l)"
