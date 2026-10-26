# Registry Worker

Cloudflare Worker at `registry.obsidianoptimizer.uk`: claims `<name>.obsidianoptimizer.uk` for self-hosted installs
(tunnel + DNS + Access) and relays Google OAuth callbacks. API contract: `architecture_plans/SELF_HOST_BUNDLE_PLAN.md`.

## Dev

    cd registry && npm install
    npx vitest run                              # mocked CF API, no network
    npx wrangler deploy --dry-run --outdir dist # build check

## Owner deploy steps (one-time; NOT done by CI)

1. Domain `obsidianoptimizer.uk` must be a zone on your Cloudflare account. Note the **Zone ID** and **Account ID**
   (dashboard, zone Overview page, right sidebar).
2. Enable Zero Trust (free plan) on the account; pick a team name if asked (Access apps need it).
3. Create an API token (My Profile > API Tokens > Create Custom Token) with permissions:
   - Account > Cloudflare Tunnel > Edit
   - Account > Access: Apps and Policies > Edit
   - Zone > DNS > Edit (scope: zone obsidianoptimizer.uk)
4. Create KV: `npx wrangler login` then `npx wrangler kv namespace create REGISTRY`; paste the printed `id`
   into `wrangler.toml` (replace `REPLACE_WITH_KV_NAMESPACE_ID`).
5. Secrets (each prompts for the value):

       npx wrangler secret put CF_API_TOKEN
       npx wrangler secret put CF_ACCOUNT_ID
       npx wrangler secret put CF_ZONE_ID

6. Deploy: `npx wrangler deploy` (custom-domain route `registry.obsidianoptimizer.uk` is created from `wrangler.toml`;
   the hostname must not already have a DNS record).
7. Smoke: `curl -s "https://registry.obsidianoptimizer.uk/v1/resolve?name=nobody"` returns 404;
   claim a test name, then `DELETE /v1/claim` with its installSecret to clean up.
8. Google OAuth client: register ONLY `https://registry.obsidianoptimizer.uk/oauth/google/callback`.
