import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The dev port is assigned by the harness via PORT when it needs to avoid a
// collision; nothing in Keyweb depends on a fixed port yet.
const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env;
const port = Number(env?.["PORT"]) || undefined;

export default defineConfig({
  plugins: [react()],
  base: "./",
  ...(port ? { server: { port } } : {}),
});
