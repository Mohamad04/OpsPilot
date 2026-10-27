# Phase 3C Step 4 - React alerting frontend

## Subsequent live validation

The user subsequently confirmed the manual HTTP 200/UP -> HTTP 503/DOWN ->
ACTIVE after three failures -> HTTP 200/UP -> RESOLVED after two recoveries
lifecycle using the disposable Node demo and a localhost.run HTTPS tunnel.
See [the alerting E2E validation report](alerting-e2e-validation.md) for the
observations, reproduction steps, evidence boundaries and unverified cleanup
status. The original implementation-time test and browser limitations below
remain historical; this confirmation does not establish responsive or broader
authorization checks.

## Starting point and scope

Implementation started on `feat/alerting`, with a clean working tree and committed
Phase 3C.3 (`9630667`), Phase 3C.2 (`05b2a91`) and Phase 3C.1 (`251a9f2`).
This increment changes only the frontend and this documentation. No dependencies,
backend code, migration, authentication mechanism or API contract are added.

## Pages and navigation

- `/alerts`: organisation-wide alert summary, server-paginated registry, status,
  severity, service and date filters; clear filters, previous/next and manual
  refresh. Results include service name when available, HTTP availability
  category, lifecycle/severity labels, first/last occurrence, count and details.
- `/alerts/:alertId`: validated alert identifiers, service link, lifecycle,
  severity, timestamps, count, opening rule snapshots, threshold actually used at
  resolution, triggering HTTP code/duration and persisted evidence IDs.
- Service Details: HTTP-only alert configuration plus a five-row alert-history
  preview and link to `/alerts?serviceId=...`. The history includes ACTIVE and
  RESOLVED records. Non-HTTP services can show retained historical HTTP alerts,
  but never request or render HTTP rule configuration.
- Dashboard: actual active, resolved and critical-active alert counts and links,
  alongside the unchanged service/health metrics. Old notices claiming alerting
  is unavailable are updated; uptime, incidents and notifications remain absent.
- The shared Navigation adds Alerts after Services for sidebar, collapsed rail
  and mobile drawer. Existing shell, account controls and authentication remain.

Alerts and health are independent. Active is an alert lifecycle state, not the
latest HTTP health status. Resolved records are historical and do not imply the
service is currently healthy. There is no fabricated incident timeline,
root-cause analysis, notification status, uptime percentage or monitoring data.

## Components and styling

`AlertPresentation` contains text-labelled severity/lifecycle badges, retry
feedback and a shared semantic alert table. `AlertOverview` provides the bounded
summary metrics and manual summary refresh. `ServiceAlerts` contains the rule
form and scoped service-history preview.

`alerts.css` reuses existing colour, spacing, typography, radius, focus and touch
tokens. Layout uses available container width, so it adapts to the existing
expanded/collapsed shell:

- Narrow containers: one-column fields, metrics, detail cards and alert rows.
  Rows are styled as compact cards using the same table/headers and links.
- From 36rem: adaptive filter/form grids, two-column alert cards and three
  summary metrics when space allows.
- From 48rem: two detail information cards.
- From 60rem: full tabular alert registry with readable headings.
- Small screens stack actions; long IDs, service/organisation names and counts
  wrap. No minimum-width desktop table is forced on mobile.

Forms have explicit labels, required thresholds, integer bounds, aria-invalid,
linked error text, semantic fieldsets and visible feedback. Navigation keeps
existing keyboard, active-route, Escape and drawer focus behavior. Dates are
represented by `<time dateTime>` and displayed in the browser's locale.

## APIs and contracts

All requests use the existing authenticated `apiFetch`; it supplies in-memory
Keycloak access tokens and handles refresh and 401 session rejection. No new
token persistence or authentication flow is introduced.

| Client | Backend path relative to `/api/organisations/{organisationId}` |
| --- | --- |
| getAlerts | GET `/alerts` |
| getAlert | GET `/alerts/{alertId}` |
| getAlerts with serviceId argument | GET `/monitored-services/{serviceId}/alerts` |
| getAlertSummary | GET `/alerts/summary` |
| getAlertRule | GET `/monitored-services/{serviceId}/alert-rule` |
| putAlertRule | PUT `/monitored-services/{serviceId}/alert-rule` |

