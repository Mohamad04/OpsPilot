import { mkdirSync, writeFileSync } from "node:fs";
import type { FullConfig, FullResult, Reporter, Suite, TestCase, TestResult } from "@playwright/test/reporter";

// Do not serialize raw Playwright errors, API headers, auth URLs, step arguments or DOM snapshots.
export default class SafeReporter implements Reporter {
    private results: unknown[] = [];
    private counts: Record<string, number> = {};
    private listing = process.argv.includes("--list");
    onBegin(_config: FullConfig, suite: Suite) {
        console.log(`Discovered ${suite.allTests().length} Playwright tests.`);
        if (this.listing) for (const test of suite.allTests()) console.log(test.title);
    }
    onTestEnd(test: TestCase, result: TestResult) {
        const blocked = test.annotations.find(annotation => annotation.type === "blocked")?.description;
        const status = blocked && result.status === "skipped" ? "blocked" : result.status;
        const record = { title: test.title, status, reason: blocked, durationMs: result.duration,
            location: { file: test.location.file.split(/[\\/]/).at(-1), line: test.location.line },
            errorCount: result.errors.length, attachments: result.attachments.map(item => ({ name: item.name, path: item.path })) };
        this.results.push(record);
        this.counts[status] = (this.counts[status] ?? 0) + 1;
        console.log(`${status.toUpperCase()}: ${test.title}${blocked ? ` (${blocked})` : ""}`);
    }
    onError() { console.error("Playwright infrastructure error. Check browser installation, configuration and local service availability."); }
    async onEnd(result: FullResult) {
        if (this.listing) return;
        const incomplete = process.env.E2E_REQUIRE_COMPLETE === "1" && Boolean(this.counts.blocked);
        mkdirSync("test-results", { recursive: true });
        writeFileSync("test-results/e2e-results.json", JSON.stringify({ status: incomplete ? "failed" : result.status,
            acceptanceComplete: !this.counts.blocked && result.status === "passed", counts: this.counts, results: this.results }, null, 2));
        console.log(`Playwright finished: ${JSON.stringify(this.counts)}. Safe results: test-results/e2e-results.json`);
        if (this.counts.blocked) console.log("Acceptance remains incomplete: blocked scenarios are not passes.");
        if (incomplete) return { status: "failed" as const };
    }
}
