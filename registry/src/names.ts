export const NAME_RE = /^[a-z][a-z0-9-]{2,30}$/;
export const RESERVED = new Set([
  "www", "app", "registry", "api", "admin", "mail", "smtp", "imap", "pop", "ftp", "ns", "ns1", "ns2",
  "dns", "mx", "root", "support", "help", "status", "docs", "blog", "dev", "staging", "test", "demo",
  "static", "cdn", "assets", "auth", "login", "oauth", "cloudflare", "tunnel", "mcp", "obsidianoptimizer",
]);
export function validateName(n: unknown): string | null {
  if (typeof n !== "string" || !NAME_RE.test(n)) return "name must match ^[a-z][a-z0-9-]{2,30}$";
  if (n.endsWith("-")) return "name must not end with '-'";
  if (RESERVED.has(n)) return "name is reserved";
  return null;
}