`types/Alert.ts` defines Zod schemas and inferred TypeScript contracts for Alert,
AlertPage, AlertSummary, AlertRule, AlertRuleInput and AlertFilters. Their fields
match the committed backend DTOs documented in
`alert-management-api-phase-3c-step-3.md`:

- Alert: IDs, HTTP_AVAILABILITY, severity, ACTIVE/RESOLVED, first/last/resolved
  instants, occurrence count, opening thresholds, actual resolution threshold,
  first/trigger/last/resolution health IDs, trigger HTTP code and duration.
- Page: content, page, size, totalElements, totalPages, first, last.
- Summary: totalAlerts, activeAlerts, resolvedAlerts and all four active severity
  counts. Counts are checked for internal consistency, never derived from only
  the currently loaded page.
- Rule: nullable identity/timestamps for unsaved defaults, organisation/service
  IDs, enabled, consecutiveFailures, consecutiveRecoveries and severity.

Schema checks include enums, safe nonnegative integer counts, 1-20 thresholds,
timestamps, ACTIVE/RESOLVED resolution consistency, evidence IDs and bounded page
metadata. Unknown response properties are stripped; internal diagnostic text is
not displayed. Malformed JSON or invalid/scope-mismatched responses become
controlled 502 errors. Successful PUT additionally verifies persisted identity,
scope and every submitted configuration value before reporting success.

List requests encode page, size, status, severity, serviceId and ISO date bounds
using URLSearchParams. Pagination is zero-based and entirely server-side with
20 rows per registry page; service preview uses five. Client validation rejects
invalid pagination and from >= to before fetching. Date controls use local time
and convert to instants; from is inclusive and to exclusive on firstSeenAt.
Summary cards always represent the whole selected organisation, independently
of the list's filters. Only serviceId is supported as a deep-link query in the
page URL; other filter/page choices are local component state.

HTTP errors preserve status: 401 uses existing session handling; 403 reports
permission denial; 404 reports an unavailable alert/service; other failures have
retry feedback. A failed GET never creates client-side defaults or fabricated
empty production results. Service-name lookup failure leaves IDs as a safe
fallback, with retry feedback on the registry.

## Query keys, refresh and tenant isolation

`useAlerts.ts` uses these keys:

```text
["alerts", organisationId, "list", serviceIdOrNull, filtersAndPage]
["alerts", organisationId, "detail", alertId]
["alerts", organisationId, "summary"]
["alert-rule", organisationId, serviceId]
["alert-rule-draft", organisationId, serviceId]
["monitored-services", organisationId]  // existing shared service-name cache
```

The alert organisation prefix is `["alerts", organisationId]`. Rule saves place
the verified response into the captured tenant/service rule cache, then invalidate
that rule key and the organisation alert prefix (lists, details, summary).
Requests and callbacks retain their original scope. A late save may invalidate
the original tenant's caches, but cannot navigate or show success in the newly
selected tenant. No optimistic alert or rule mutation is used.

New query hooks explicitly disable previous-query placeholders, automatic retries
and background polling. They revalidate on mount/key change (`staleTime: 0`,
refetchOnMount always), with focus refetch disabled. Cached response bodies are
withheld until that observer has a successful fetch; errors hide cached bodies.
This also works when global defaults use keepPreviousData. The tests exercise
returning to a cached previous page and clearing filters, preventing a permanent
loading state during those key changes.

Refresh is manual: registry, summary, detail and service-history controls. Saves
invalidate affected active queries. There is no streaming or alert polling loop;
the existing health-monitoring refresh behavior is unchanged. Requests for the
same active query key are shared by TanStack Query. Alert requests consume its
AbortSignal, and the existing service collection client now accepts an optional
signal for cancellable name lookups; existing callers remain compatible.

Returned alerts/rules must match the requested organisation, service when
specified, and detail ID. Lists also check requested filters, pagination and
duplicate alert IDs. Service-name collections are checked against tenant scope.
The backend summary DTO contains no organisationId, so its provenance cannot be
independently checked inside its JSON body: scope is enforced by its authenticated
request path, captured query key and cancellation/isolation behavior. No new
backend property is invented.

