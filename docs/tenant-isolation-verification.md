# Tenant-isolation verification before Phase 3

Date: 2026-10-26. Scope: existing organisation/service workflows and Dashboard data.
No monitoring or alerting functionality was implemented.

## Verification boundary and fixtures

The new backend integration suite starts the complete Spring Boot application on a
random HTTP port and a disposable PostgreSQL 18.4 Testcontainer with the existing
Flyway migrations. Requests use real RSA-signed bearer tokens, a temporary local
JWKS server and the real resource-server decoder, controllers, services and repositories.
No JWT decoder or persistence repository is mocked in this suite. Test-only issuer,
JWKS and audience properties do not change production configuration.

Each test provisions four disposable identities and creates two organisations through
HTTP: Alice owns Alpha, Bob owns Beta, a shared user is ENGINEER in Alpha and VIEWER
in Beta, and an outsider has no memberships. Alpha has two services (one enabled,
one disabled); Beta has a different enabled service. Signed tokens deliberately
claim a realm OWNER role to verify that local memberships remain authoritative.
Fixtures are reset only inside the container database. Existing local/production
organisations, users, services, memberships and Keycloak accounts are untouched.

The new frontend suite uses StrictMode, the real OrganisationProvider, router,
TanStack Query cache and authenticated API clients. HTTP responses and token
retrieval are controlled fixtures so responses can be delayed and reordered.
Existing Dashboard, creation and service-action tests remain enabled.

These are full HTTP/backend integration tests and frontend integration tests,
not a newly executed browser/Keycloak SSO end-to-end test.

## Findings

Backend tenant isolation passed the added HTTP/database tests without production
backend changes:

- `/api/organisations` returns only the subject's local memberships, including an
  empty list for a provisioned outsider. Organisation creation uses the validated
  JWT subject and the existing server-assigned OWNER workflow.
- Organisation detail, service collection/detail, registration and enable/disable
  all require local membership. Service detail and mutations query by both service
  ID and organisation ID. A user belonging to both organisations cannot move a
  service ID between tenant paths.
- Non-members receive the same 404 JSON for existing and nonexistent organisations.
  Foreign service IDs under an authorised tenant return 404 without exposing the
  foreign service's name, endpoint or organisation data.
- VIEWER writes return 403 even when its token claims OWNER; the same user's
  ENGINEER membership permits writes only in its own tenant. Existing role-matrix
  tests also cover OWNER, ADMIN, ENGINEER and VIEWER.
- Missing/malformed bearer credentials, wrong signatures, wrong issuer/audience
  and expired tokens return 401 for every scoped operation.
- Revoking a membership is enforced on the next request using the same still-valid
  JWT. Rejected writes leave service records unchanged.
- Dashboard has no separate metrics endpoint: its collection endpoint returns only
  the selected tenant's records, with correct enabled/disabled counts.

Frontend switching tests verify that an uncached tenant shows loading rather than
the previous tenant's records. Late successful or denied requests for Alpha cannot
replace Beta's records or error state. Cached tenant switching and removal of the
selected membership from the organisation list also preserve correct scoping.
Existing tests verify tenant-specific creation/update invalidation, draft reset,
confirmation reset and late mutation completion. Dashboard's existing tests cover
tenant switching, late responses, counts, errors and mismatched response records.

### Reproduced defect and correction

Four new tests initially failed: the registry and details page retained previously
authorised cached records after a background refresh returned 403 or 404. This could
leave information on screen after membership revocation or service removal. It was
a frontend stale-visibility defect; the backend still rejected the request.

Service GET functions now retain HTTP status in the existing `ApiError` class while
preserving their error messages and API contracts. The two pages hide cached content
and actions when a refresh fails with 401/403/404, and expose their existing retry
state. A successful explicit retry can restore the records after access returns.
Temporary network/server refresh failures retain the established cached-data UX.
No backend security, roles, schema, authentication flow or query keys were changed.

## Files changed for this verification

| File | Change |
| --- | --- |
| `backend/src/test/java/com/opspilot/backend/web/TenantIsolationIntegrationTest.java` | Full HTTP/JWT/PostgreSQL isolation, role, revocation and rejected-write tests. |
| `frontend/src/test/TenantIsolation.test.tsx` | Provider/API/cache integration tests for tenant switching, delayed responses, denied refreshes and recovery. |
| `frontend/src/api/monitoredServiceApi.ts` | Preserve status on collection/detail GET errors using existing ApiError. |
| `frontend/src/pages/ServicesPage.tsx` | Hide cached registry data after access-denied/unavailable refreshes. |
| `frontend/src/pages/ServiceDetailsPage.tsx` | Hide cached details/actions after access-denied/unavailable refreshes. |
| `docs/tenant-isolation-verification.md` | This report. |

All pre-existing uncommitted Dashboard/Services/Create Service work was preserved.
No dependencies were installed and no migrations were changed.

## Commands and results

From `frontend`:

```powershell
npm.cmd run lint
npm.cmd run build
npm.cmd run test:run -- --maxWorkers=1 --testTimeout=15000
```

- Lint: passed.
- Production TypeScript/Vite build: passed.
- Full frontend suite: **183 tests in 12 files passed** (including 12 new isolation tests).
- Focused regression suite before the final added cases: 82 tests in four files passed.
- Single-worker execution and a 15-second test timeout accommodate the existing
  interaction-heavy suites on this workstation; test assertions remain unchanged.

From `backend`, using the already installed/cached Maven distribution and dependencies:

```powershell
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
```

Full backend suite: **252 tests passed, zero failures, errors or skips**, including
all **21** new HTTP isolation tests. The first focused run passed all 20
then-present HTTP isolation tests; a membership-revocation case was added for the
final run. Docker access is required for Testcontainers;
ordinary sandbox access to the Docker pipe was denied, so tests run with authorised
Docker access. No dependencies are downloaded in Maven offline mode.

## Remaining limitations

- Live multi-account Keycloak login, browser organisation switching and browser
  network-race verification were not performed in this pass. The test issuer uses
  the same validation path but is not the running Keycloak realm.
- Existing service updates are enable/disable PATCH operations; no general service
  editing endpoint exists to test.
- Previously delivered data cannot be recalled from a client. The UI hides it once
  the access-denied refresh reaches its error state, and the backend checks database
  membership on subsequent requests. This does not add push-based revocation.
- Timing side channels, load testing and a broader security audit are outside this
  focused verification. No cross-tenant backend access was found in the tested paths.

No manual Keycloak configuration changes are required for these automated tests.
