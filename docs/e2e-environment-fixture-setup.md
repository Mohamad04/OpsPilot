# Phase 4E — Dedicated E2E environment and fixture setup

## Readiness recheck — 2026-10-29

Phase 4D.5's production membership API is present. This subsequent run used only
read-only readiness checks and the isolated strict browser gate; the original
setup evidence below is retained as historical context.

| Prerequisite/check | Actual current result |
| --- | --- |
| Frontend / backend | Frontend HTTP 200; backend `/actuator/health` HTTP 200, status UP. |
| Keycloak | Discovery HTTP 200, expected local `opspilot` issuer and S256 support. |
| Development target | Project Compose PostgreSQL and Keycloak containers running; PostgreSQL healthy and published on local port 5432. |
| Flyway | SELECT-only read-only transaction in local `opspilot`: V1–V13 all successful; membership audit table present. No migration was pending or applied by this run. |
| Backend process | Java 21 OpsPilot process, using project `backend/target/classes`, started after the compiled membership controller. No restart performed. |
| Current-user response contract | `currentUserId`/`currentUserRole` confirmed in source DTO; authenticated live response blocked by missing dedicated credentials. |
| Private configuration | File remains Git-ignored and unchanged. Six credential fields and four fixture UUIDs are missing. Write/demo/screenshot opt-ins remain disabled. |
| Dedicated identities | Existence not verified; no approved dedicated login or Keycloak administrator session was supplied to this run. No credential guessing or personal-session reuse. |
| Strict acceptance | Executed: 30 discovered, **2 passed, 28 blocked, 0 failed scenarios**; exit 1 intentionally enforces incomplete acceptance. |
| Responsive/screenshots | All twelve list/detail checks at 360, 390, 768, 1024, 1280 and 1440px blocked before authentication; no screenshots captured. |

No users, organisations, memberships, services, alerts, incidents or comments were
created or modified. No development SQL insertion, migration, fixture seeding,
tunnel startup or authorization change occurred. Verification stopped at the
dedicated-identity prerequisite. Follow Step 1 below; do not send credentials in
chat. The immediate administrator action is to create/confirm the three dedicated
users, then fill their six existing credential keys locally. Leave write/capture
flags disabled until disposable data and write approval are explicitly ready.

