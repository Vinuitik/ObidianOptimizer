// Minimal Cloudflare REST client. Every call throws CfError on failure.
export interface CfEnv { CF_API_TOKEN: string; CF_ACCOUNT_ID: string; CF_ZONE_ID: string }

export class CfError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

const API = "https://api.cloudflare.com/client/v4";

async function call<T>(env: CfEnv, method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${env.CF_API_TOKEN}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json: any = null;
  try { json = await res.json(); } catch { /* non-JSON */ }
  if (!res.ok || (json && json.success === false)) {
    const msg = json?.errors?.map((e: any) => e.message).join("; ") || `HTTP ${res.status}`;
    throw new CfError(res.status, `${method} ${path}: ${msg}`);
  }
  return json?.result as T;
}

const acct = (e: CfEnv) => `/accounts/${e.CF_ACCOUNT_ID}`;

export async function createTunnel(e: CfEnv, name: string): Promise<string> {
  const r = await call<{ id: string }>(e, "POST", `${acct(e)}/cfd_tunnel`, { name: `oo-${name}`, config_src: "cloudflare" });
  return r.id;
}
export async function getTunnelToken(e: CfEnv, id: string): Promise<string> {
  return call<string>(e, "GET", `${acct(e)}/cfd_tunnel/${id}/token`);
}
export async function setTunnelIngress(e: CfEnv, id: string, hostname: string, service: string): Promise<void> {
  await call(e, "PUT", `${acct(e)}/cfd_tunnel/${id}/configurations`, {
    config: { ingress: [{ hostname, service }, { service: "http_status:404" }] },
  });
}
export async function deleteTunnel(e: CfEnv, id: string): Promise<void> {
  // Drop stale connectors first or delete is refused.
  try { await call(e, "DELETE", `${acct(e)}/cfd_tunnel/${id}/connections`); } catch { /* none */ }
  await call(e, "DELETE", `${acct(e)}/cfd_tunnel/${id}`);
}
export async function createCname(e: CfEnv, name: string, target: string): Promise<string> {
  const r = await call<{ id: string }>(e, "POST", `/zones/${e.CF_ZONE_ID}/dns_records`, {
    type: "CNAME", name, content: target, proxied: true, ttl: 1,
  });
  return r.id;
}
export async function deleteDns(e: CfEnv, id: string): Promise<void> {
  await call(e, "DELETE", `/zones/${e.CF_ZONE_ID}/dns_records/${id}`);
}
export async function createAccessApp(e: CfEnv, name: string, domain: string): Promise<string> {
  const r = await call<{ id: string }>(e, "POST", `${acct(e)}/access/apps`, {
    name, domain, type: "self_hosted", session_duration: "720h",
  });
  return r.id;
}
export async function createPolicy(
  e: CfEnv, appId: string, kind: "allow-email" | "bypass", email?: string,
): Promise<void> {
  const body = kind === "bypass"
    ? { name: "bypass-mcp", decision: "bypass", include: [{ everyone: {} }], precedence: 1 }
    : { name: "owner-email", decision: "allow", include: [{ email: { email } }], precedence: 1 };
  await call(e, "POST", `${acct(e)}/access/apps/${appId}/policies`, body);
}
export async function deleteAccessApp(e: CfEnv, id: string): Promise<void> {
  await call(e, "DELETE", `${acct(e)}/access/apps/${id}`);
}
