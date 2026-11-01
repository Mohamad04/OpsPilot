import { existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const local = resolve(root, ".env.e2e.local");
if (existsSync(local)) process.loadEnvFile(local);
// Never connect to an existing desktop browser/profile or use system-wide installs.
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve(root, ".playwright-browsers");
// Verified in the installed runner: prevents automatic DOM/error-context files on auth failures.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = "1";
process.env.DEBUG = "";
process.env.PWDEBUG = "";
for (const key of Object.keys(process.env)) {
    if (key.startsWith("PW_TEST_CONNECT_") || key === "PW_TEST_REUSE_CONTEXT") delete process.env[key];
}
const require = createRequire(import.meta.url);
const args = process.argv.slice(2);
if (args.includes("--require-complete")) process.env.E2E_REQUIRE_COMPLETE = "1";
const result = spawnSync(process.execPath, [require.resolve("@playwright/test/cli"), ...args.filter(value => value !== "--require-complete")], {
    cwd: root, env: process.env, stdio: "inherit",
});
if (result.error) throw new Error("Unable to launch project-local Playwright.", { cause: result.error });
process.exitCode = result.status ?? 1;