Fresh quality checks: 8 focused E2E environment/reporter unit tests passed; ESLint,
`tsc -b` and Vite production build passed. No product code changed, so backend
regressions were not rerun: the preceding 862-backend/391-frontend full-suite
results remain separate historical evidence. The detailed current verification
matrix is in the [Phase 4E report](incident-dashboard-phase-4e.md#phase-4d5-readiness-and-strict-acceptance-recheck).

## Current boundary and verified state

On 2026-10-28, repository inspection found no dedicated E2E credentials or fixture
IDs in the process environment and no private E2E configuration file. The blank
existing template was copied to ignored `frontend/.env.e2e.local` using an
exclusive, non-overwriting operation. No passwords or resource IDs were guessed.
All write/capture opt-ins remain disabled.

The new `npm run e2e:check-env` command validates local presence/format and emits
**variable names and constant status messages only**, never values. It performs
no network calls and cannot prove authentication, membership, fixture ownership
or database contents. A complete configuration is not an acceptance pass.

Current read-only HTTP checks: frontend 200; unauthenticated organisation API 401;
Keycloak discovery issuer `http://localhost:8082/realms/opspilot`, advertising S256.
Full Keycloak client/admin settings were not inspected without administrator
access. Existing documented configuration is public client `opspilot-frontend`,
PKCE S256, API audience `opspilot-backend`.

**Stop point:** an administrator must prepare dedicated Keycloak identities and
provide their credentials locally. There is also a separate application
membership-setup procedure described below. No account, membership, organisation,
service, health result, alert or incident was provisioned by this follow-up.

## Step 1 — Administrator action: dedicated Keycloak users

Use your own authorised administrator login manually at
[the local Keycloak Admin Console](http://localhost:8082/admin/). Do not copy its
password into the E2E file or reuse that account for browser tests. Do not share
passwords/tokens in chat, screenshots or source control.

1. Select the **opspilot** realm, not the master realm. Leave its existing client,
   issuer, audience and authentication policies unchanged.
2. Under **Users**, create three new users reserved for this suite. Suggested
   names are `opspilot-e2e-owner`, `opspilot-e2e-engineer` and
   `opspilot-e2e-viewer`. If a name already exists without confirmed suite ownership,
   choose a fresh unique suffix instead of editing/resetting that account.
3. Supply distinct test email addresses and clean demonstration display names.
   For local-only dedicated identities, approved administrator-managed addresses
   under the reserved `example.test` domain can avoid using personal information.
   Do not reuse any existing account's email: OpsPilot refuses automatic email linking.
4. Enable each dedicated user. Complete the approved email-verification process;
   for administrator-created local test identities, explicitly mark their test
   addresses **Email verified** only as an approved administrative test setup.
   First-time local provisioning requires a valid verified email claim.
5. In each user's **Credentials**, set a separate locally generated password.
   For these newly created automation identities, use a non-temporary credential
   if permitted by your local test policy. Never change another user's password.
6. Review required actions. The existing runner automates the standard
   username/password hosted-login form, not forced password changes, email-action
   links or MFA enrolment. If realm policy requires those, stop and arrange a
   policy-compliant test identity/authentication workflow; do not disable realm
   security or bypass a challenge to make a test pass.
7. Do **not** add OWNER/ENGINEER/VIEWER realm roles as a substitute for OpsPilot
   membership. These labels represent the desired local memberships below;
   creating a Keycloak user alone grants no organisation access.
8. Edit `frontend/.env.e2e.local` locally and fill only the six existing credential
   keys. The file has already been created in this workspace; do not overwrite it:

   | Intended identity | Existing keys |
   | --- | --- |
   | Operator (eventual local OWNER) | `E2E_USERNAME`, `E2E_PASSWORD` |
   | Local ENGINEER | `E2E_ENGINEER_USERNAME`, `E2E_ENGINEER_PASSWORD` |
   | Local VIEWER | `E2E_VIEWER_USERNAME`, `E2E_VIEWER_PASSWORD` |

No Keycloak admin credential is needed in the E2E file. The suite must not enable
password grants or put a client secret in the public frontend. Existing
[authentication configuration](keycloak-authentication.md) and
[provisioning policy](user-provisioning.md) remain authoritative.

## Step 2 — Local provisioning and membership administration

After the dedicated users exist, each signs in through the hosted flow in a new
isolated context. The existing frontend calls authenticated `POST /api/me/provision`.
Its `UserResponse` contains the **local** `id` and `displayName`. Review only that
response body when a local user UUID is required; do not copy bearer headers or
export an unredacted HAR. The local UUID differs from the Keycloak subject/user ID.
An empty organisation list on first login is correct: provisioning grants no roles.

The operator can create the two dedicated organisations through the existing
Create Organisation UI or authenticated `POST /api/organisations`. Their creator
becomes OWNER through the existing business use case. Use fresh run-specific
slugs starting with `opspilot-e2e-`, for example `opspilot-e2e-alpha-<run-suffix>`
and `opspilot-e2e-beta-<run-suffix>`. The suffix is a naming convention, **not a new
environment variable**. Preserve the real returned IDs; never reuse an unrelated
organisation merely because its name looks similar.

### Production membership API (Phase 4D.5)

The [membership API](organisation-membership-api-phase-4d5.md) now exposes the
existing application-service policy through authenticated, tenant-scoped routes.
After each dedicated identity signs in and is provisioned, the Alpha OWNER can:

1. Call `POST /api/organisations/{alphaId}/members` with
   `{"userId":"<engineer-local-uuid>","role":"ENGINEER"}`.
2. Call the same endpoint with the viewer's local UUID and role `VIEWER`.
3. Call `GET /api/organisations/{alphaId}/members?page=0&size=100` to verify the
   resulting local user IDs and actual membership roles. Page further if needed.

Use the OWNER's actual validated session for these calls; no body-supplied actor
or Keycloak role grants permission. No credentials belong in documentation or
committed scripts. Do not insert memberships using SQL or bypass provisioning.
The API preserves the existing policy prohibiting ordinary OWNER additions,
demotions and removals. OWNER bootstrap still comes from organisation creation.

Required target memberships:

| Dedicated identity | Alpha | Beta |
| --- | --- | --- |
| Operator | OWNER, from creating Alpha | OWNER, from creating Beta |
| Engineer | ENGINEER, through the existing membership business use case | No membership needed |
| Viewer | VIEWER, through the existing membership business use case | No membership needed |

No separate development-only membership bypass/bootstrap is needed. The API is a
production feature, not a test backdoor. Dedicated credentials and actual fixture
creation remain operator prerequisites. **No identities or memberships were
provisioned during the Phase 4D.5 implementation**, and no earlier blocked browser
scenario is reclassified as passed.

## Step 3 — Deterministic dataset design (not provisioned)

The following is the target fixture specification, **not observed live data**.
Each incident originates from an actual ACTIVE alert. Use one HTTP service per
initial incident: repeated outages on the same service correlate into its still
ongoing incident and will not reliably produce enough pagination records.

| Tenant | Target incidents | Purpose |
| --- | --- | --- |
| Alpha | 1 OPEN, unassigned HIGH incident whose alert has recovered | `E2E_INCIDENT_ID`: lifecycle, 409, assignment, comments and screenshots. |
| Alpha | 7 further OPEN incidents, mixed severities, with ACTIVE alerts | Active/history distinctions and alert evidence. |
| Alpha | 8 RESOLVED incidents with recovered alerts, including HIGH | Status/severity filters with nonempty matching records. |
| Alpha | 8 CLOSED incidents with recovered alerts | Historical state and creation sorting. |
| Beta | 1 OPEN with ACTIVE alert; 1 RESOLVED with recovered alert | Independent tenant records/cache checks. |

Alpha has **24 real incident records**, giving two UI pages at size 20. Dataset
ordering is based on real creation timestamps and the backend's UUID tie-breaker;
do not backdate records or modify database clocks. Vary actual alert-rule
severities across LOW/MEDIUM/HIGH/CRITICAL using the existing rule API.

Every source service must have `serviceType=HTTP`, `environment=TEST` and a name
starting with `opspilot-e2e-`. Capture service/alert/incident IDs in a non-secret
run manifest during authorised setup. Only manifest-owned
resources may be changed or cleaned up by that process. Do not put credentials
or access tokens in the manifest.

### Supported simulation procedure after authorisation

1. Use the existing [Node HTTP demo](../tools/alert-e2e-demo/README.md) and a new
   approved public HTTPS tunnel/endpoint you control. Do not reuse historical
   URLs/PIDs or point tests at the portfolio/production website. Loopback/private
   outbound targets remain forbidden by OpsPilot's SSRF policy.
2. Register the dedicated TEST services through the actual monitored-service API
   while the demo is UP. Configure the intended severity per service, retaining
   the normal failure/recovery thresholds (3/2). Wait for persisted UP evidence
   for every service by polling the authorised latest/history APIs.
3. Switch the controlled demo to DOWN. Poll persisted evidence until each service
   has the qualifying consecutive DOWN checks, one ACTIVE alert and its linked
   OPEN incident. Do not insert health-check results, alerts or incidents.
4. Disable monitoring on the eight services whose alerts should stay ACTIVE
   (seven Alpha, one Beta), after verifying their alerts/incidents exist. This
   preserves actual failure evidence; disabled is configuration, not healthy.
5. Restore the demo to UP for the remaining services. Poll until two accepted UP
   observations resolve each alert. Incident recovery does not automatically
   resolve the incident; the target OPEN incident stays OPEN.
6. Use the existing lifecycle API for historical fixtures only: OPEN →
   ACKNOWLEDGED → RESOLVED, then CLOSED where required. Read the current version
   before every PATCH and send its actual `expectedVersion`. Active linked
   alerts must prevent resolution/closure; never bypass that rule.
7. Pause monitoring on the remaining incident-source services. Confirm the primary
   write fixture is ongoing/unassigned and its associated service is HTTP/TEST,
   correctly prefixed and disabled. This prevents background monitoring from
   introducing nondeterministic incident version changes during write tests.
8. Optionally add a separate suite-owned TEST service for live UP observations
   during dashboard capture, while its controlled HTTPS endpoint remains running.
   Disable and re-read that service before stopping the temporary endpoint/tunnel.
   Paused historical observations must never be presented as live healthy checks.

Use bounded, condition-based polling keyed by actual result/alert/incident IDs,
with a deadline derived from configured scheduler timing and request bounds.
There is no fixed sleep that proves an alert/incident was created. Capture a
failure and stop if a predicate is not reached; never replace it with fake data.

The process is deliberately held at the administrator prerequisite in this run.
No tunnel/server, fixture simulation or programmatic data provisioning was started.

### Verified API surface to use

Let `{base}` be `/api/organisations/{organisationId}`. All calls require the
dedicated identity's validated token and real local permissions:

| Method/path | Actual purpose |
| --- | --- |
| `POST /api/me/provision` | Local user from authenticated JWT; no membership. |
| `POST /api/organisations` | Create an organisation and its creator's OWNER membership. |
| `GET /api/organisations`, `GET {base}` | Authorised organisations and local current-user context. |
| `GET/POST {base}/members` | Bounded directory / add provisioned local ENGINEER or VIEWER as authorised OWNER/ADMIN. |
| `PATCH/DELETE {base}/members/{membershipId}` | Change permitted roles / remove membership, retaining audit history. |
| `POST/GET {base}/monitored-services` | Register/list scoped services. |
| `GET {base}/monitored-services/{serviceId}` | Verify actual service configuration. |
| `PATCH {base}/monitored-services/{serviceId}/enable` or `/disable` | Control monitoring without deleting history. |
| `GET/PUT {base}/monitored-services/{serviceId}/alert-rule` | Effective rule/configuration. |
| `GET {base}/monitored-services/{serviceId}/health/latest` or `/health/history` | Actual persisted observations. |
| `GET {base}/alerts`, `GET {base}/monitored-services/{serviceId}/alerts` | Scoped real alert evidence/history. |
| `GET {base}/incidents`, `GET {base}/incidents/{incidentId}` | Existing correlated incidents and versions. |
| `GET {base}/incidents/{incidentId}/events` | Immutable timeline. |
| `PATCH {base}/incidents/{incidentId}/status` | Validated lifecycle change. |
| `PATCH {base}/incidents/{incidentId}/assignee` | Versioned assignment on ongoing incidents. |
| `POST {base}/incidents/{incidentId}/comments` | Attributed immutable human comment. |

There is no public incident/alert/health-result creation endpoint. Membership
management uses the real Phase 4D.5 routes listed above; incident/alert fixtures
must still originate from supported monitoring and correlation workflows.

## Step 4 — Complete the existing environment variables

No new environment variable names were introduced.

| Existing variables | Source/condition |
| --- | --- |
| `E2E_BASE_URL`, `E2E_API_URL`, `E2E_KEYCLOAK_URL` | Existing local ports 5173/8080/8082; template already populated. |
| `E2E_USERNAME`, `E2E_PASSWORD` | Dedicated operator credentials entered locally by administrator/user. |
| `E2E_ENGINEER_USERNAME`, `E2E_ENGINEER_PASSWORD` | Dedicated engineer credentials; actual Alpha ENGINEER membership required. |
| `E2E_VIEWER_USERNAME`, `E2E_VIEWER_PASSWORD` | Dedicated viewer credentials; actual Alpha VIEWER membership required. |
| `E2E_ORGANISATION_ID` | Real Alpha UUID returned by the application. |
| `E2E_SECOND_ORGANISATION_ID` | Real distinct Beta UUID; operator belongs to both. |
| `E2E_INCIDENT_ID` | Primary ongoing, unassigned Alpha incident UUID with quiet TEST source service. |
| `E2E_ASSIGNEE_USER_ID` | Engineer's **local** OpsPilot UUID; eligible member and different from operator. |
| `E2E_ALLOW_WRITES` | Leave 0 until approved suite-owned fixtures exist; then 1 for write acceptance. |
| `E2E_DATA_IS_DEMO`, `E2E_SCREENSHOTS` | Leave 0 until visible data is reviewed as clean demo data; then 1 to permit captures. |
| `E2E_REQUIRE_COMPLETE` | Template 0 is valid; `e2e:acceptance` forces strict completion regardless. |

From frontend:

```powershell
# Safe and repeatable: creates only a missing blank private file, never overwrites.
npm run e2e:check-env -- --init
# After credentials, memberships and fixtures have actually been prepared:
npm run e2e:check-env
npm run e2e:acceptance
```

The preflight command exits 1 while required values/consent flags are missing or
invalid. An existing private file is preserved. It checks distinct identity names,
UUID formats, distinct organisation IDs, local origins and literal 0/1 flags.
It never infers a password, role, current user ID or fixture result. Its exit 0
means only that local configuration is complete; live API/browser verification
is still required.

## Execution and current results

| Item | This follow-up |
| --- | --- |
| Private environment | Created from blank template, verified ignored by Git; no credential values filled. |
| Preflight | Executed; exit 1 for six missing credentials, four missing UUIDs and disabled write/capture flags. |
| Identities/memberships/business fixtures | None provisioned. Dedicated identities and authorised API fixture setup remain; Phase 4D.5 supplies the membership API. |
| Browser acceptance | Not rerun past the administrator boundary. Previous run: 2 passed, 28 blocked; not fresh evidence. |
| Responsive checks/screenshots | Not executed/captured; all six incident viewport checks remain pending. |
| Unit/component tests | 380 passed in 21 files, including six new environment-check tests. |
| ESLint / TypeScript / production build | Passed. |
| Backend tests | Not rerun; backend code unchanged. Earlier 800 tests remain historical evidence. |

The new checker files are `frontend/e2e/environment.ts`,
`frontend/e2e/environment.unit.ts` and `frontend/tools/check-e2e-env.mjs`.
`frontend/package.json` adds `e2e:check-env`. README, the E2E guide and Phase 4E
report link this setup procedure. The private local file is not a committed
artifact. No dependencies, migrations, product components or authorization rules
were changed. Existing uncommitted work was preserved; no commit/push/merge or
Phase 4F work occurred.

**Required user action now:** create the three dedicated Keycloak identities
through your administrator session and fill their six credential values in the
ignored local file. Do not send credentials in chat. Once those identities are
ready, use the authenticated membership API above and prepare monitoring-driven
fixtures before full E2E acceptance can proceed. V13 is required for the updated
membership backend; apply it only through the normal application/Flyway workflow.
