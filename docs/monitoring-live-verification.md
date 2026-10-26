# Phase 3A/3B live verification — 26 October 2026

This is a follow-up to [the Phase 3B implementation report](monitoring-dashboard-phase-3b.md).
The initial V1–V5 database snapshot in that report is now superseded: V6 is deployed
and the existing portfolio is receiving persisted scheduled checks.

## Environment and migration investigation

Read-only inspection identified the backend listening on local port 8080 as
`com.opspilot.backend.OpsPilotBackendApplication`, PID 44628, started by IntelliJ
at 16:43:45 local time using the Microsoft Java 21 installation and this project's
backend classpath. The existing IDE run configuration points to
`jdbc:postgresql://localhost:5432/opspilot`. Its credentials were inspected only
for presence and were neither printed, changed nor copied into documentation.

Docker's PostgreSQL container is `opspilot-postgres-1`, publishing port 5432. Its
Compose project/working-directory labels point to this repository's
`infrastructure/compose.yaml`. Database identity is `opspilot`; JDBC connections
started at approximately 15:43:49–50 UTC, matching backend startup.

On inspection, Flyway already contained successful V1–V6 entries. V6 was installed
at `2026-10-26 16:43:50.171484` (Flyway's timestamp without time zone), taking 85 ms,
with checksum `-1643966319`. This continuation did **not** restart the backend or
apply SQL manually, because normal startup had already applied the migration.

The prior V1–V5 observation preceded this backend startup. There is no current
migration failure or need for repair/baseline. The evidence shows V6 was applied
on the current normal startup; it cannot reconstruct the exact classpath or
configuration of the earlier, no-longer-running process, so an old runtime is a
plausible explanation rather than a separately proven historical fact.

V6 source and the compiled classpath resource have matching SHA-256:
`9B89C311382C947C8DD6F6221F216D25819396D3209E46F8FDCA56566A4AC67E`.

### Data-loss review

V6 adds a unique `(id, organisation_id)` constraint to existing services, creates
`health_check_results`, adds its tenant-enforcing foreign key and validation
constraints, and creates its history index. It contains no DROP, DELETE, TRUNCATE,
column removal or data rewrite. The unique constraint may briefly lock the service
table during application. Its `ON DELETE CASCADE` applies only to future deletion
of a parent service and its monitoring results; applying V6 does not delete a service.
Deployed constraints/indexes match this design.

No volume reset, Flyway repair/baseline, service update, role change, credential
change or outbound-security exception was performed.

## Scheduler and PostgreSQL evidence

The unchanged existing registration is:

```text
organisation: 3b1a3d12-8513-4ab1-b399-8736aac49e12 (cedra)
service:      44ca654d-652a-4f8f-86e0-f5ff5abf79bb (portfolio)
type: HTTP; enabled: true
base URL: https://portfolio.cedraforge.dev
health endpoint: /
```

Two read-only snapshots showed the portfolio result count growing from **3 to 5**
without manually invoking checks or changing the registration. Observed checks:

| Started (UTC) | Status | HTTP | Response time |
| --- | --- | --- | --- |
| 15:44:09.105630 | UP | 200 | 527 ms |
| 15:45:11.026531 | UP | 200 | 327 ms |
| 15:46:12.522360 | UP | 200 | 238 ms |
| 15:47:14.208831 | UP | 200 | 313 ms |
| 15:48:15.555250 | UP | 200 | 221 ms |

The first recorded result was `323bd783-84e7-4c88-8a53-a71664eaa46e`;
the 15:46 result was `fb9c9006-3ad1-4f11-a536-e46b80f2178b`.
All inspected portfolio results used the expected organisation/service IDs and
had no failure detail. The cadence agrees with a 60-second delay after each
completed scheduler round, rather than an exact wall-clock minute.

A later snapshot contained **8 portfolio checks**, newest at
`2026-10-26T15:51:20.522305Z`, completed at `15:51:20.875012Z`, again UP / HTTP 200,
352 ms and no failure detail.

Read-only checks across stored observations found zero invalid tenant links and
zero invalid timestamp/status/duration/UP-code combinations. Other existing
services produced real DOWN records with `DNS resolution failed.`, HTTP 301 and
HTTP 403 errors. Redirect failures explicitly state that redirects are not
followed. Errors are persisted instead of converted to successful checks; no
existing service was altered to generate these cases.

`GET /actuator/health` returned HTTP 200 with status UP. An unauthenticated
portfolio latest request and a history request with a deliberately invalid bearer
token both returned HTTP 401 / `Authentication required`.

SQL was executed with `PGOPTIONS=-c default_transaction_read_only=on` using the
container's existing credentials internally. No credential values or valid JWTs
were printed. Business-table counts at inspection were 4 services, 3 organisations,
2 users and 2 memberships. Content fingerprints were recorded for a final
unchanged-data comparison. The final comparison matched all four fingerprints
exactly: no existing service, organisation, user or membership changed during
verification. Only the running scheduler added monitoring results.

## Authenticated API and browser verification

The initial continuation could not safely access an isolated browser window:

- Compare authorised `/health/latest` and `/health/history?limit=20` responses
  with PostgreSQL IDs, timestamps, status, HTTP code and duration.
- Confirm actual values in Dashboard, Services and Service Details.
- Check 320, 390, 768, 977, 1280 and 1440 CSS-pixel layouts and navigation.

The native helper listed a `frontend` browser window but its subsequent state
capture returned a different active tab. Screenshots were disabled. Browser
inspection was stopped, and automatic approval review rejected a browser-state
request because the current window contained unrelated/private conversation
content. No desktop-capture workaround was attempted. A safer URL-scoped browser
connector lookup for `http://localhost:5173` returned **No browser is available**;
it did not inspect other windows. The user was asked to move OpsPilot into its own
signed-in browser window before further browser work. No screenshots were taken.

### Resumed verification after the user isolated and signed in

The user subsequently confirmed an isolated, signed-in window at
`http://localhost:5173`. A fresh window inventory returned exactly one OpsPilot
candidate, `frontend - Brave`, window ID 24056248. Its handle/title were validated
before activation. A text-only accessibility read then confirmed:

- The same window ID, URL `http://localhost:5173/`, and only one frontend tab.
- Selected organisation **cedra**, with authenticated account controls.
- Dashboard configuration totals: **1 registered, 1 enabled, 0 disabled**.
- Monitoring totals: **UP 1, DOWN 0, UNKNOWN 0**, with **0 paused services**.
- Production environment count 1; the other three environment counts 0.
- Scheduled HTTP monitoring described as available, with uptime calculations,
  alerts and incidents still labelled unimplemented.

This is a real rendered Dashboard observation consistent with the previously
verified portfolio registration and persisted UP checks. It is not a new SQL
snapshot, an inspection of raw HTTP response bodies, or proof of a fresh API 200.

The first navigation attempt, clicking the observed Services link in that exact
window, failed with **`coordinate input geometry is unavailable`**. The requested
post-action capture was not reached. Following the user's explicit stop condition,
no further browser inspection, input retry, alternative targeting method or
capture workaround was attempted. No screenshots were captured in this resumed
attempt, and no unrelated window content was read. The exact viewport width was
not established, so the Dashboard observation is not counted as any of the six
specified responsive checks.

Final live verification matrix:

| Check | Actual result |
| --- | --- |
| Isolated localhost window and signed-in rendered UI | Verified by text-only accessibility state |
| Dashboard totals for selected cedra organisation | Verified: 1 registered/enabled, UP 1 / DOWN 0 / UNKNOWN 0 |
| Authorised latest/history HTTP status and response bodies compared with persisted records | Not executed; manual verification required |
| Services / Service Details real monitoring display | Not verified; navigation attempt failed |
| 320 px responsive layout | Not executed |
| 390 px responsive layout | Not executed |
| 768 px responsive layout | Not executed |
| 977 px responsive layout | Not executed |
| 1280 px responsive layout | Not executed |
| 1440 px responsive layout | Not executed |
| Live organisation switching / delayed-response cache consistency | Not executed |

Earlier automated tenant/cache/delayed-response tests remain separate evidence;
they are not substituted for the unexecuted live browser checks. No live
implementation defect was reproduced: the interruption is an automation-input
limitation, not evidence that the Services route is broken.

### Exact remaining manual verification

Keep the existing backend running. Do not apply migrations again, edit service
records, change memberships, disable outbound security or copy bearer tokens.

1. In the isolated signed-in browser, select **cedra**. Open DevTools **Network**,
   filter Fetch/XHR by `/health/`, and leave request headers/tokens private.
2. Open **Services**, then **portfolio**, and click **Refresh monitoring** on
   Service Details. Inspect the two GET responses:

   ```text
   /api/organisations/3b1a3d12-8513-4ab1-b399-8736aac49e12/monitored-services/44ca654d-652a-4f8f-86e0-f5ff5abf79bb/health/latest
   /api/organisations/3b1a3d12-8513-4ab1-b399-8736aac49e12/monitored-services/44ca654d-652a-4f8f-86e0-f5ff5abf79bb/health/history?limit=20
   ```

   Both should return HTTP 200. Check matching organisation/service IDs, valid
   timestamps, actual status/code/duration/failure fields, and at most 20 history
   records ordered newest first. The latest record should appear in history;
   if a scheduler round completes between the requests, refresh and account for
   that timing difference instead of treating a newer observation as corruption.
   Compare a record ID and its fields with a read-only SQL lookup if exact
   API-to-database comparison is required. Do not export an unredacted HAR.
3. Confirm Services displays the latest observation separately from Enabled.
   On details, verify the same HTTP code, duration and absolute timestamp, plus
   bounded historical records. Return to Dashboard: monitoring totals should
   agree with current enabled HTTP observations in that organisation, allowing
   for the documented 60-second UI polling interval. Do not require a historical
   response duration to equal a newer scheduler result.
4. In DevTools device emulation, choose **Responsive** and set width in turn to
   **320, 390, 768, 977, 1280 and 1440 CSS pixels** at 100% browser zoom. At each
   width inspect Dashboard, Services and portfolio Details; record the viewport
   height used. Check no horizontal page scrolling, clipped actions, overlapping
   badges or broken timestamp/failure wrapping. Scroll through the full page and
   history. At mobile widths test hamburger open/close, Escape, keyboard focus
   return, organisation selector and account menu. Do not click Enable/Disable
   merely to test layout, because this verification preserves service records.
5. If an existing second organisation is already authorised for this account,
   switch to it and confirm its own configuration/monitoring counts and services.
   Use Network throttling to switch while a health read is pending; old tenant
   values must disappear while loading, and late responses must not replace the
   active tenant's values. Switch back and verify cached data stays correctly
   scoped. If no second authorised organisation is selectable, record this live
   test as unavailable; do not create organisations, memberships or test records
   as part of this remaining read-only verification.
6. Record results per page/width and API record ID. Any browser screenshot should
   contain only the isolated OpsPilot content, without unrelated windows or
   authentication/request-header secrets. Restore normal network throttling when
   finished. A failure should include the route, width, selected organisation,
   observed behavior and expected behavior, without JWTs.

## Changes and regression checks

Only verification documentation is changed in this continuation. Existing
uncommitted frontend implementation, prior reports and Git history are preserved.
No implementation defect has been reproduced or corrected. The previously
reported **223 frontend tests, lint and production build** remain the last
application-code checks; they are not newly executed live browser checks.
`git diff --check` passed after documentation changes (existing line-ending
normalization warnings only). Regression suites were not rerun for documentation.
The final resumed attempt changed this report and the Phase 3B report's status
note only. It did not restart the backend, repeat the successful database checks,
modify backend/frontend code or business records, or change Git history.
No Phase 3C, alerting or unrelated development is included.
