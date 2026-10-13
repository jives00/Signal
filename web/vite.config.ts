import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In development the API comes from a companion on this machine (PLAN §7.5): ./gradlew :server:run.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:3013",
      "/health": "http://localhost:3013",
      "/images": "http://localhost:3013",
    },
  },
  test: {
    environment: "node",
  },
});
