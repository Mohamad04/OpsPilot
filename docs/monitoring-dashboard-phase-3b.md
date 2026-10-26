# Monitoring dashboard integration — Phase 3B

**Live verification update:** V6 is now deployed and scheduled portfolio checks
are persisted as UP / HTTP 200. The isolated signed-in Dashboard was observed with
cedra selected and UP 1 / DOWN 0 / UNKNOWN 0. Further browser input failed with
`coordinate input geometry is unavailable`; API-body comparisons, Services/Details,
tenant switching and all six exact viewport checks require manual verification.
See [the final follow-up verification report](monitoring-live-verification.md)
for evidence, the execution matrix and exact manual steps. The initial smoke-test
snapshot below is retained as historical context.

This phase connects the frontend to the existing Phase 3A monitoring read APIs.
It preserves Keycloak initialization/provisioning, organisation selection, RBAC,
service registration and enable/disable actions. No backend code, migrations,
dependencies, authentication settings or business records were changed.
See [Phase 3A](scheduled-http-health-checks-phase-3a.md) for scheduler configuration,
outbound HTTP security and database design.

## UI and health semantics

- Dashboard retains configuration/environment totals and adds latest UP, DOWN and
  UNKNOWN counts for **enabled HTTP services in the selected organisation**.
  Disabled services are counted separately as paused; other protocols are excluded.
  Every required observation must load successfully before health totals appear.
- Services registry shows observed health, HTTP response code (or no response),
  response time and absolute check timestamp below each service name. Its existing
  search, filters, 25-item pagination, mobile cards and details links remain intact.
- Service Details adds latest observation and a scrollable, keyboard-focusable
  history of at most 20 records, with status, HTTP code, milliseconds, timestamp
  and backend-sanitised failure details. Latest and history have independent
  loading, empty, error and retry states.
- UP means a recorded successful HTTP check, DOWN a recorded failed check, and
  UNKNOWN an absent observation or a backend-reported unknown outcome (for example
  a blocked outbound target). Network/API failures are errors, never UNKNOWN.
- A disabled service displays **Paused**, even if its historical observation was
  UP. Its previous status is explicitly historical. Non-HTTP services state that
  only HTTP monitoring is supported.
- Observations are explicitly the latest recorded checks, not a guarantee of
  current availability. Timestamps remain visible. No uptime percentage, alert,
  incident or invented monitoring statistic is introduced.
- Registration/details/dashboard notices now describe the implemented HTTP checks.
  Uptime calculations, alert rules and incident management remain unimplemented.

## Existing API contracts reused

Both endpoints require the existing bearer-token client and backend membership
checks. There is no new public route or monitoring write endpoint.

| Method | Route | Response |
| --- | --- | --- |
| GET | `/api/organisations/{organisationId}/monitored-services/{serviceId}/health/latest` | `{ organisationId, monitoredServiceId, enabled, status, latestResult }` |
| GET | `/api/organisations/{organisationId}/monitored-services/{serviceId}/health/history?limit=20` | Newest-first array, bounded to 20 records by this UI (backend supports 1–100) |

A result contains `id`, `organisationId`, `monitoredServiceId`, `startedAt`,
`completedAt`, `status`, nullable `httpStatusCode`, `responseTimeMs` and nullable
`failureDetail`. A successful latest response without an observation contains
`status: "UNKNOWN"` and `latestResult: null`.

`healthApi.ts` validates response shape and scope using the existing Zod dependency.
A mismatched wrapper/record tenant or service is rejected rather than cached as
valid data. It uses the existing `apiFetch`, so memory-only tokens, refresh,
redirect restrictions and 401/403 behavior remain centralized. JWTs are never logged.

## Query lifecycle and tenant safety

Query keys:

```text
["monitored-service-health", organisationId, serviceId, "latest"]
["monitored-service-health", organisationId, serviceId, "history", 20]
```

- Latest observations are shared across dashboard, registry and details; history
  is fetched only on details. The existing service collection/detail keys remain.
- Data is fresh for 30 seconds. Enabled HTTP observations refresh every 60 seconds
  while the app is focused; hidden-tab interval polling is disabled. Disabled
  services load historical data once on mount as needed, without interval polling.
