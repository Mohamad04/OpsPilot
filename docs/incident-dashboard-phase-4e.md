# Phase 4E — React Incident Management Workspace

## Scope and status

Implemented on `feature/phase4-incident-management`, verified on 2026-10-28.
The existing Phase 4C/4D backend changes were already uncommitted at the start of
this task and were preserved. This increment adds the incident frontend and a
minimal, additive organisation-response extension required for permission-aware
controls. The original implementation performed no commit, push, migration,
dependency installation or development database modification. A subsequent
[Playwright follow-up](playwright-e2e-phase-4e.md) installs isolated browser-test
tooling; its evidence and credential blockers are recorded separately below.
Phase 4F push updates remain out of scope.

Automated acceptance is green. Live authenticated browser integration, visual
acceptance and screenshots remain outstanding; this report does not mark those
checks as passed.

## Routes and presentation

- `/incidents`: organisation context, severity/status/service filters, explicit
  Apply/Clear, creation-time sort, manual refresh and server-paginated registry.
- `/incidents?serviceId=<uuid>`: initially scoped service filter. Switching
  organisation resets filters, pagination and the initial service selection.
- `/incidents/:incidentId`: title/description, severity and lifecycle status,
  service link, assignee reference, real creation/acknowledgement/resolution
  timestamps, version, timeline, comments and operational controls.

The shared navigation exposes Incidents on desktop, rail and mobile drawer.
Only the new routes are lazy-loaded. Existing authentication, provisioning,
providers, monitored-service/alert APIs and routes remain in place. The alert
details notice now links to incidents instead of claiming timelines do not exist.

The registry distinguishes ongoing incidents from historical RESOLVED/CLOSED
records using text in addition to badges. There is no fabricated updatedAt,
incident statistic, incident timeline synthesis or full linked-alert collection.
The backend has no such fields in IncidentResponse. Related alerts are available
through the timeline's actual sourceAlertId references. User identities without
display-name data are shown as local UUIDs, with "You" for the current local user.

## Verified contracts

All incident paths use `/api/organisations/{organisationId}/incidents`:

| Method | Suffix | Use |
| --- | --- | --- |
| GET | empty | List with status, severity, serviceId, page, size and sort. |
| GET | `/{incidentId}` | Current incident representation/version. |
| GET | `/{incidentId}/events` | Chronological paginated immutable timeline. |
| PATCH | `/{incidentId}/status` | `{status, expectedVersion}`. |
| PATCH | `/{incidentId}/assignee` | `{assigneeUserId: UUID or null, expectedVersion}`. |
| POST | `/{incidentId}/comments` | `{content}`, UUID `Idempotency-Key` header. |

Lists use page 0, size 20, and supported sorts `createdAt,desc` / `createdAt,asc`.
The backend provides deterministic ID tie-breaking. Timeline pages also have 20
events and retain server chronological ordering. A latest-events shortcut jumps
directly to the last page. Neither collection is fetched unboundedly or paginated
locally. Services/names come from the existing authenticated service-list client.

`Incident.ts` defines Zod-validated DTOs matching the existing Java records:

- Incident: id, organisationId, nullable monitoredServiceId, title, description,
  severity, status, nullable assignedUserId, createdAt, nullable acknowledgedAt
  and resolvedAt, version.
- Event: id, organisationId, incidentId, eventType, nullable actorUserId and
  sourceAlertId, occurredAt, details, nullable previousStatus/newStatus and
  previousAssigneeUserId/newAssigneeUserId. Missing legacy assignment metadata
  normalises to null. Unknown future event types receive a safe generic label.
- Both page responses: content, page, size, totalElements, totalPages, first, last.

UUIDs, timestamps, safe integer versions/counts, lifecycle timestamp consistency,
page bounds and metadata are checked. Returned organisation/incident IDs are
verified, as are list filters, page identifiers and duplicate result IDs.
Unrecognised diagnostic fields are stripped. Invalid/foreign responses fail
closed with a controlled error rather than displaying their content.

## Necessary organisation identity contract

Inspection found that the existing organisation DTO exposed only id, name, slug,
status and createdAt; the frontend had neither the local user ID nor authoritative
organisation role. The provisioning call did not retain its local user response,
and Keycloak realm roles cannot supply organisation permissions. This was a
genuine integration blocker for safe role-specific action visibility.

The existing organisation list/detail/create responses now additionally return:

```json
{
  "currentUserId": "33333333-3333-4333-8333-333333333333",
  "currentUserRole": "ENGINEER"
}
```

This is an illustrative fragment, not a fixture or complete response. Both fields
are derived from the authenticated local membership already used by the service,
never from frontend input or JWT realm-role claims. No new endpoint, user
directory, grant, authorization rule or persistence model was added. The UUID
identifies only the requesting user. Frontend properties are optional for older
deployments, with schema validation and read-only fallback. Deploy the current
backend code to enable writes against an older running API; this task did not
restart the existing application.

