import { constants, copyFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { inspectE2EEnvironment } from "../e2e/environment.ts";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const file = resolve(root, ".env.e2e.local");
if (process.argv.slice(2).some(value => value !== "--init")) {
    console.error("Only the optional --init flag is supported. Never pass credentials on the command line.");
    process.exitCode = 2;
} else {
    try {
        if (process.argv.includes("--init") && !existsSync(file)) {
            copyFileSync(resolve(root, "e2e/.env.example"), file, constants.COPYFILE_EXCL);
            console.log("Created private local configuration from the blank template. No credentials or fixtures were provisioned.");
        }
        if (existsSync(file)) process.loadEnvFile(file);
        const result = inspectE2EEnvironment(process.env);
        for (const check of result.checks) console.log(`${check.name}: ${check.status.toUpperCase()} — ${check.message}`);
        console.log(result.configurationComplete ? "Local configuration format is complete; credentials, roles and fixtures still require live verification."
            : "Local configuration is incomplete. No network requests or provisioning were performed.");
        process.exitCode = result.configurationComplete ? 0 : 1;
    } catch {
        console.error("Unable to initialise or read private E2E configuration. Existing files were not overwritten; no values are logged.");
        process.exitCode = 2;
    }
}
