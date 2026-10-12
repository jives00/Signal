// The companion's API. The web admin is only ever served by the companion itself, so paths are
// relative; in development Vite proxies them (vite.config.ts).

export interface Status {
  version: string;
  database: "ok" | "unreachable" | "not configured";
  secretKey: boolean;
}

export async function getStatus(): Promise<Status> {
  const response = await fetch("/api/status");
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as Status;
}

/** One word for the top bar's dot: ok, warn or error. */
export function health(status: Status | null, failed: boolean): "ok" | "warn" | "error" {
  if (failed || !status) return "error";
  if (status.database !== "ok" || !status.secretKey) return "warn";
  return "ok";
}
