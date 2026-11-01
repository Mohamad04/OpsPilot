# Phase 4E — Isolated Playwright E2E testing

## Implementation and actual acceptance status

Playwright Test **1.64.1** was installed as a frontend development dependency with
npm and the existing package-lock convention. Supported Chromium **156.0.8078.4**
(Playwright revision 1248) was installed into the ignored project-local browser
directory. Windows installation and real headless Chromium launch succeeded.
No existing browser process, profile, tab or desktop window was inspected.

The 2026-10-28 acceptance run discovered **30 browser tests**:

- **2 passed** against actual local services: hosted Keycloak redirect with PKCE
  S256, and HTTP 401 for an unauthenticated incident request.
- **28 blocked/skipped** because dedicated test credentials were not provided.
- **0 failed browser scenarios** in that partial run. The strict acceptance gate
  deliberately fails when there are blocked scenarios; this is not full acceptance.

There were no environment-provided E2E credentials and no
`frontend/.env.e2e.local`. No personal browser session was reused. No identity or
membership was created, no authenticated incident read/write occurred, and no
development business data was modified. Running-backend currentUserId/currentUserRole
verification, incident workflows, VIEWER/ENGINEER permissions, all six viewports
and recruiter screenshots remain **unverified**. The earlier read-only local
Flyway V1–V12 verification is recorded in the [Phase 4E report](incident-dashboard-phase-4e.md#final-browser-acceptance-follow-up);
it was not repeated or treated as authenticated browser evidence.

## Commands

Run from `frontend` using the documented Node.js 24 baseline. On Windows use
`npm.cmd` if PowerShell blocks the npm shim. Existing local frontend/backend/
Keycloak must already be running; the suite does not launch or restart them.

```powershell
# Existing checkout: install locked dependencies if not already installed.
npm ci
# Supported browser binaries, isolated under this frontend directory.
npm run e2e:install
npm run e2e:list

# Real unauthenticated smoke checks; no test credentials needed.
npm run e2e -- public.spec.ts

# Copy only when there is no existing private configuration to preserve.
if (-not (Test-Path .env.e2e.local)) {
    Copy-Item e2e/.env.example .env.e2e.local
}
# Fill this ignored file locally; never paste passwords/tokens into reports or Git.
npm run e2e
# Strict definition-of-done gate: missing prerequisites also produce exit code 1.
npm run e2e:acceptance
```

On Unix-like systems, use `test -e .env.e2e.local || cp e2e/.env.example .env.e2e.local`
for the copy. The npm commands are identical. `npm ci` installs the new locked
dependency; browser installation is separate and does not require a system-wide
browser or a browser extension.

The runner loads `.env.e2e.local` through Node's built-in environment-file support.
Environment variables already supplied to the process take precedence. Commands
must use the project scripts, not a raw CLI invocation that bypasses the privacy
settings in `tools/playwright.mjs`. All configured origins must be local
localhost/loopback HTTP(S) services and cannot contain embedded credentials.

## Dedicated identities and fixtures

**2026-10-29 recheck:** local Flyway V1–V13 and the membership audit table are
confirmed. The current compiled membership controller predates the running
backend process. `npm run e2e:acceptance` was rerun in isolated Chromium:
**2 passed, 28 blocked, 0 failed scenarios**, strict exit 1. All six credential
fields/four fixture IDs are still absent, and write/capture opt-ins remain off.
No authenticated provisioning or fixture mutation was attempted. All six
responsive widths and screenshots remain blocked. See the [dated acceptance
matrix](incident-dashboard-phase-4e.md#phase-4d5-readiness-and-strict-acceptance-recheck);
earlier execution evidence in this document remains historical.

The subsequent [environment and fixture setup report](e2e-environment-fixture-setup.md)
provides exact manual administrator actions, all existing variables and the
monitoring-driven dataset design. The subsequent [Phase 4D.5 membership API](organisation-membership-api-phase-4d5.md)
now supports authorised OWNER/ADMIN fixture membership setup after each dedicated
identity signs in/provisions locally. No fixtures were automatically created.

`npm run e2e:check-env -- --init` now initialises a missing ignored configuration
file without overwriting it and reports missing/invalid settings by name only.
The private file has been created with blank credentials and opt-ins disabled.
No identity or business fixture was created. Supply credentials locally; do not
paste them into chat. The later checker follow-up passed 380 unit/component tests
and is separate from the 374-test original tooling run documented below.

| Setting | Required for |
| --- | --- |
| `E2E_USERNAME`, `E2E_PASSWORD` | Dedicated operational test identity; OWNER/ADMIN for the complete operator suite. |
| `E2E_ORGANISATION_ID` | Explicit organisation membership/scope for authenticated workspace tests. |
| `E2E_INCIDENT_ID` | Existing disposable incident produced by real alert correlation. |
| `E2E_SECOND_ORGANISATION_ID` | A second authorised demo tenant for switching/cache isolation. |
| `E2E_ASSIGNEE_USER_ID` | Known eligible local member UUID for administrative reassignment. |
| `E2E_ENGINEER_USERNAME`, `E2E_ENGINEER_PASSWORD` | Separate dedicated identity with actual ENGINEER membership in the same test organisation. |
| `E2E_VIEWER_USERNAME`, `E2E_VIEWER_PASSWORD` | Separate dedicated identity with actual VIEWER membership in the same test organisation. |
| `E2E_ALLOW_WRITES=1` | Explicit opt-in to mutate the designated disposable incident. Default 0. |
| `E2E_DATA_IS_DEMO=1` | Explicit acknowledgement that visible content is safe demonstration data. Default 0. |
| `E2E_SCREENSHOTS=1` | Opt-in recruiter captures after the demo-data gate. Default 0. |
| `E2E_REQUIRE_COMPLETE=1` | Optional strict completion gate; always enabled by `e2e:acceptance`. |
| `E2E_BASE_URL`, `E2E_API_URL`, `E2E_KEYCLOAK_URL` | Defaults: local ports 5173, 8080, 8082. |

Do not use personal or Keycloak administrator credentials. Have a maintainer
prepare dedicated identities and actual local memberships using an approved
setup process. The suite does not alter users/roles or offer an authorization
bypass. If suitable identities/memberships cannot be provisioned through an
approved process, obtain administrator approval rather than editing development
records directly. Use the real paginated `GET /api/organisations/{organisationId}/members`
directory and authenticated POST membership route described in the setup guide.

Write fixtures must meet every safeguard:

1. Designated organisation slug starts with `opspilot-e2e-`.
2. Designated incident belongs to that organisation and a real HTTP service whose
   name starts with `opspilot-e2e-` and environment is TEST.
3. Monitoring on that dedicated service is paused before incident write tests,
   preventing scheduler-driven version changes from making a UI concurrency test
   nondeterministic. Preserve its health/alert evidence. The suite does not pause
   monitoring itself or touch unrelated services.
4. Lifecycle/concurrency tests need an ongoing incident. Assignment/self-claim
   fixtures start unassigned. Comments are valid in any incident status.
5. The fixture organisation/incidents/services are explicitly reserved for this
   suite. Status transitions and immutable comments/events are retained as test
   history; no SQL seeding, record deletion or history reset is performed.

For actual second-page UI coverage, provide **more than 20** demo incidents. The
suite will not claim pagination coverage with a single incident, invent rows or
change the application's page-size behavior. Sorting requires at least two
incidents. Evidence checks require a real source-alert reference in the first
bounded timeline page. Obtain missing records through the existing authorised
monitoring/alert workflow, not an invented incident-creation REST endpoint.
Test fixture preparation can therefore be a separate explicit prerequisite.

There is deliberately no automatic teardown that deletes test business records:
incident history and immutable timeline evidence remain available. Any later
cleanup must be restricted to suite-owned fixtures and use approved application
operations. The runner itself closes only the browser/context processes it starts.

## Suite structure and safety

- `playwright.config.ts`: single Chromium project, one worker, zero automatic
  retries, semantic test timeouts and separate test/artifact directories.
- `tools/playwright.mjs`: loads private configuration, selects project-local
  binaries, disables browser-context reuse/remote connection environment settings
  and raw debugging output, then invokes the installed Playwright CLI.
- `e2e/fixtures.ts`: real hosted login with fresh context, in-memory bearer handling
  from that dedicated browser only, trusted local membership/schema checks,
  organisation selection, bounded real API reads and guarded fixture-only writes.
- `public.spec.ts`: unauthenticated real browser/API smoke checks.
- `incidents.spec.ts`: real scoped list/detail/navigation/filter/sort/page/evidence
  and delayed tenant-switching scenarios. CDP network throttling delays genuine
  responses; no API response is mocked or fulfilled by the suite.
- `writes.spec.ts`: confirmed transitions, real two-tab 409, assignment,
  engineer self-claim/release, attributed comments with actual idempotent replay
  and real VIEWER read-only/backend denial scenarios.
- `responsive.spec.ts`: list/detail checks at all six requested widths plus
  guarded clean-data screenshot capture.
- `reporter.ts`: safe result metadata and blocked-versus-passed classification.
- `reporter.unit.ts`: Vitest checks for secret omission and strict gate behavior.

All new browser tests use the existing React UI and actual backend contracts.
Direct API checks supplement UI assertions using the dedicated session's bearer
token kept only in memory. Author identity is never supplied in a comment body.
Write helpers allow only the designated organisation/incident and status,
assignee or comment routes, under explicit opt-in. There is no generic data
cleanup operation. Each test closes its context/pages through Playwright fixtures;
browser teardown affects only processes created by this runner.

### Diagnostic privacy

Traces, HAR, video, automatic screenshots and persisted storageState are disabled:
authenticated traces could contain bearer headers, so they are not safe default
failure artifacts. The runner also sets `PLAYWRIGHT_NO_COPY_PROMPT=1`, verified
against this installed Playwright version, to suppress automatic DOM/error-context
snapshots. Recheck that behavior when upgrading the runner. Failed login closes
the page and throws a fixed safe message without recording the raw exception.

The custom reporter writes only test titles, statuses, prerequisite reasons,
duration, source location, error counts and explicit attachment paths. It does
not serialize raw errors, API headers, auth URLs, step arguments or page content.
After authentication, optional failure screenshots require reviewed demo data
and the dedicated organisation prefix, capture only application main content,
and mask input/textarea contents. Never enable raw Playwright tracing/debugging
or publish local diagnostic directories without a separate security review.

`frontend/test-results/e2e-results.json` records blocked counts distinctly.
`e2e:acceptance` returns nonzero for missing prerequisites; a normal partial run
can exit zero while clearly reporting blocked cases, and is not an acceptance
gate. Browser binaries, private `.env.e2e.local` and result/report directories are
ignored by Git. The template contains blank credential fields only.

## Verification matrix for the actual run

| Scenario | Result | Evidence/blocker |
| --- | --- | --- |
| Chromium installation and isolated launch | Passed | Supported project-local build; real headless browser ran. |
| Unauthenticated hosted login / PKCE S256 | Passed | Browser navigated from OpsPilot to the actual Keycloak form; public client and challenge method checked. |
| Missing authentication rejected | Passed | Actual incident endpoint returned 401. |
| Successful credential login / current local membership contract | Blocked | No operator credentials supplied. |
| Organisation selection and incident navigation | Blocked | No authenticated test context. |
| Server filters, deterministic sorting, pagination | Blocked | No authenticated context/designated dataset; pagination additionally needs >20 records. |
| Details, timeline and alert evidence | Blocked | No authenticated context/designated incident. |
| Lifecycle, stale 409, assignment and comments | Blocked | No authenticated context or write opt-in/fixtures. |
| ENGINEER / VIEWER behavior | Blocked | Corresponding dedicated credentials absent. |
| Tenant switching / delayed cache isolation | Blocked | No authenticated context or second designated tenant. |
| Monitoring/alert/incident navigation | Blocked | No authenticated context. |
| 360, 390, 768, 1024, 1280, 1440px list/details | Blocked | All 12 viewport scenarios stopped before authenticated page inspection. |
| Recruiter screenshots | Blocked | No authenticated clean demo context. No image assets were created. |

The authenticated specs are implemented and type-checked, but their assertions
have **not** been executed successfully against live records. A real failure
must be investigated when prerequisites become available; their mere presence
does not establish acceptance. Browser assertions check overflow, bounds,
semantic controls and keyboard focus; visual composition and real screenshots
still require human review after a successful capture.

The current frontend Vitest run passed **374 tests in 20 files** (the previous
372 plus two reporter unit tests). ESLint, separate TypeScript checks including
E2E sources, production build and `git diff --check` passed after changes. These
are distinct from the 2 passed / 28 blocked browser cases. Backend code was not
changed and its earlier 800-test result was not rerun or relabelled as new evidence.

## Screenshots

With reviewed disposable data, both capture flags and an operational membership,
the screenshot test writes these genuine application-content images at a 1440px
desktop viewport under `docs/assets/screenshots/`:

- `monitoring-dashboard.png`
- `incident-list.png`
- `incident-details-timeline.png`
- `incident-lifecycle-confirmation.png` (dialog content only; confirmation is cancelled).

These are **intended output names, not existing captures**. Header identity and
personal browser content are excluded. Review real files for sensitive data,
composition and clipping before adding relative image links to README. The
current run did not create them, so README contains no invented image links.

## Installation and remaining issues

The first restricted npm/CDN attempts failed with EACCES; authorised installation
then succeeded without changing system browser settings. npm added only the
Playwright Test/Playwright/core dependency chain to the lockfile. The existing
Zod **4.4.3** dependency remained unchanged. `npm audit` reported one high-severity
[Zod constrained-collection advisory](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5)
affecting versions below 4.6.6. No automatic audit fix or unrelated dependency
upgrade was applied. That existing advisory remains a separate maintenance item.

Minimum next action: supply dedicated credentials and designated fixtures in the
ignored environment file, then run `npm run e2e:acceptance`. If accounts/roles or
disposable incidents are unavailable, arrange approved fixture setup first.
Do not share credentials in chat or reuse personal browser state.

No backend authorization/security changes, migrations, commits, merges, pushes
or Phase 4F work are included.

## Files created or modified in this follow-up

Created:

- `frontend/playwright.config.ts`
- `frontend/tsconfig.e2e.json`
- `frontend/tools/playwright.mjs`
- `frontend/e2e/fixtures.ts`
- `frontend/e2e/reporter.ts`
- `frontend/e2e/reporter.unit.ts`
- `frontend/e2e/public.spec.ts`
- `frontend/e2e/incidents.spec.ts`
- `frontend/e2e/writes.spec.ts`
- `frontend/e2e/responsive.spec.ts`
- `frontend/e2e/.env.example`
- `docs/playwright-e2e-phase-4e.md` (this guide).

Modified:

- `frontend/package.json`, `frontend/package-lock.json`: requested dev dependency
  and reproducible install/discovery/partial-run/strict-acceptance commands.
- `frontend/.gitignore`: private diagnostics and local browser binaries.
- `frontend/tsconfig.json`: E2E type-check project reference.
- `frontend/vite.config.ts`: explicit unit/component discovery separated from
  Playwright spec files, including reporter unit tests.
- `README.md`, `docs/incident-dashboard-phase-4e.md`: capabilities, setup, evidence
  and remaining acceptance blockers.

Existing uncommitted Phase 4C–4E implementation is separate and preserved. No
product component or backend source was modified for these testing additions.