- Monitoring requests are limited to four active requests across the application.
  Queued requests and active fetches receive TanStack Query's AbortSignal, so a
  removed tenant/page can cancel unnecessary work. Registry reads cover only the
  visible page. No request is issued on each render.
- There are no automatic failure retries; explicit retry/refresh actions exist.
  Periodic refresh may retry an error at the normal bounded interval.
- `placeholderData` is explicitly disabled, including when global defaults use
  `keepPreviousData`. The active organisation keys the page content; late responses
  cannot overwrite a different tenant's keys. No health result is copied into
  independent component state.
- Failed health refreshes hide the affected cached observations. Dashboard hides
  the aggregate if any required read fails. On details a 401/403/404 from either
  health endpoint hides both cached latest and history. Existing service-data
  authorization errors continue to hide protected page content.
- Enable/disable invalidates the original tenant's service collection, service
  detail and health prefix. Existing mutation closures retain their original scope
  if the organisation changes while the request is in flight.

Cached observations may be displayed while a refresh is pending. Revocation is
only detectable when an API request returns; backend checks remain authoritative.
No promise of immediate revocation while offline is made.

## Responsive/accessibility design

`monitoring.css` reuses existing color, spacing, type, radius and touch-target
tokens. It uses the existing page container queries: single-column cards on narrow
pages, three health counts from 30rem of available width, and two details cards
from 48rem. Text/failure details wrap, grid columns use `minmax(0, ...)`, and mobile
refresh actions use the available width. Registry retains its existing cards/table
breakpoint. The application shell is unchanged.

Badges include text rather than color alone. Dates use semantic `time`, facts use
`dl`, history uses an ordered list in a labelled focusable scroll region, loading
uses live text/`aria-busy`, and retry/refresh are native keyboard-accessible buttons.

## Live backend smoke test — 26 October 2026

Only read-only inspection and HTTP GETs were performed:

| Check | Actual result |
| --- | --- |
| Existing portfolio registration | Found enabled HTTP service, base URL `https://portfolio.cedraforge.dev`, health endpoint `/` |
| Direct normal HTTPS GET to portfolio | HTTP 200 outside the restricted sandbox; no TLS bypass, redirect override or website changes |
| Local backend `/actuator/health` | HTTP 200 |
| Unauthenticated organisation and health requests | HTTP 401, authentication required |
| Local PostgreSQL Flyway history | V1–V5 applied; V6 absent |
| `health_check_results` in that database | Relation absent |
| Scheduler writes and authorised latest/history HTTP 200 | **Not verified**; local runtime/database has not deployed Phase 3A and no isolated authenticated browser window was available |

Portfolio identifiers observed for follow-up:

```text
organisation: 3b1a3d12-8513-4ab1-b399-8736aac49e12
service:      44ca654d-652a-4f8f-86e0-f5ff5abf79bb
```

The direct website GET proves external reachability from the host, not that the
backend scheduler ran. The backend was not restarted and V6 was not applied as
part of this non-destructive smoke test. Restart/migration approval was requested
separately. No outbound security exceptions were added and no service was changed.

## Automated verification

From `frontend`:

```powershell
npm.cmd run test:run -- --maxWorkers=1 --testTimeout=15000
npm.cmd run lint
npm.cmd run build
```

The new monitoring integration tests use real routes, organisation context, API
clients and query cache with controlled HTTP/auth fixtures. They cover UP/DOWN/
UNKNOWN, configuration separation, disabled historical results, zero services,
bounded/empty history, loading, failures/retry, bearer requests, malformed or
foreign responses, tenant switching with delayed responses, cached-data denial,
fresh cache reuse, polling and paused polling. API tests cover shape/scope, HTTP
errors, concurrency limits, queued cancellation and active cancellation.
These are frontend tests, not live Keycloak/browser end-to-end tests.

Final results:

| Check | Result |
| --- | --- |
| Full frontend suite | **223 tests passed across 14 files** |
| Monitoring UI/cache/tenant/polling tests | **21 passed**, included in full suite |
| Monitoring API/validation/concurrency tests | **19 passed**, included in full suite |
| Frontend lint | **Passed** |
| TypeScript and Vite production build | **Passed** |
| Git whitespace check | **Passed** (existing CRLF normalization warnings only) |
| Live authenticated monitoring / six viewport checks | **Pending**, as described above and below |