## Permissions and workflows

| Role | Lifecycle | Assignment on ongoing incidents | Human comments |
| --- | --- | --- | --- |
| OWNER / ADMIN | Allowed transitions | Assign/reassign eligible member, self-assign, unassign | Allowed |
| ENGINEER | Allowed transitions | Claim unassigned incident, release own assignment | Allowed |
| VIEWER | Read-only | Read-only | Read-only |
| Unknown/invalid context | Read-only | Read-only | Read-only |

The backend remains authoritative for every write, eligibility, membership and
resource lookup. There is no supported member directory. Admins can enter a
**known local OpsPilot user UUID**, with explicit explanation and validation;
the server checks membership and eligibility. This is not a Keycloak subject.
No guessed member lists or email-based identity mapping are introduced.

Lifecycle actions follow the actual backend matrix:

| Current | Offered next states |
| --- | --- |
| OPEN | ACKNOWLEDGED |
| ACKNOWLEDGED | INVESTIGATING, RESOLVED |
| INVESTIGATING | MITIGATED, RESOLVED |
| MITIGATED | INVESTIGATING, RESOLVED |
| RESOLVED | CLOSED, INVESTIGATING |
| CLOSED | INVESTIGATING |

Every lifecycle/assignment change requires confirmation and the version captured
when the dialog opened. If details change during confirmation, confirm is
disabled. Significant resolution/reopening constraints are explained. 409
messages distinguish active linked alerts, ongoing-incident reopening conflicts,
and stale incident/membership versions. Details/events/lists refresh, but a stale
mutation is **never automatically replayed**. A fresh explicit confirmation is
required. 403 disables write controls and revalidates organisation membership;
404 hides unavailable scoped resources. 401 retains the existing auth-client
session handling. Unrecognised backend diagnostic messages are not exposed.

## Comments and timeline

Comments are React-rendered plain text, never executable HTML. React Hook Form and
Zod reject blank, NUL-containing or over-4000-character input. The textarea has a
4000 UTF-16 code-unit maximum, character count and accessible validation errors.
Comments are allowed in every status, including CLOSED. No edit/delete controls
are provided. Pending guards prevent rapid duplicate submits.

Each logical submission receives a cryptographic UUID retry key. An uncertain
failure retains the same key for a retry of the same trimmed content, including
navigation away/back. New content or a new submission after success receives a
new key. Discarding a draft abandons the previous logical submission. Backend
receipt semantics and historical immutability are unchanged.

Drafts and retry keys are stored **only in the in-memory QueryClient**, scoped by
organisation, incident and local user. There is a beforeunload warning for drafts.
They survive SPA navigation in the tab, not reload/closing/logout. A late response
cannot erase a newer cached draft. The browser does not persist JWTs or drafts to
localStorage/sessionStorage/cookies. Backend author identity comes from JWT;
the frontend does not send an author ID in the request body.

The chronological, paginated timeline labels CREATED, ALERT_LINKED,
ALERT_RESOLVED, STATUS_CHANGED, ASSIGNMENT_CHANGED and COMMENT_ADDED. Human comments
have an actor; legacy COMMENT_ADDED recovery notes without a human actor are
labelled historical system notes. System events never invent a human identity.
Assignment metadata shows previous/new assignee, and status metadata shows the
actual transition. Unknown future types retain safe text and an explicit label.

## Query isolation and refresh

- List: `["incidents", org, "list", filters]`.
- Detail: `["incidents", org, "detail", incidentId]`.
- Timeline: `["incidents", org, "events", incidentId, {page, size: 20}]`.

Components remount on organisation/incident changes. Queries consume AbortSignal,
override inherited keepPreviousData, require a new mount fetch before exposing
cached content, and hide data after an error. Delayed requests for the old tenant
cannot populate the active tenant's view. Responses are scope-validated too.
After lifecycle/assignment success, only the original organisation's incident
lists, current detail and timeline are invalidated. Comment success invalidates
the original incident timeline. No optimistic incident writes or polling are
used. Manual refresh and standard invalidation are Phase 4E's update mechanism.

## Responsive design and accessibility

Existing CSS tokens, cards, buttons and form conventions are reused. This
repository uses custom CSS; Tailwind/shadcn are not installed and were not added.
Incident content uses container queries: one column/mobile cards by default,
two filter columns at 34rem content width, a full registry table/four filter
columns and two-column details at 52rem. The shared shell's breakpoints remain
unchanged. Long titles, UUIDs and comments wrap; grids use minmax(0, 1fr).

Semantic headings, table headers/caption, ordered timeline, labels, aria-invalid,
aria-describedby, status/error announcements and existing visible focus styles
support keyboard/screen-reader use. Native confirmation dialogs provide browser
modal behavior, Cancel autofocus, Escape and trigger-focus restoration. Touch
targets follow the existing 44px token. No new animations are introduced.

