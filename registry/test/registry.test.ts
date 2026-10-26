import { describe, it, expect, beforeEach, vi } from "vitest";
import worker, { sha256Hex } from "../src/index";

class FakeKV {
  m = new Map<string, string>();
  async get(k: string) { return this.m.get(k) ?? null; }
  async put(k: string, v: string) { this.m.set(k, v); }
  async delete(k: string) { this.m.delete(k); }
}

let kv: FakeKV;
let calls: string[];
let failOn: string | null;
const mkEnv = (extra: any = {}) => ({
  REGISTRY: kv as any, CF_API_TOKEN: "t", CF_ACCOUNT_ID: "acc", CF_ZONE_ID: "zone",
  BASE_DOMAIN: "obsidianoptimizer.uk", TUNNEL_ORIGIN: "http://frontend:8081", ...extra,
});

beforeEach(() => {
  kv = new FakeKV(); calls = []; failOn = null;
  let n = 0;
  vi.stubGlobal("fetch", async (url: string, init: any) => {
    const path = new URL(url).pathname.replace("/client/v4", "");
    const sig = `${init.method} ${path}`;
    calls.push(sig);
    const ok = (result: any) => new Response(JSON.stringify({ success: true, result }), { status: 200 });
    if (failOn && sig.includes(failOn) && init.method !== "DELETE") {
      return new Response(JSON.stringify({ success: false, errors: [{ message: "boom" }] }), { status: 500 });
    }
    if (init.method === "GET" && path.endsWith("/token")) return ok("TOKEN123");
    return ok({ id: `id${++n}` });
  });
});

const call = (method: string, path: string, body?: any, ip = "1.1.1.1") =>
  worker.fetch(new Request("https://registry.obsidianoptimizer.uk" + path, {
    method, headers: { "CF-Connecting-IP": ip }, body: body ? JSON.stringify(body) : undefined,
  }), mkEnv());

const claim = (name = "alice", ip?: string) => call("POST", "/v1/claim", { name, email: "a@b.com" }, ip);

describe("claim", () => {
  it("happy path creates all resources and stores hashed secret", async () => {
    const r = await claim();
    expect(r.status).toBe(201);
    const b: any = await r.json();
    expect(b.hostname).toBe("alice.obsidianoptimizer.uk");
    expect(b.tunnelToken).toBe("TOKEN123");
    expect(b.installSecret).toMatch(/^[0-9a-f]{64}$/);
    const stored = JSON.parse(kv.m.get("name:alice")!);
    expect(stored.secretHash).toBe(await sha256Hex(b.installSecret));
    expect(JSON.stringify(stored)).not.toContain(b.installSecret);
    expect(calls.filter((c) => c.includes("/access/apps") && !c.includes("policies")).length).toBe(2);
    expect(calls.filter((c) => c.includes("/policies")).length).toBe(2);
    expect(calls).toContain("POST /zones/zone/dns_records");
  });
  it("409 when taken", async () => {
    await claim();
    expect((await claim()).status).toBe(409);
  });
  it.each(["ab", "Alice", "1abc", "www", "registry", "a".repeat(32), "bad_name", "trail-"])("rejects name %s", async (n) => {
    expect((await claim(n)).status).toBe(400);
    expect(calls.length).toBe(0);
  });
  it("rejects bad email", async () => {
    expect((await call("POST", "/v1/claim", { name: "alice", email: "nope" })).status).toBe(400);
  });
  it("rate limits 5 claims per IP per day", async () => {
    for (let i = 0; i < 5; i++) expect((await claim(`user${i}x`, "9.9.9.9")).status).toBe(201);
    expect((await claim("another", "9.9.9.9")).status).toBe(429);
    expect((await claim("another", "8.8.8.8")).status).toBe(201);
  });
  it.each(["/dns_records", "/access/apps", "/configurations"])("rolls back when step %s fails", async (step) => {
    failOn = step;
    const r = await claim();
    expect(r.status).toBe(502);
    expect(kv.m.has("name:alice")).toBe(false);
    expect(calls.some((c) => c.startsWith("DELETE") && c.includes("cfd_tunnel/id1"))).toBe(true);
    failOn = null;
    expect((await claim()).status).toBe(201);
  });
  it("rollback deletes dns+apps created before a later failure", async () => {
    failOn = "/policies";
    await claim();
    expect(calls.some((c) => c.startsWith("DELETE /zones/zone/dns_records"))).toBe(true);
    expect(calls.some((c) => c.startsWith("DELETE /accounts/acc/access/apps"))).toBe(true);
  });
});

