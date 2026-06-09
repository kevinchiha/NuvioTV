// Bundle the Fastify server into a single self-contained file.
// All runtime deps (fastify, @fastify/static, pg, @supabase/supabase-js) AND the workspace
// package @kevbox-admin/core are INLINED, so the VPS needs no node_modules / npm ci, and the
// private "@kevbox-admin/core": "*" dep never has to resolve at runtime (it can't, off-monorepo).
import { build } from "esbuild";

await build({
  entryPoints: ["src/server/index.ts"],
  outfile: "dist/server.js",
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node20",
  // ESM output bundling CJS deps (pg, fastify) needs require()/__filename/__dirname shims:
  banner: {
    js: [
      "import { createRequire as __cr } from 'module';",
      "import { fileURLToPath as __ftu } from 'url';",
      "import { dirname as __dn } from 'path';",
      "const require = __cr(import.meta.url);",
      "const __filename = __ftu(import.meta.url);",
      "const __dirname = __dn(__filename);",
    ].join("\n"),
  },
});
