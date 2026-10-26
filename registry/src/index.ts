import * as cf from "./cf";
import { validateName } from "./names";

export interface Env extends cf.CfEnv {
  REGISTRY: KVNamespace;
  BASE_DOMAIN: string;
  TUNNEL_ORIGIN: string;
  CLAIMS_PER_IP_PER_DAY?: string;
}

interface Rec {
  hostname: string; tunnelId: string; dnsId: string; appIds: string[];
  secretHash: string; email: string; createdAt: string;
}

const json = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });

export async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function randomHex(bytes: number): string {
  const a = crypto.getRandomValues(new Uint8Array(bytes));
  return [...a].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}
const key = (name: string) => `name:${name}`;
const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,255}\.[^\s@]{2,}$/;

async function readBody(req: Request): Promise<any | null> {
  try { const b = await req.json(); return b && typeof b === "object" ? b : null; } catch { return null; }
}

async function getRec(env: Env, name: string): Promise<Rec | null> {
  const raw = await env.REGISTRY.get(key(name));
  if (!raw) return null;
  const r = JSON.parse(raw);
  return r.pending ? null : (r as Rec);
}

async function rateLimited(env: Env, ip: string): Promise<boolean> {
  const limit = parseInt(env.CLAIMS_PER_IP_PER_DAY ?? "5", 10);
  const k = `rl:${ip}:${new Date().toISOString().slice(0, 10)}`;
  const n = parseInt((await env.REGISTRY.get(k)) ?? "0", 10);
  if (n >= limit) return true;
  await env.REGISTRY.put(k, String(n + 1), { expirationTtl: 86400 });
  return false;
}

async function claim(req: Request, env: Env): Promise<Response> {
  const body = await readBody(req);
  if (!body) return json(400, { error: "invalid JSON" });
  const { name, email } = body;
  const bad = validateName(name);
  if (bad) return json(400, { error: bad });
  if (typeof email !== "string" || !EMAIL_RE.test(email)) return json(400, { error: "invalid email" });

  const ip = req.headers.get("CF-Connecting-IP") ?? "unknown";
  if (await rateLimited(env, ip)) return json(429, { error: "claim rate limit exceeded (per IP per day)" });

  if (await env.REGISTRY.get(key(name))) return json(409, { error: "name taken" });
  // Reserve first (KV is not atomic; narrows the race window, see FLOWS.md).
  await env.REGISTRY.put(key(name), JSON.stringify({ pending: true }), { expirationTtl: 120 });

  const hostname = `${name}.${env.BASE_DOMAIN}`;
  const undo: Array<() => Promise<void>> = [];
  try {
    const tunnelId = await cf.createTunnel(env, name);
    undo.push(() => cf.deleteTunnel(env, tunnelId));
    await cf.setTunnelIngress(env, tunnelId, hostname, env.TUNNEL_ORIGIN);
    const dnsId = await cf.createCname(env, hostname, `${tunnelId}.cfargotunnel.com`);
    undo.push(() => cf.deleteDns(env, dnsId));
    const mainApp = await cf.createAccessApp(env, `oo-${name}`, hostname);
    undo.push(() => cf.deleteAccessApp(env, mainApp));
    await cf.createPolicy(env, mainApp, "allow-email", email);
    const mcpApp = await cf.createAccessApp(env, `oo-${name}-mcp`, `${hostname}/mcp`);
    undo.push(() => cf.deleteAccessApp(env, mcpApp));
    await cf.createPolicy(env, mcpApp, "bypass");
    const tunnelToken = await cf.getTunnelToken(env, tunnelId);

    const installSecret = randomHex(32);
    const rec: Rec = {
      hostname, tunnelId, dnsId, appIds: [mainApp, mcpApp], email,
      secretHash: await sha256Hex(installSecret), createdAt: new Date().toISOString(),
    };
    await env.REGISTRY.put(key(name), JSON.stringify(rec));
    return json(201, { name, hostname, tunnelToken, installSecret });
  } catch (e) {
    for (const u of undo.reverse()) { try { await u(); } catch { /* best effort */ } }
    try { await env.REGISTRY.delete(key(name)); } catch { /* ignore */ }
    return json(502, { error: "provisioning failed; rolled back" });
  }
}

async function authed(env: Env, body: any): Promise<{ rec: Rec } | Response> {
  if (!body) return json(400, { error: "invalid JSON" });
  const { name, installSecret } = body;
  if (validateName(name) || typeof installSecret !== "string") return json(400, { error: "invalid request" });
  const rec = await getRec(env, name);
  if (!rec) return json(404, { error: "not found" });
  if (!safeEqual(rec.secretHash, await sha256Hex(installSecret))) return json(403, { error: "forbidden" });
  return { rec };
}

async function rotate(req: Request, env: Env): Promise<Response> {
  const a = await authed(env, await readBody(req));
  if (a instanceof Response) return a;
  try { return json(200, { tunnelToken: await cf.getTunnelToken(env, a.rec.tunnelId) }); }
  catch { return json(502, { error: "cloudflare error" }); }
}

async function remove(req: Request, env: Env): Promise<Response> {
  const body = await readBody(req);
  const a = await authed(env, body);
  if (a instanceof Response) return a;
  const { rec } = a;
  // KV record only dropped if all CF deletes succeed (404 = already gone), so retry is possible.
  const steps = [
    ...rec.appIds.map((id) => () => cf.deleteAccessApp(env, id)),
    () => cf.deleteDns(env, rec.dnsId),
    () => cf.deleteTunnel(env, rec.tunnelId),
  ];
  let failed = false;
  for (const s of steps) {
    try { await s(); } catch (e) { if (!(e instanceof cf.CfError && e.status === 404)) failed = true; }
  }
  if (failed) return json(502, { error: "cloudflare delete incomplete; retry" });
  await env.REGISTRY.delete(key(body.name));
  return json(204);
}

async function resolve(url: URL, env: Env): Promise<Response> {
  const name = url.searchParams.get("name");
  if (validateName(name)) return json(404, { error: "not found" });
  const rec = await getRec(env, name!);
  return rec ? json(200, { hostname: rec.hostname }) : json(404, { error: "not found" });
}

// state = "<name>.<nonce>". Redirect target is built ONLY from a registered name + fixed base domain.
async function oauth(url: URL, env: Env): Promise<Response> {
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  if (!code || !state) return json(400, { error: "missing code/state" });
  const m = /^([a-z][a-z0-9-]{2,30})\.([A-Za-z0-9_-]{8,128})$/.exec(state);
  if (!m) return json(400, { error: "invalid state" });
  const [, name, nonce] = m;
  if (validateName(name) || !(await getRec(env, name))) return json(400, { error: "invalid state" });
  const target = new URL(`https://${name}.${env.BASE_DOMAIN}/api/sync/oauth/callback`);
  target.searchParams.set("code", code);
  target.searchParams.set("state", nonce);
  return new Response(null, { status: 302, headers: { Location: target.toString() } });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      if (p === "/v1/claim" && req.method === "POST") return await claim(req, env);
      if (p === "/v1/claim" && req.method === "DELETE") return await remove(req, env);
      if (p === "/v1/rotate" && req.method === "POST") return await rotate(req, env);
      if (p === "/v1/resolve" && req.method === "GET") return await resolve(url, env);
      if (p === "/oauth/google/callback" && req.method === "GET") return await oauth(url, env);
    } catch { return json(500, { error: "internal error" }); }
    return json(404, { error: "not found" });
  },
};