Pages and forms are keyed by organisation/service/detail identity. Changing
organisation resets registry filters/page; a service deep link is associated
with the tenant in which it was first opened. Late or aborted responses cannot
replace active-tenant data. Access-denied cached data is hidden by query error
states and re-entry validation. This does not replace backend tenant isolation
or detect permission changes before another request is made.

## Rule validation, permissions and drafts

The form uses React Hook Form with the shared Zod input schema. Both thresholds
are required integers in 1-20; severity is LOW/MEDIUM/HIGH/CRITICAL; enabled is a
boolean. Effective defaults come from the verified GET response, including null
rule identity and timestamps. Monitoring disabled and alerting disabled are
explained separately. Both must be enabled for evaluation.

The current Organisation response/context has **no authoritative role field**.
The UI cannot distinguish VIEWER from a writer before attempting PUT. It does
not infer roles from JWT claims, service data or organisation ownership. The
form explains that OWNER/ADMIN/ENGINEER permissions are verified by the server.
A write 403 makes that editor read-only, reports that nothing was saved, and
allows discarding its draft to view confirmed values. Read 403/404 hides cached
configuration. No backend authorization change is necessary or made. A future
authoritative membership capability endpoint could make VIEWER read-only before
any edit attempt.

The save button and fieldset show pending state; an immediate submission guard
prevents duplicate PUTs. Success appears only after verified server confirmation
and scoped invalidation. Network/validation errors retain input values.

Unsaved values and their confirmed baseline are stored only in the scoped
in-memory QueryClient draft key. Its gcTime is infinite until save/discard or
the tab/application session ends, so navigating away and back does not silently
lose a draft. Drafts are never stored in localStorage, sessionStorage or cookies.
The browser's beforeunload warning protects reload/tab-close while dirty. Closing
or reloading loses drafts if the user chooses to leave. Draft restoration occurs
only after a fresh authorised rule GET succeeds.

If a subsequent rule GET differs from the draft's baseline, saving is blocked
until the user discards and reviews the new confirmed configuration. This is a
client safeguard, not atomic conflict detection: the current PUT API has no
ETag/version precondition, so simultaneous edits between GET and PUT retain the
backend's last-writer-wins behavior.

## Tests and validation

New API tests exercise contract validation, query encoding, bearer/signal reuse,
pagination, lifecycle/severity validation, foreign responses, rule defaults and
confirmed saves, invalid thresholds and HTTP errors. New route/component tests
use the real authenticated clients, OrganisationProvider, router and QueryClient
with controlled fetch fixtures, covering:

- Empty/loading/error/retry states, active/resolved records and detail evidence.
- Combined filters, local date conversion, invalid date order and pagination.
- Summary counts, Dashboard integration and bounded service history.
- Save pending/deduplication, validation, success, error and write-403 read-only
  behavior; scoped invalidation and late mutation completion.
- Unsaved draft restoration/discard, beforeunload warning, stale server values
  and tenant-scoped draft separation.
- Cached access denial, malformed cross-tenant bodies, tenant switching,
  cancellation and delayed list/summary responses.
- HTTP-only configuration, protected routes and keyboard/mobile Alerts navigation.

Existing regression fixtures now supply empty responses for the new endpoints.
Assertions claiming alerting was unimplemented are updated for the real feature;
existing service, monitoring, authentication and tenant assertions remain.

Commands from `frontend` (npm.cmd avoids the environment's npm.ps1 prefix-access
warning; these run the same package scripts):

```powershell
npm.cmd run test:run
npm.cmd run lint
npm.cmd run build
```

Repository check: `git diff --check`.

The initial regression run exposed missing new-endpoint fixtures. Later tests
found the cached-query re-entry loading bug, which was corrected by key-change
revalidation. An unused import, test option typing and a redundant section header
landmark were also corrected. The intermediate complete run passed 282 tests,
lint and build. After the final summary-refresh and draft-retention safeguards,
the complete run passed **286 tests across 16 files, zero failures and zero
skips** (223 existing plus 63 additional cases). `npm.cmd run lint` passed with
zero warnings or errors. `npm.cmd run build` passed TypeScript and Vite production
compilation. `git diff --check` passed, and no backend/dependency/migration file
was modified. Logs remain in ignored local files `frontend/alerting-tests.log`,
`frontend/alerting-lint.log` and `frontend/alerting-build.log`.

