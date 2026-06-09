import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    include: ["test/**/*.test.{ts,tsx}"],
    environment: "node",
    environmentMatchGlobs: [["test/web/**", "jsdom"]],
    setupFiles: ["test/web/setup.ts"],
    fileParallelism: false, // server tests share one test DB; rollback gives isolation
  },
});