describe("rotate / delete / resolve", () => {
  it("rotate works with right secret, 403 with wrong, 404 unknown", async () => {
    const b: any = await (await claim()).json();
    const ok = await call("POST", "/v1/rotate", { name: "alice", installSecret: b.installSecret });
    expect(ok.status).toBe(200);
    expect(((await ok.json()) as any).tunnelToken).toBe("TOKEN123");
    expect((await call("POST", "/v1/rotate", { name: "alice", installSecret: "0".repeat(64) })).status).toBe(403);
    expect((await call("POST", "/v1/rotate", { name: "nobody", installSecret: "x" })).status).toBe(404);
  });
  it("delete requires secret and removes everything", async () => {
    const b: any = await (await claim()).json();
    expect((await call("DELETE", "/v1/claim", { name: "alice", installSecret: "bad" })).status).toBe(403);
    expect(kv.m.has("name:alice")).toBe(true);
    calls.length = 0;
    expect((await call("DELETE", "/v1/claim", { name: "alice", installSecret: b.installSecret })).status).toBe(204);
    expect(kv.m.has("name:alice")).toBe(false);
    expect(calls.filter((c) => c.startsWith("DELETE")).length).toBeGreaterThanOrEqual(4);
  });
  it("resolve", async () => {
    await claim();
    const r = await call("GET", "/v1/resolve?name=alice");
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ hostname: "alice.obsidianoptimizer.uk" });
    expect((await call("GET", "/v1/resolve?name=ghost")).status).toBe(404);
    expect((await call("GET", "/v1/resolve?name=")).status).toBe(404);
  });
});

describe("oauth relay", () => {
  beforeEach(async () => { await claim(); });
  it("redirects to registered name", async () => {
    const r = await call("GET", "/oauth/google/callback?code=C&state=alice.abcdefgh12");
    expect(r.status).toBe(302);
    expect(r.headers.get("Location")).toBe(
      "https://alice.obsidianoptimizer.uk/api/sync/oauth/callback?code=C&state=abcdefgh12");
  });
  it.each([
    "evil.com.abcdefgh12", "alice@evil.com.abcdefgh12", "alice.evil.com/x", "//evil.com.abcdefgh12",
    "ghost.abcdefgh12", "alice.abc", "alice.abcdefgh12/../x", "alice.abcdefgh12%0d%0aX:y", "alice",
  ])("rejects hostile state %s", async (s) => {
    const r = await call("GET", `/oauth/google/callback?code=C&state=${encodeURIComponent(s)}`);
    expect(r.status).toBe(400);
    expect(r.headers.get("Location")).toBeNull();
  });
  it("code cannot break out of the target", async () => {
    const r = await call("GET", "/oauth/google/callback?code=" + encodeURIComponent("x&redirect=//evil.com#") + "&state=alice.abcdefgh12");
    const loc = new URL(r.headers.get("Location")!);
    expect(loc.hostname).toBe("alice.obsidianoptimizer.uk");
    expect(loc.searchParams.get("code")).toBe("x&redirect=//evil.com#");
  });
  it("requires code", async () => {
    expect((await call("GET", "/oauth/google/callback?state=alice.abcdefgh12")).status).toBe(400);
  });
});