These are jsdom/React Testing Library tests. They exercise DOM semantics and
navigation events, not actual pixel layout, native browser focus trapping,
Keycloak login or live PostgreSQL data. No browser screenshot or visual viewport
check is claimed for this increment.

## Manual verification

1. Start the current backend normally with its existing database/Keycloak setup
   and V8 schema, then run the existing frontend development command. Sign in
   through Keycloak; select an authorised organisation.
2. Open Alerts from desktop navigation and from the keyboard-operated mobile
   drawer. Check summary, names/IDs, status/severity labels and detail links.
3. Apply each filter, a combined filter, a valid date range and a reversed range.
   Clear filters, move between pages and refresh list/summary. Counts should
   remain organisation-wide while the list is filtered.
4. Open an ACTIVE and a RESOLVED alert. Compare evidence IDs, timestamps,
   thresholds, code and duration with the authorised backend response. Follow
   the service link and its bounded history/full-history link.
5. On an HTTP service, edit thresholds/severity/enabled and save. Verify pending,
   confirmed success and a reload showing the saved rule. Enter 0, 21 or a decimal
   threshold and confirm no PUT occurs. Check that monitoring disabled is
   distinguished from alerting disabled. Non-HTTP services must not show a rule.
6. Change a rule without saving, navigate away/back and switch organisations.
   Confirm draft restoration only in its original scope, discard behavior and a
   browser warning on reload. With a VIEWER token, confirm PUT is denied and the
   editor becomes read-only; no backend rule must change.
7. Switch between two authorised organisations with different alerts while a
   network request is delayed. Check there are no previous-tenant rows, counts,
   details or save messages. Revoke membership and refresh/re-enter: cached
   protected data must be hidden once the denial is received.
8. Verify Dashboard service/health metrics remain correct alongside real alert
   counts. Exercise offline/server-error/empty responses using browser network
   tools without changing business data. No error should become a fake zero.
9. Inspect 320, 390, 768, 977, 1280 and 1440px viewports and long service/organisation
   names. Confirm compact rows/cards, usable filters/actions, wrapped evidence,
   visible focus and no horizontal page scroll. Actual visual checks remain
   manual until executed in an isolated OpsPilot browser.

## File inventory

Created:

- `frontend/src/types/Alert.ts`
- `frontend/src/api/alertApi.ts`
- `frontend/src/api/alertRuleApi.ts`
- `frontend/src/hooks/useAlerts.ts`
- `frontend/src/components/AlertPresentation.tsx`
- `frontend/src/components/AlertOverview.tsx`
- `frontend/src/components/ServiceAlerts.tsx`
- `frontend/src/pages/AlertsPage.tsx`
- `frontend/src/pages/AlertDetailsPage.tsx`
- `frontend/src/styles/alerts.css`
- `frontend/src/test/alertFixtures.ts`
- `frontend/src/test/alertApi.test.ts`
- `frontend/src/test/Alerting.test.tsx`
- `docs/alerting-frontend-phase-3c-step-4.md` (this file)

Modified:

- `frontend/src/App.tsx`
- `frontend/src/components/Navigation.tsx`
- `frontend/src/pages/DashboardPage.tsx`
- `frontend/src/pages/ServiceDetailsPage.tsx`
- `frontend/src/api/monitoredServiceApi.ts`
- `frontend/src/test/AuthRoutes.test.tsx`
- `frontend/src/test/DashboardPage.test.tsx`
- `frontend/src/test/Monitoring.test.tsx`
- `frontend/src/test/ResponsiveLayout.test.tsx`
- `frontend/src/test/ServiceDetailsPage.test.tsx`
- `frontend/src/test/TenantIsolation.test.tsx`

## Remaining limitations

- No authoritative frontend role contract exists; backend denial is authoritative
  and pre-emptive VIEWER detection is unavailable.
- Alerts refresh through REST/manual controls, not streaming or real-time push.
- Pagination has the backend's normal concurrent-insert offset limitations.
- Rule PUT has no atomic version check. Drafts are tab-memory only and are lost
  if the user confirms reload/close; permission revocation is detected on requests.
- Combined filters/page are not encoded as shareable URL state in this increment.
- Browser-level responsive, live Keycloak and live-data checks have not been run.