Automated tests exercise keyboard interactions and the mobile navigation drawer.
jsdom cannot establish real CSS overflow, text zoom, dialog focus trapping,
screen-reader behavior or screenshot fidelity. Six live viewport checks
(320, 390, 768, 977, 1280, 1440px) were **not executed**. Automatic approval review
rejected the computer-use window inventory because it could expose unrelated
private desktop metadata. No screenshot was captured, and no alternative capture
was used to bypass that rejection. Playwright is not configured in this repository.

## Validation evidence

Executed against this working tree:

| Check | Actual result |
| --- | --- |
| Focused incident API tests | 41 passed. |
| Focused incident pages/controls plus existing alerting | 71 passed. |
| `npm.cmd run test:run` | 372 passed, 0 failed, 0 skipped; 19 files. |
| `npm.cmd run lint` | Passed. |
| `.\node_modules\.bin\tsc.cmd -b --pretty false` | Passed. |
| `npm.cmd run build` | Passed; new incident routes split into separate bundles. |
| Backend full cached-Maven regression suite | 800 passed, 0 failures, 0 errors, 0 skipped; PostgreSQL Testcontainers and real signed-JWT HTTP tests included. |
| Backend cached-Maven `-DskipTests package` | Passed, Maven exit code 0; separate packaging step after full tests. |
| `git diff --check` and new-file whitespace checks | Passed. |
| README/report relative links, Markdown fences and JSON examples | Passed. |

The wrapper `.\mvnw.cmd test` was tried first and failed before Maven with the
existing PowerShell null-array error. A first restricted cached-Maven invocation
could not read a cached dependency JAR; the authorised cache/Docker run succeeded.
The wrapper was not changed and dependencies were not installed. Maven logged
800 successful tests and BUILD SUCCESS; the PowerShell redirected test launcher
reported exit 1 after native stderr warnings. Surefire reports are the test
evidence, rather than a claim that this launcher exit code was zero. Packaging
explicitly preserved the native Maven exit code (0). Testcontainer shutdowns
also produced Hikari closed-connection warnings; no failed tests resulted.

Backend command, from `backend`, using the pre-existing local cache:

```powershell
$mavenExe = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $mavenExe '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
& $mavenExe '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o -DskipTests package
```

Frontend tests use realistic DTOs with mocked HTTP responses and real auth API
client/query/provider/router code. They cover schema/scope validation, filters,
pagination, list/details/timeline states, all lifecycle action sets, role
visibility, confirmed writes, stale/conflicting versions, admin and engineer
assignment, comments/keys/drafts, safe untrusted text, revocation, old caches and
late tenant responses. A regression also verifies uppercase assignee UUIDs are
normalised before comparing the backend's canonical response. They are not a
live Keycloak-to-browser E2E claim.
Backend coverage verifies the two new identity fields on list/detail/create,
different local roles for the same signed JWT across tenants, and continued
denial of writes by local VIEWER despite an OWNER realm-role claim.

## Manual acceptance still required

