# Registry Worker Flows

Files: src/index.ts, src/cf.ts, src/names.ts, wrangler.toml, test/registry.test.ts

## Claim
POST /v1/claim → `claim()` → `validateName()` + email regex → `rateLimited()` (KV `rl:<ip>:<date>`) → KV `name:<n>` exists? 409 → write pending marker (TTL 120s) → `cf.createTunnel` → `cf.setTunnelIngress` → `cf.createCname` (proxied) → `cf.createAccessApp`+`createPolicy` (allow email) → mcp Access app + bypass policy → `cf.getTunnelToken` → random 32B hex installSecret → KV record with SHA-256 hash → 201.
Failure at any step → undo stack in reverse (delete mcp app, main app, DNS, tunnel) → KV pending marker deleted → 502.
To change ingress target: env var `TUNNEL_ORIGIN`. To change limit: `CLAIMS_PER_IP_PER_DAY`. To change reserved names: `RESERVED` in names.ts.

## Rotate / Delete
POST /v1/rotate → `authed()` (hash compare, constant-time) → `cf.getTunnelToken` → 200.
DELETE /v1/claim → `authed()` → delete apps, DNS, tunnel (404 tolerated) → KV delete only if all succeeded (else 502, retry-safe).
To change auth: `authed()`.

## Resolve
GET /v1/resolve?name= → `resolve()` → KV lookup → `{hostname}` / 404.

## OAuth relay
GET /oauth/google/callback → `oauth()` → state must match `<name>.<nonce>` → name validated AND registered in KV → 302 to `https://<name>.${BASE_DOMAIN}/api/sync/oauth/callback?code&state=<nonce>` (URL built from fixed base domain; nonce charset restricted; query via URLSearchParams).
To change nonce rules: regex in `oauth()`.

## Env / secrets
Secrets: `CF_API_TOKEN`, `CF_ACCOUNT_ID`, `CF_ZONE_ID` (wrangler secret). KV binding `REGISTRY`. Vars in wrangler.toml: `BASE_DOMAIN`, `TUNNEL_ORIGIN`, `CLAIMS_PER_IP_PER_DAY`. KV namespace id in wrangler.toml is a placeholder `[NOT IMPLEMENTED until owner creates KV]`.

## Technology Notes
- KV is eventually consistent (up to ~60s across edges) and has no compare-and-set: two simultaneous claims of one name can both pass the 409 check. Mitigations: pending marker, and Cloudflare tunnel name `oo-<name>` + DNS CNAME are unique so the second CF call fails and rolls back. Rate-limit counter is read-modify-write, so bursts can exceed 5/IP slightly.
- Rate limit keyed on `CF-Connecting-IP`; users behind shared NAT share quota; IPv6 users can rotate addresses (not /64-aggregated).
- Rollback is best-effort: if an undo call fails, orphan CF resources remain (no sweeper). Pending marker TTL 120s is KV's minimum-ish granularity (min TTL 60s).
- CF API limits: global ~1200 req/5min per token; claim uses ~9 calls. Free Zero Trust plan caps at 50 users, but Access apps are per-hostname and each claim adds 2 apps; account app limits apply [verify against plan before scale].
- Access `/mcp` bypass app relies on Access matching the most specific path; verify live (Wave 3 smoke).
- installSecret is only stored hashed; lost secret = name unrecoverable except by owner manually via CF dashboard + KV.
- Hardcoded: `session_duration` 720h, tunnel name prefix `oo-`, redirect path `/api/sync/oauth/callback`.
- Tests mock `fetch` and KV in plain Node (not the workerd runtime); runtime differences untested until deploy.

## Change Index
| Thing | Where |
|---|---|
| Name rules / reserved list | names.ts `NAME_RE`, `RESERVED` |
| Claim limit | env `CLAIMS_PER_IP_PER_DAY`, `rateLimited()` |
| Tunnel ingress service | env `TUNNEL_ORIGIN`, `cf.setTunnelIngress()` |
| Access policy shape | `cf.createPolicy()`, `cf.createAccessApp()` |
| Rollback | `claim()` undo stack |
| OAuth redirect | `oauth()` |
| Route / domain | wrangler.toml `routes`, `BASE_DOMAIN` |
| CF API calls | cf.ts |