No backend source changed in this phase, so backend suites were not rerun. The
Phase 3A report records its previous 346-test backend result; that result does not
prove that the currently running local database has deployed V6.

## Manual verification / remaining limitations

1. Start PostgreSQL and Keycloak using the existing setup. With approval to update
   the local database, start the **current** backend normally so Flyway applies
   the existing V6 migration. Keep outbound restrictions unchanged and monitoring
   enabled; default initial delay is 10 seconds and round delay is 60 seconds.
2. Start the frontend with `npm.cmd run dev`, open an isolated
   `http://localhost:5173` window, and sign in as a portfolio-organisation member.
3. Select that organisation. Open portfolio in Services. Wait for a scheduler
   round, then use Refresh monitoring on details. In browser Network, confirm
   authorised latest/history GETs return 200 with matching organisation/service
   IDs. Inspect the records without copying or exposing the bearer token.
4. Read `health_check_results` using read-only SQL scoped to those IDs. Confirm
   timestamps/history grow across two rounds. Inspect actual failure details if
   the check is DOWN/UNKNOWN; do not change the status or network policy to force UP.
5. Confirm dashboard counts agree with enabled HTTP observations and the registry
   shows the same latest status. Details should show actual HTTP status, duration,
   time and at most 20 historical entries. Polling is approximate, not real-time.
6. Switch to another authorised organisation with distinct services. Old counts
   and rows must disappear while loading. With Network throttling, switch again
   during a pending health request and confirm late data stays out of the active
   tenant. Use disposable fixtures for DOWN/UNKNOWN/empty scenarios; do not edit
   the portfolio website or unrelated records.
7. At widths **320, 390, 768, 977, 1280 and 1440 CSS pixels**, verify Dashboard,
   Services and Details, long names/failure text, keyboard refresh/retry, mobile
   navigation and the history scroll region. There should be no clipped controls
   or horizontal page scroll. These six live browser checks remain pending; jsdom
   does not verify CSS layout and no screenshot/visual pass is claimed.

The backend has per-service APIs, so dashboard refresh costs one read per enabled
HTTP service. Four-request concurrency bounds pressure but does not remove that
fleet-size cost. Batch summaries, retention, push updates and stale-age policy
remain future work. No new Keycloak configuration or Phase 3B migration is needed.
No Phase 3C, alerting or incident implementation is included.

## Changed files

Created:

- `frontend/src/api/healthApi.ts` — authenticated, validated, scope-checked reads and bounded concurrency.
- `frontend/src/hooks/useMonitoring.ts` — shared tenant/service keys and refresh options.
- `frontend/src/components/ServiceMonitoring.tsx` — badges, facts, registry health and details/history.
- `frontend/src/components/MonitoringOverview.tsx` — actual organisation-scoped health counts.
- `frontend/src/styles/monitoring.css` — responsive monitoring styles using existing tokens.
- `frontend/src/test/healthFixtures.ts` — controlled monitoring test records.
- `frontend/src/test/healthApi.test.ts` — API, validation and cancellation regressions.
- `frontend/src/test/Monitoring.test.tsx` — UI/cache/tenant/polling integration regressions.
- `docs/monitoring-dashboard-phase-3b.md` — this report.

Modified:

- `frontend/src/pages/DashboardPage.tsx` — overview and accurate capabilities notices.
- `frontend/src/pages/ServicesPage.tsx` — visible-row monitoring and tenant response guard.
- `frontend/src/pages/ServiceDetailsPage.tsx` — monitoring cards, tenant response guard and health invalidation.
- `frontend/src/pages/AddServicePage.tsx` — replaces obsolete monitoring-unavailable note only.
- `frontend/src/test/DashboardPage.test.tsx` — updated API fixtures/notices.
- `frontend/src/test/ServicesPage.test.tsx` — monitoring fixtures and notice.
- `frontend/src/test/ServiceDetailsPage.test.tsx` — fixtures, notice and health invalidation assertion.
- `frontend/src/test/TenantIsolation.test.tsx` — health endpoint fixtures for existing isolation tests.

The pre-existing uncommitted Phase 3A report was preserved. No changes were committed
or discarded, and no dependencies were installed.
