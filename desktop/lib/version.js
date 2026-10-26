// Pure semver-ish compare (major.minor.patch, optional leading v; a prerelease sorts below its release).
function parse(v) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:-([\w.]+))?/.exec(String(v || '').trim());
  if (!m) throw new Error(`bad version: ${v}`);
  return { n: [+m[1], +m[2], +m[3]], pre: m[4] || null };
}
function compare(a, b) {
  const x = parse(a), y = parse(b);
  for (let i = 0; i < 3; i++) if (x.n[i] !== y.n[i]) return x.n[i] < y.n[i] ? -1 : 1;
  if (x.pre === y.pre) return 0;
  if (!x.pre) return 1;
  if (!y.pre) return -1;
  return x.pre < y.pre ? -1 : 1;
}
const isNewer = (candidate, current) => compare(candidate, current) > 0;
const launcherOk = (manifest, launcherVersion) => !manifest.minLauncher || compare(launcherVersion, manifest.minLauncher) >= 0;
module.exports = { parse, compare, isNewer, launcherOk };
