import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In development the API comes from a companion on this machine (PLAN §7.5): ./gradlew :server:run.
// SIGNAL_API points it elsewhere, such as the NAS (SIGNAL_API=http://synology:3013): real data, so edits are real.
declare const process: { env: Record<string, string | undefined> }; // no @types/node here
const api = process.env.SIGNAL_API ?? "http://localhost:3013";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": api,
      "/health": api,
      "/images": api,
    },
  },
  test: {
    environment: "node",
  },
});
