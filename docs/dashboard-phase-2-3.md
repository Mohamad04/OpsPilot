# Dashboard — Phase 2.3

## Implementation

The previous dashboard contained fixed service, alert, and incident numbers.
The replacement uses `getMonitoredServices(organisationId)`, through the existing
authenticated API client, and the same TanStack Query key as the Services registry:
`["monitored-services", organisationId]`. The cached value remains the original
service array, so existing registration and enable/disable invalidations work
without changes to other pages or API contracts.

The dashboard derives total registered services, enabled services, disabled
services, and counts for Development, Test, Staging, and Production. Empty
service arrays produce real zero configuration totals and a first-service
registration action. Metrics are not rendered while the initial request is
pending. Errors, including failed refreshes and permission failures, hide metrics
and provide an explicit retry. Cached data for the current organisation can be
shown while a refresh is pending, with a visible refresh notice.

The selected organisation ID is part of the query key and the API path.
`placeholderData` is explicitly disabled so previous-tenant data is never used
while switching. No server totals are copied into component state. In addition,
the page checks each returned record's `organisationId` before aggregating it;
an unexpected tenant response produces an error instead of metrics. This is a
defensive UI check; backend authentication and RBAC remain authoritative.

The dashboard includes Services registry and Add service links. Health monitoring,
uptime calculations, alerts, and incidents are individually labelled **Not
implemented**. There are no fabricated health, uptime, alert, or incident metrics.
Enabled/Disabled are explicitly explained as configuration states.

## Responsive design

`dashboard.css` is scoped to the dashboard and reuses the established tokens,
card/button foundations, Icon, and PageState. It introduces no dependencies.

- Below 36rem of available page width: one metric column.
- From 36rem: two metric columns.
- From 48rem: three metric columns and two lower information sections.
- Below 30rem: header actions stack and use the available width.

The breakpoints use page container width, accounting for the existing sidebar.
Long organisation names, descriptions, badges, and numbers wrap safely. Navigation
remains standard keyboard-accessible links with existing focus/touch styles.

## Files for this phase

- Modified: `frontend/src/pages/DashboardPage.tsx`.
- Created: `frontend/src/styles/dashboard.css`.
- Created: `frontend/src/test/DashboardPage.test.tsx`.
- Created: `docs/dashboard-phase-2-3.md`.

Pre-existing uncommitted Services List/Create Service changes are preserved.
No other pages, shared providers, API clients, authentication, schemas, package
manifests, or backend files were modified in this phase.

## Automated verification

The 25 focused dashboard tests use the real OrganisationProvider, API clients,
and query cache with controlled HTTP responses. They cover:

- Zero, one, many, all-enabled, and all-disabled service totals.
- Environment aggregation, long names, and larger counts.
- Bearer headers and the organisation-scoped endpoint.
- Raw cache compatibility and registry navigation.
- First-service and existing Create Organisation entry points.
- Initial loading, network errors, retry, 401/403, and failed refreshes.
- Organisation switching, including global `keepPreviousData` defaults.
- Late previous-tenant responses and switching back to a cached tenant.
- Existing list invalidation updating the displayed totals.
- Defensive rejection of cross-tenant response records.
- Unavailable monitoring features without invented statistics.

The focused command `npm.cmd run test:run -- src/test/DashboardPage.test.tsx`
passed all 25 tests. `npm.cmd run lint` and `npm.cmd run build` also passed.
The first focused run exposed test-harness issues: response bodies shared by
StrictMode requests and a short initial two-request wait. Each mocked HTTP request
now receives a cloned response body, and dashboard readiness uses a bounded wait.

The default full-suite command was executed and reported 154/171 passing, with
17 failures during a heavily delayed run. A one-worker rerun reported 169/171
passing; the remaining failures were the existing Services pagination test's
5-second timeout and the existing organisation-creation success-message wait.
Neither of those test files nor their page implementations was changed here.
The final full-suite run with one worker and a 15-second test timeout passed all
171 tests across 11 files. The existing test assertions and project test
configuration were not changed to obtain this result.

| Command (from `frontend`) | Actual result |
| --- | --- |
| `npm.cmd run lint` | Passed |
| `npm.cmd run test:run -- src/test/DashboardPage.test.tsx` | Passed: 25/25 |
| `npm.cmd run test:run` | 154/171 passed; 17 failures in the delayed parallel run |
| `npm.cmd run test:run -- --maxWorkers=1` | 169/171 passed; two existing timing failures |
| `npm.cmd run test:run -- --maxWorkers=1 --testTimeout=15000` | Passed: 171/171, 11 files |
| `npm.cmd run build` | Passed |

## Browser verification and remaining limits

A live desktop dashboard was partially observed, but browser screenshots also
captured an overlapping browser window. Automatic approval review blocked
additional capture because it could expose unrelated private content. Only the
empty window created during test setup was closed. Further browser work is
pending an isolated OpsPilot window and approval for task-specific capture.

**No requested viewport has a completed visual pass.** The widths below are
verification targets; jsdom tests do not establish CSS layout or overflow.
No browser integration flow or real-user tenant switch is claimed as tested.

## Manual verification

1. Use the project's existing PostgreSQL, Keycloak, backend, and frontend setup.
   Open `http://localhost:5173`, complete the normal Keycloak login, and select an
   organisation. No additional configuration is required by this phase.
2. Compare Dashboard totals with the complete Services registry for that
   organisation. Verify total = enabled + disabled. Compare environment counts.
3. With permitted workflows, create a service or change a service's enabled
   configuration, then return to Dashboard. Confirm the existing list query
   invalidation/refetch updates counts. No health interpretation should appear.
4. Switch between organisations with different service counts. On a slower
   connection, verify the new tenant's loading state and that a late response for
   the old tenant cannot change the displayed metrics. An empty organisation
   should show zero service totals and the first-service action.
5. Follow View services, Open service registry, Add service, and the empty-state
   registration link. Confirm the existing routes and selected organisation.
6. Test **320, 390, 768, 977, 1280, and 1440px**. Expected layouts with the standard
   shell are one metric column on 320/390px, an adaptive two-column grid around
   768px, and three metric columns with two lower sections when available page
   width reaches 48rem. Check expanded/collapsed sidebar, long names, 200% zoom,
   keyboard focus, touch targets, and no horizontal page scrolling.
7. Verify error/retry behavior using browser offline mode. On a failed refresh,
   totals must be hidden rather than presented as current. Permission failures
   remain backend-enforced; do not change roles or security to bypass them.
8. Confirm Health monitoring, Uptime calculations, Alerts, and Incidents all say
   Not implemented and show no invented percentages or counts.

The current implementation aggregates the existing full service-list response.
If the backend later introduces server pagination, these metrics will need a
complete data source or a dedicated aggregate endpoint. They are snapshots of
service configuration, not streaming monitoring results.