1. Run the current backend/frontend using the [root setup guide](../README.md#getting-started).
   Preserve existing data; use disposable organisation/service/incident fixtures.
   Normal application startup, not this frontend task, is responsible for any
   still-pending existing migrations. Confirm organisation responses contain the
   current local user ID and role.
2. Sign in and open Incidents. Verify status/severity/service filters, sort, paging,
   clear and refresh; open an incident and its actual alert/service links.
3. With known OWNER/ADMIN, ENGINEER and VIEWER memberships, check the documented
   control policy, known-UUID assignment/reassignment/unassignment and self-claim.
   Do not change production records solely to exercise this checklist.
4. Use a disposable incident for acknowledge/investigate/mitigate/resolve/reopen.
   Confirm significant actions; exercise stale versions using two tabs and active
   linked-alert/reopening conflicts. Rejected actions must create no UI success.
5. Add plain text, multiline and long comments. Exercise a failed request/retry,
   same-key deduplication, new submissions, draft navigation, timeline pagination
   and latest events. Check human authors versus historical system notes.
6. Switch between two authorised organisations while reads are delayed; confirm
   no previous title, event, filter, feedback or draft appears under the new tenant.
   Also check a foreign incident URL and membership revocation.
7. At 360, 390, 768, 1024, 1280 and 1440 CSS pixels, verify no page overflow, cards
   versus table, side panel stacking, long comments/titles, keyboard focus,
   Escape/Cancel, native dialog focus trapping and 200% text zoom.
8. Exercise loading, zero results, HTTP 400/401/403/404/409 and transport errors.
   Preserve evidence with genuine screenshots only when a safe isolated window
   and representative disposable data are available.

## Files in this increment

Created under `frontend/src/`:

- `types/Incident.ts`: validated contracts and lifecycle labels/matrix.
- `api/incidentApi.ts`: authenticated scoped reads/writes and safe errors.
- `hooks/useIncidents.ts`: tenant-scoped queries, invalidation and write guards.
- `pages/IncidentsPage.tsx`, `pages/IncidentDetailsPage.tsx`: routes/workspaces.
- `components/IncidentPresentation.tsx`, `IncidentTimeline.tsx`,
  `IncidentActions.tsx`, `IncidentComments.tsx`: registry, audit and interactions.
- `styles/incidents.css`: responsive styles using existing tokens.
- `test/incidentFixtures.ts`, `incidentApi.test.ts`, `IncidentControls.test.tsx`,
  `Incidents.test.tsx`: realistic contracts and focused regressions.

Modified:

- `frontend/src/App.tsx`, `components/Navigation.tsx`, `components/Icon.tsx`:
  lazy routes and existing navigation/icon integration.
- `frontend/src/pages/AlertDetailsPage.tsx`, `test/Alerting.test.tsx`: replace the
  obsolete incident-timeline notice and validate the new link.
- `frontend/src/types/Organisation.ts`: optional typed local membership context.
- `backend/src/main/java/com/opspilot/backend/web/dto/OrganisationResponse.java`,
  `application/OrganisationMembershipService.java`: additive authenticated
  membership response, with unchanged authorization.
- Backend `OrganisationResponseServiceTest.java`, `OrganisationControllerTest.java`,
  `TenantIsolationIntegrationTest.java`: DTO and signed-JWT membership regression.
- `README.md` and this report: capabilities, routes, permissions, roadmap,
  reproducible verification and limitations.

The pre-existing uncommitted backend, migrations and phase reports are not all
new Phase 4E files. No historical migration was edited by this increment.

## Remaining limitations

No user-directory endpoint means admin assignment requires a known local UUID;
user display names beyond the current user are unavailable. Incident details do
not expose a full linked-alert collection or updatedAt. Live permission changes
are enforced by the backend on every request; the frontend learns changes on
refresh or a rejected mutation, not through push. Drafts/keys are tab-memory only;
reload discards their retry guarantee. Timeline history is paginated, not streamed.
Screenshots and live six-width/Keycloak acceptance remain pending. No SSE,
notifications, AI, incident editing/deleting or unrelated UI redesign was added.

## Final browser acceptance follow-up

This historical follow-up preceded Playwright installation. The newer
[Playwright acceptance follow-up](#playwright-acceptance-infrastructure-follow-up)
supersedes its tooling-unavailable blocker; authenticated acceptance is still open.

### Evidence and boundaries — 2026-10-28

The final acceptance request explicitly prohibited enumerating existing desktop
windows/browser tab titles and requested a project-local isolated Playwright
context instead. No window/tab inventory, desktop inspection, personal-session
capture or authentication-token extraction was performed in this follow-up.

Inspection of `frontend/package.json`, the local installed packages and repository
files found no `playwright`, `playwright-core` or `@playwright/test` installation,
Playwright configuration or browser-test script. No dedicated isolated browser
automation tool was available. The requested fallback was therefore used:
**manual acceptance checklist, with no browser pass or screenshot claim**.
No packages or browsers were installed, and no alternate desktop automation was
used to bypass the restriction. No screenshot assets or image links were invented.

| Check | Result | Evidence and limits |
| --- | --- | --- |
| Branch and worktree | Confirmed | `feature/phase4-incident-management`; existing uncommitted implementation preserved. |
| Frontend HTTP availability | Passed | GET `http://localhost:5173` returned 200. This does not establish React rendering or login success. |
| Protected backend HTTP availability | Passed | Unauthenticated GET `http://localhost:8080/api/organisations` returned 401. This establishes reachability and rejection of that unauthenticated request, not successful JWT validation or the loaded DTO version. |
| Keycloak discovery | Passed | GET `http://localhost:8082/realms/opspilot/.well-known/openid-configuration` returned 200. No login was attempted. |
| Local infrastructure | Confirmed running | Compose project `opspilot`: PostgreSQL `postgres:18.4-alpine` healthy; Keycloak `26.7.1` running. |
| Local Flyway history | Passed | Read-only query in the Compose application database returned versions 1–12, all `success=true`, including V12 assignment/comments. |
| Running backend Phase 4E DTO compatibility | Not verified | An authenticated organisation response containing currentUserId/currentUserRole was not available to this follow-up. Source code and earlier regression results do not prove the running process uses that code. |
| Live browser workflows | Could not run | Isolated project-local Playwright automation unavailable; no authenticated browser context was created. |
| Six viewport checks | Could not run | 360, 390, 768, 1024, 1280 and 1440px remain unverified in a real browser. |
| Recruiter screenshots | Not captured | No genuine list, details/timeline, lifecycle-dialog or monitoring-dashboard captures were available. |

Docker initially denied access inside the restricted runner. The authorised
read-only Docker checks then succeeded. Only container status and Flyway metadata
were inspected; credentials were not printed. Database verification used the
configured local Compose PostgreSQL service and this explicit read-only SQL:

```sql
BEGIN READ ONLY;
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
COMMIT;
```

No business data was queried or changed. No application restart, migration
execution, fixture creation, status/assignment/comment mutation, infrastructure
restart or Git-history change occurred. The live backend's exact database
connection and loaded application revision were not independently established by
an authenticated request; the migration evidence above applies to the verified
local Compose application database.

### Manual live acceptance checklist

Use an isolated private browser session opened directly at `http://localhost:5173`.
Sign in manually through Keycloak. Use authorised disposable demonstration
organisations/accounts and incidents only. Do not modify existing operational
incidents, production services or user memberships to satisfy this checklist.
If suitable roles or fixtures are unavailable, record that check as blocked.
Never save credentials, JWTs, request headers or an unredacted HAR with evidence.

First, in the browser Network response for the existing organisation request,
verify `currentUserId` is a local UUID and `currentUserRole` is the expected
local membership. Inspect only the response fields needed for this check, not
Authorization headers. Missing fields mean the running backend is not confirmed
Phase 4E-compatible; stop write acceptance and run the current backend through
the normal development workflow before continuing. V12 is already confirmed
in the local Compose database; no reset, repair or baseline is required.

| Workflow | Manual operation | Required result/evidence |
| --- | --- | --- |
| Authentication/organisation | Sign in and select demonstration organisation A. | Hosted login returns to OpsPilot; selected organisation and local membership context agree. |
| Incident navigation | Open Incidents from desktop navigation and mobile drawer. | Correct route; drawer closes and active navigation is visible. |
| Filters/sort/pagination | Apply status, severity and service filters, change creation sort, Next/Previous, then Clear. | Network query contains the selected server parameters; page content and counts match. Filters reset page to zero. Pagination needs more than 20 suitable incidents; otherwise record it untested. |
| Details/timeline/links | Open a demonstration incident, page timeline, use latest events and follow a related alert/service. | IDs/timestamps/status agree with actual responses; chronological events distinguish human and system origin; links open the correct scoped record. |
| Lifecycle | On a disposable incident, acknowledge/investigate/mitigate; resolve only after its linked alerts recover. Cancel a confirmation first. | Only applicable actions appear; Cancel writes nothing; confirmed request sends expectedVersion; incident and timeline refresh on success. Active linked alerts prevent resolution/closure. |
| Reopening | Reopen a suitable resolved/closed demonstration incident; test an existing ongoing-incident conflict only with approved fixtures. | Allowed reopening succeeds; conflicting reopening gives a meaningful 409 without duplicate incidents. |
| OWNER/ADMIN assignment | Assign/reassign using a known eligible local member UUID, then unassign an ongoing demonstration incident. | Version is sent; assignee and immutable assignment events agree; unavailable/foreign assignees are rejected. No directory API is assumed. |
| ENGINEER assignment | With a demonstration engineer, claim an unassigned ongoing incident, then release it. | Self-claim/release are available; taking another member's assignment and administrative reassignment are unavailable and backend-protected. |
| Human comments | Submit blank input, then a multiline comment; inspect the count and 4000-character limit. | Blank/oversized input is rejected; successful POST produces an attributed plain-text comment and updated timeline, without changing incident version. |
| Retry safety | With a disposable comment, interrupt a request and retry the same logical submission when safe. Inspect only the UUID retry-key value if necessary, keeping auth headers private. | Same logical retry uses the same Idempotency-Key; one persisted comment. A new submission gets a new key. Pending submit cannot be repeated. |
| Stale version | Open the same disposable incident in two tabs. Make a permitted change in tab A; without refreshing tab B, submit its previously loaded version. | Tab B receives 409, refreshes details, reports the conflict and does not silently replay the write. |
| Tenant/cache isolation | Switch A to B during a deliberately delayed incident/timeline request, then return to A. Try an A incident URL while B is selected. | Old tenant data, filters, feedback and drafts do not appear under B; a foreign scoped record returns unavailable/404. Restore normal network throttling afterwards. |
| VIEWER | Sign in as an existing demonstration VIEWER and inspect an incident. | Details/timeline readable; lifecycle, assignment and comment writes unavailable. Direct unauthorised writes remain denied by the backend. |
| Existing screens | Navigate back to Dashboard, Services, Service Details and Alerts. | Existing monitoring/alert data remains organisation-scoped and usable; enabled state remains distinct from health. |
| Loading/error/empty | Use network throttling/offline mode and a known empty demonstration filter. | Useful loading/error/retry/empty feedback; stale privileged content is hidden after access errors; no false success. Restore networking afterwards. |

Do not force a 409 by modifying database versions. Do not create incident records
through SQL or invent new backend endpoints for fixtures. Automatic creation from
a dedicated authorised monitoring demo is the existing supported workflow.

### Responsive and accessibility matrix

Use browser device emulation at 100% zoom with the following **CSS viewport**
widths. Record the actual height and browser/version when executing each row.
All entries below are pending, not observations from jsdom tests.

| Width | Pages and controls to inspect | Current result |
| --- | --- | --- |
| 360px | Registry cards, single-column filters, drawer/organisation selector, stacked details, comment form and confirmation dialog. | Not run |
| 390px | Same mobile controls, long title/UUID/comment wrapping and full-page scrolling. | Not run |
| 768px | Navigation rail/drawer breakpoint, filter grouping, detail stacking and all actions. | Not run |
| 1024px | Content-dependent card/table and detail columns, sidebar width, timeline and dialog. | Not run |
| 1280px | Registry table, side panel, filters, pagination and monitoring dashboard. | Not run |
| 1440px | Full desktop hierarchy, line lengths, table/detail alignment and monitoring dashboard. | Not run |

At every width check for horizontal page scrolling, clipped controls, overlapping
badges, long unbroken values and inaccessible form actions. Tab through filters,
links and buttons. Verify visible focus, labels, error announcements, native
dialog focus containment, initial Cancel focus, Escape and trigger-focus return.
Repeat representative mobile/desktop checks at 200% text zoom. Keep status and
severity understandable without color alone. Do not treat computed CSS review or
jsdom interactions as proof of these rendering/focus checks.

### Genuine screenshot procedure

After successful live acceptance, save only application-content screenshots with
clean demonstration data under `docs/assets/phase-4e/`, for example
`incident-list.png`, `incident-details-timeline.png`,
`incident-lifecycle-confirmation.png` and `monitoring-dashboard.png`. These are
suggested filenames, not existing assets or verified captures. Exclude login
screens, account menus with personal information, DevTools/network headers,
browser chrome and unrelated windows. Review each file before adding a verified
relative image link to README. This follow-up created no screenshot directory or
image links because no safe genuine captures were produced.

### Follow-up change scope and acceptance status

Only README and this report changed for the final-acceptance follow-up. No
frontend defect was reproduced in a browser, so application code was not changed.
The previous backend results above remain separate from live browser evidence.
The following checks were actually rerun in this follow-up:

| Command/check | Follow-up result |
| --- | --- |
| `npm.cmd run test:run` | 372 passed in 19 files; 0 failed, 0 skipped. |
| `npm.cmd run lint` | Passed. |
| `.\node_modules\.bin\tsc.cmd -b --pretty false` | Passed. |
| `npm.cmd run build` | Passed. |
| README/report links and anchors, Markdown fences, JSON examples, whitespace | Passed. |
| `git diff --check` | Passed; existing line-ending normalisation warning only. |

Backend tests were not rerun for these documentation-only changes; the earlier
800-test result is historical automated evidence, not new live verification.

**Phase 4E visual/browser acceptance remains open.** Local HTTP availability and
V12 metadata are verified; authenticated workflows, the running DTO contract,
all six viewports and screenshots remain blocked by unavailable isolated browser
tooling. Phase 4F was not started.

## Playwright acceptance infrastructure follow-up

On 2026-10-28, explicitly authorised frontend Playwright installation was
completed: Playwright Test 1.64.1 and supported project-local Chromium. No existing
desktop windows, tabs, browser profiles or personal sessions were enumerated or
reused. No backend/application feature code or business records changed.

The [E2E guide](playwright-e2e-phase-4e.md) documents installation, blank-secret
template, dedicated identities, fixture guards, commands, diagnostic privacy and
the detailed verification matrix. Real API requests are used; no E2E API mocking
or fabricated incident data is present. Authenticated specimens require local
membership data from the current backend rather than inferred Keycloak roles.

| Verification | Actual result |
| --- | --- |
| Project-local Chromium installation/launch | Passed. |
| Actual Keycloak login redirect with PKCE S256 | Passed; unauthenticated browser, no credential submission. |
| Actual unauthenticated incident request | Passed; HTTP 401. |
| Complete browser suite | 30 discovered: 2 passed, 28 blocked/skipped, 0 failed scenarios. |
| Strict acceptance gate | Fails intentionally while blocked cases remain; not a green full-acceptance run. |
| Successful login / currentUserId/currentUserRole response | Blocked; no dedicated credentials supplied. |
| Incident, role, write, tenant and six-width scenarios | Blocked before authentication; not reported as passed. |
| Screenshot assets | None captured; no README image links fabricated. |
| Vitest | 374 passed in 20 files, 0 failed/skipped; includes 2 new privacy/gate reporter unit tests. |
| ESLint / TypeScript / production build | Passed, including new E2E source type-checking. |
| Backend regression | Not rerun; backend unchanged in this follow-up. Earlier 800-test evidence remains historical. |
| Documentation and Git whitespace checks | Passed. |

The new tooling files are `frontend/playwright.config.ts`, `tsconfig.e2e.json`,
`tools/playwright.mjs`, and `e2e/{fixtures.ts,reporter.ts,reporter.unit.ts,
public.spec.ts,incidents.spec.ts,writes.spec.ts,responsive.spec.ts,.env.example}`.
Frontend package manifests, ignore rules, TypeScript project references and
Vitest discovery were extended. README and this report were updated, and the
dedicated E2E guide was added. Previous uncommitted implementation was preserved.

Traces/HAR/video/storageState and automatic DOM snapshots are disabled because
they can capture credentials or bearer tokens. A safe reporter records metadata
only; optional masked app-content diagnostics require reviewed demo data. Explicit
write opt-in plus a prefixed organisation, TEST HTTP service and paused monitoring
protect fixtures from unrelated writes and background version changes. The suite
creates no identities or memberships and deletes no records/history.

`npm audit` also reported a high-severity advisory in the unchanged existing Zod
dependency; no unrelated automatic upgrade was made. See the E2E guide for details.

**Remaining action:** prepare `frontend/.env.e2e.local` from the blank template
with dedicated operator/engineer/viewer credentials and approved disposable
fixture IDs, then run `npm run e2e:acceptance` from frontend. Credentials must stay
local. The authenticated spec assertions and screenshots still need actual live
execution; merely installing the runner does not close those criteria. Phase 4E
browser/visual acceptance remains incomplete. Phase 4F was not started.

## Dedicated environment and fixture setup follow-up

The [setup report](e2e-environment-fixture-setup.md) records the next prerequisite
inspection. No dedicated credentials/fixture IDs were available. The ignored
`.env.e2e.local` was created from the blank template with writes/screenshots
disabled; no identities, memberships or business records were provisioned.
`e2e:check-env` is a reusable opt-in initializer/secret-safe configuration check,
not a fixture seeder or authentication test. It reports ten missing credential/
UUID fields and disabled opt-in flags without printing any values.

Inspection confirmed an important setup gap: local membership operations exist
in `OrganisationMembershipService`, but no membership-management REST endpoint
is exposed. Keycloak user creation or realm roles cannot grant local ENGINEER/
VIEWER access. The setup guide gives exact manual Keycloak user-creation steps,
the target tenant/membership matrix, a 24-incident Alpha/two-incident Beta design
through real monitoring/alert/lifecycle workflows, and the constraints for a
future explicitly approved application-service fixture bootstrap. None of that
target dataset is claimed to exist.

Execution stopped at the required administrator identity prerequisite. No E2E
acceptance was rerun beyond that boundary; the preceding 2-passed/28-blocked run
remains historical evidence. No incident viewport checks or screenshots were
performed. Current HTTP availability checks returned frontend 200, protected
organisation API 401 and the expected Keycloak issuer with S256 support.

After adding the checker and six focused validation/privacy tests, **380 frontend
tests passed in 21 files**; lint, TypeScript and production build passed. No
backend code or migrations changed and backend tests were not rerun. README and
the E2E/setup reports were updated. All earlier uncommitted work remains in place;
no commit/push/merge or Phase 4F work occurred. Administrator-created dedicated
users, actual local memberships and normal-workflow fixtures remain required
before acceptance can be completed.

## Phase 4D.5 readiness and strict acceptance recheck

Execution date: **2026-10-29**. Branch remains
`feature/phase4-incident-management`; all prior uncommitted work was preserved.
This follow-up changes documentation only. It supersedes the earlier missing
membership-API prerequisite: the production [Phase 4D.5 API](organisation-membership-api-phase-4d5.md)
is implemented, but it does not automatically create test identities or fixtures.

### Live local readiness evidence

- Frontend HTTP 200; backend health HTTP 200 with `status=UP`; unauthenticated
  organisation request HTTP 401.
- Keycloak discovery HTTP 200 with issuer
  `http://localhost:8082/realms/opspilot` and PKCE S256 support. No administrator
  login or realm-account enumeration was attempted.
- Local Compose services identified from this repository's configuration:
  `opspilot-postgres-1` healthy, `opspilot-keycloak-1` running. No service was
  stopped/restarted.
- In an explicit `BEGIN READ ONLY` transaction inside the confirmed local
  PostgreSQL container, `opspilot.public.flyway_schema_history` returned successful
  V1–V13 rows. V13 script was `V13__create_membership_audit_events.sql`, checksum
  `1294387464`; `public.membership_audit_events` exists. Only metadata SELECTs
  were executed. The migration was already applied before this check; no approval
  request/application, repair, baseline or database reset was necessary.
- Backend listener PID 45784 was the Java 21 OpsPilot process using
  `backend/target/classes`, started at `2026-10-29T15:30:38.5336030+01:00` after
  compilation of the current membership controller. This identifies the current
  workspace build rather than a stale packaged JAR. It does not substitute for
  authenticated response verification.
- `OrganisationResponse` includes `currentUserId` and `currentUserRole` in source.
  Their live authenticated response, actual local roles and fixture ownership
  could not be verified without dedicated credentials.

### Fresh browser verification matrix

`npm.cmd run e2e:acceptance` executed the existing suite against the actual local
frontend/backend/Keycloak using isolated project-local Chromium, without mocking
API responses or using existing desktop windows, tabs, profiles or sessions.

| Scenario group | Passed | Failed | Blocked | Evidence/limitation |
| --- | --- | --- | --- | --- |
| Hosted Keycloak redirect and PKCE S256 | 1 | 0 | 0 | Actual browser redirect; no credential submission or successful-login claim. |
| Unauthenticated incident rejection | 1 | 0 | 0 | Actual backend HTTP 401. |
| Authenticated context, browsing, filters, sorting, pagination, details/evidence, switching and cross-section navigation | 0 | 0 | 8 | Dedicated operator credentials missing; blocked before login. |
| Registry and details at six viewport widths | 0 | 0 | 12 | 360, 390, 768, 1024, 1280, 1440px; authentication prerequisite unmet. |
| Recruiter screenshots | 0 | 0 | 1 | No approved authenticated demo environment; no images captured. |
| Lifecycle, stale-version 409, assignment, comments, ENGINEER self-assignment and VIEWER restrictions | 0 | 0 | 7 | Dedicated credentials/fixtures/explicit write opt-in unavailable. |
| **Total** | **2** | **0** | **28** | **30 discovered; strict command exits 1; acceptance incomplete.** |

The safe reporter writes only metadata to ignored
`frontend/test-results/e2e-results.json`; it records `acceptanceComplete=false`.
Traces, video, HAR, storageState and automatic DOM/auth screenshots remain off.
No personal/credential screenshots or mocked integration passes were produced.

### Exact remaining prerequisite and next actions

`npm.cmd run e2e:check-env` exited 1 and reported these existing keys missing:

```text
E2E_USERNAME
E2E_PASSWORD
E2E_ENGINEER_USERNAME
E2E_ENGINEER_PASSWORD
E2E_VIEWER_USERNAME
E2E_VIEWER_PASSWORD
E2E_ORGANISATION_ID
E2E_INCIDENT_ID
E2E_SECOND_ORGANISATION_ID
E2E_ASSIGNEE_USER_ID
```

`E2E_ALLOW_WRITES`, `E2E_DATA_IS_DEMO` and `E2E_SCREENSHOTS` remain disabled. The
private file is Git-ignored and was neither printed nor overwritten. Identity
existence was not inferred from missing configuration; no test/admin credentials
were supplied, guessed or extracted from a personal session.

The immediate action is for an authorised administrator to use the existing
Keycloak `opspilot` realm's **Users** workflow to create or confirm three dedicated
automation identities. Suggested names are `opspilot-e2e-owner`,
`opspilot-e2e-engineer`, `opspilot-e2e-viewer`; do not reset/reuse an account whose
test ownership is uncertain. Give each a distinct verified test email and a
separate credential under the realm's existing policy. Complete required actions
through the approved flow; do not disable MFA or weaken policy for automation.
Fill the six existing credential keys in `frontend/.env.e2e.local` locally. Do
not put the administrator password in that file, send secrets in chat or grant
Keycloak realm roles as a substitute for local memberships. The full procedure is
in [Step 1 of the setup guide](e2e-environment-fixture-setup.md#step-1--administrator-action-dedicated-keycloak-users).

After credentials are ready, authenticate/provision each identity through the
normal flow. Create disposable Alpha/Beta organisations as the dedicated OWNER,
then use authenticated POST members with the local ENGINEER/VIEWER user IDs;
ordinary membership endpoints must not assign OWNER. Verify actual directory
roles and restrictions before preparing real monitoring-derived fixtures.
Fixture creation/changes still require explicit opt-in; none occurred here.
Keep write/capture flags off until approved suite-owned data is ready. Fill the
real fixture IDs, run preflight and rerun strict acceptance. No direct SQL
membership/incident insertion or authorization bypass is an alternative.

### Quality and documentation checks

| Command/check | Current result |
| --- | --- |
| `npm.cmd run e2e:check-env` | Exit 1: prerequisite report only; no network/provisioning. |
| `npm.cmd run e2e:acceptance` | Exit 1: 2 passed, 28 blocked, 0 failed scenarios. |
| `npm.cmd run test:run -- e2e/environment.unit.ts e2e/reporter.unit.ts` | 8 passed in 2 files, no failed/skipped tests. |
| `npm.cmd run lint` | Passed. |
| `npm.cmd run build` | Passed; includes `tsc -b`, E2E project references and Vite production bundle. |
| Full backend/frontend regression | Not repeated for documentation-only changes; earlier 862/391 full-suite results are historical, not fresh results from this run. |
| Documentation links | 67 relative file references checked across the four updated documentation files; none missing. |
| `git diff --check` | Passed; existing line-ending notices are not whitespace errors. |

README, the E2E setup guide, the Playwright guide and this report were updated.
No product code, dependencies, source fixtures, private credentials or business
records changed. No commit/push/merge occurred. Phase 4E browser acceptance is
**not complete** and Phase 4F was not started.
