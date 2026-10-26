# Phase 3C Step 3 - Read-only Alert Management API

## Repository and scope

Work started on `feat/alerting` with a clean working tree. Phase 3C.2 was already
committed as `05b2a91` (`automatic motor`), following Phase 3C.1 `251a9f2`.
The existing Alert domain, evaluator, state, Flyway V8 and lifecycle are reused.
No evaluator, scheduler, rule or authentication behavior is rewritten.

This increment adds authenticated query endpoints only. There is no alert
mutation, notification, incident handling, frontend work or new dependency. The
local backend was not restarted and the development database was not modified.
All database test fixtures use disposable PostgreSQL 18.4 Testcontainers.

## Endpoints

All paths start with `/api/organisations/{organisationId}` and require
`Authorization: Bearer <validated Keycloak access token>`.

| Method | Relative path | Response |
| --- | --- | --- |
| GET | `/alerts` | `AlertPageResponse` for the organisation |
| GET | `/alerts/{alertId}` | `AlertResponse` within that organisation |
| GET | `/monitored-services/{serviceId}/alerts` | `AlertPageResponse` scoped to the path's service |
| GET | `/alerts/summary` | `AlertSummaryResponse` for the entire organisation |

Both list endpoints include ACTIVE and RESOLVED alerts by default. The summary
is unfiltered and unpaginated; it always counts the requested organisation's
complete persisted alert history. No GET invokes evaluation, inserts defaults,
resets counters, resolves alerts or otherwise changes business data. POST, PUT,
PATCH and DELETE on these alert-query paths are not implemented (405 for an
authenticated request). The existing `/alert-rule` GET/PUT contract is unchanged.

Examples (replace the angle-bracket IDs; no token is embedded in these URLs):

```text
GET /api/organisations/<orgId>/alerts
GET /api/organisations/<orgId>/alerts?page=1&size=20&status=ACTIVE&severity=HIGH
GET /api/organisations/<orgId>/alerts?serviceId=<serviceId>&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z
GET /api/organisations/<orgId>/alerts/<alertId>
GET /api/organisations/<orgId>/monitored-services/<serviceId>/alerts?status=RESOLVED&size=10
GET /api/organisations/<orgId>/alerts/summary
```

## List parameters, pagination and dates

| Parameter | Default | Validation and meaning |
| --- | --- | --- |
| `page` | 0 | Zero-based integer, nonnegative |
| `size` | 20 | Integer, 1-100 inclusive |
| `status` | absent | Exact enum: ACTIVE or RESOLVED |
| `severity` | absent | Exact enum: LOW, MEDIUM, HIGH, CRITICAL |
| `serviceId` | absent | UUID; organisation-wide list only; requires a scoped service lookup |
| `from` | absent | ISO-8601 instant with UTC/offset, inclusive on firstSeenAt |
| `to` | absent | ISO-8601 instant with UTC/offset, exclusive on firstSeenAt |

The service-history endpoint gets its service ID from the path and supports the
remaining filters identically. Filtering a collection by a nonexistent or
foreign service returns 404, rather than silently returning an empty collection.
A valid existing service with no alerts returns an empty page.

Filters combine with AND. Date bounds apply to **firstSeenAt**, the opening
streak's first failure, not lastSeenAt or resolvedAt. Either bound may be omitted.
If both are present, `from < to` is required. Local dates/times without an offset,
malformed dates, unsupported enums and invalid UUIDs return 400. ISO-8601 dates
must be in years 0001-9999 to reject Java instants outside a safe database range.
For a positive offset in a real URL, encode '+' as `%2B`, or use a query-parameter
encoder. Equivalent UTC and offset timestamps select the same records.

PostgreSQL stores microseconds; both filter bounds are rounded upward to the
next microsecond if necessary. This preserves exact `[from, to)` semantics when
clients supply nanoseconds against persisted microsecond-aligned timestamps.
An interval narrower than a microsecond may legitimately match no observations.

Ordering is fixed: **firstSeenAt DESC, id DESC**. UUID provides deterministic
ordering for equal first-seen instants. Clients cannot supply arbitrary database
sort expressions. Pagination happens in PostgreSQL using Spring Data JPA
PageRequest/Specification, not by slicing a Java list. JPA requires an integer
offset, so `page * size > 2147483647` is rejected with 400 as well. Oversized
integer literals fail request conversion with 400. A page beyond the last page
returns 200 with empty content and the matching collection's total count.

## JSON / TypeScript contracts

All IDs are UUID strings; timestamps are ISO-8601 strings representing instants.
Nullable persisted values are explicit JSON null. Counts use JSON numbers backed
by Java long; they are not health percentages or uptime statistics.

```typescript
type AlertStatus = 'ACTIVE' | 'RESOLVED';
type AlertSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
type AlertCategory = 'HTTP_AVAILABILITY';

interface AlertResponse {
  id: string;
  organisationId: string;
  monitoredServiceId: string;
  category: AlertCategory;
  severity: AlertSeverity;
  status: AlertStatus;
  firstSeenAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
  occurrenceCount: number;
  failureThreshold: number;
  recoveryThreshold: number;
  resolvedAfterRecoveries: number | null;
  firstFailureHealthCheckResultId: string;
  triggeringHealthCheckResultId: string;
  lastFailureHealthCheckResultId: string;
  resolutionHealthCheckResultId: string | null;
  triggeringHttpStatusCode: number | null;
  triggeringResponseTimeMs: number;
}

interface AlertPageResponse {
  content: AlertResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

interface AlertSummaryResponse {
  totalAlerts: number;
  activeAlerts: number;
  resolvedAlerts: number;
  activeBySeverity: Record<AlertSeverity, number>;
}

interface ApiError {
  status: number;
  message: string;
  errors: Record<string, string>;
}
```

Lists and details use the same AlertResponse. Evidence IDs belong to the alert's
organisation and service under existing V8 composite foreign keys. The triggering
HTTP code/duration are safe scalar evidence. Stored failure diagnostic text,
target URLs, credentials, user identities and JPA/proxy internals are excluded.
No new health-evidence endpoint is introduced.

Severity and failure/recovery thresholds are the existing opening snapshots.
`resolvedAfterRecoveries` records the threshold actually used when resolution
occurred, which can differ after a rule change. `lastSeenAt` represents the last
matching DOWN, not the last attempted probe. Occurrence count includes the
opening failures and subsequent matching failures while ACTIVE. ACTIVE alerts
have null resolution fields. A later outage receives a different alert ID;
historical RESOLVED rows remain visible even after configuration changes.

Empty first-page example:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

Summary example (illustrative only):

```json
{
  "totalAlerts": 25,
  "activeAlerts": 4,
  "resolvedAlerts": 21,
  "activeBySeverity": { "LOW": 1, "MEDIUM": 1, "HIGH": 2, "CRITICAL": 0 }
}
```

## Authentication, authorization and HTTP statuses

The existing Spring Security resource server validates signature, issuer,
audience and token expiration before controller invocation. Controllers extract
only the validated JWT subject. AlertQueryService resolves the local ACTIVE
OpsPilot user and requires database-backed organisation membership before any
alert or service query. OWNER, ADMIN, ENGINEER and VIEWER all have read access;
JWT role claims do not replace local membership or grant additional access.

Every list Specification includes an organisation predicate, even for service
filters. Details use `findByIdAndOrganisationId`, never a global ID lookup.
Summary JPQL always constrains organisation ID. Service history and service
filters first check `findByIdAndOrganisationId`. Membership in both organisations
does not permit mixing a service or alert ID into the wrong organisation path.
Revocation is checked again on the next request with the same valid token.

| HTTP status | Meaning |
| --- | --- |
| 200 | Successful DTO/page/summary, including empty collections |
| 400 | Invalid pagination, UUID, enum, timestamp or date range |
| 401 | Missing, malformed or invalid JWT, including wrong issuer/audience, expired or incorrectly signed tokens |
| 403 | Local OpsPilot account is inactive, following existing account-status policy |
| 404 | Alert/service absent from requested organisation; missing local user; or no organisation membership |
| 405 | Unsupported mutation method on these read-only alert paths |

Application errors reuse `{status, message, errors}` through
GlobalExceptionHandler. New alert misses use the same `Alert not found` response
for foreign and random IDs. Membership denial intentionally uses the existing
`Organisation membership not found` 404 for both existing and unknown tenants.
Service misses retain the existing safe error message containing only the
caller-supplied service ID. Conversion errors use `Invalid request parameter`.
No internal SQL errors or diagnostic text is included in these responses.

## Query strategy and consistency

AlertQueryService uses read-only transactions. AlertQuery validates pagination
and safe date bounds at the application boundary. A parameterised JPA Criteria
Specification supplies only requested filter predicates plus mandatory tenant
scope. Fixed PageRequest sorting and SQL limits/offsets return at most 100 rows;
Spring Data performs a filtered COUNT query when needed for page metadata.
Alert has scalar association IDs, so DTO mapping triggers no lazy service or
organisation traversal and no N+1 history lookups.

The summary executes one parameterised JPQL GROUP BY status, severity query.
It returns at most eight aggregated groups. Java fills the four explicit
severity keys with zero when absent and sums those bounded counts; it never
loads alert entities to calculate the summary. All counts in one summary come
from that single database statement.

Existing V8 indexes include organisation/service/first-seen history, the unique
active service/category index and primary-key lookup. The history index's tenant
prefix supports tenant restriction, and the service prefix supports service
history. PostgreSQL performs any additional organisation-wide or UUID tie-break
sorting. No evidence from this increment demonstrates a need for another index,
so no V9 migration is added and V1-V8 remain unchanged. Large production histories
should be evaluated with representative EXPLAIN ANALYZE before adding indexes.
These correctness fixtures are not a production-scale performance benchmark.

Default READ COMMITTED isolation is retained. A page and its count may reflect
nearby committed states during simultaneous alert updates; offset pagination
can shift between requests when new alerts arrive. The API provides stable
ordering for an unchanged dataset, not a multi-request historical snapshot.
There is no server-side query cache. Future React query keys must include the
organisation and filters and must clear denied tenant data when access changes.

## Tests and execution

New unit tests verify membership-before-query ordering, account denial, scoped
details/service lookup, fixed database pagination/sorting, aggregate-only summary,
pagination bounds, date validation and sub-microsecond filter normalization.

New PostgreSQL + MockMvc integration tests verify empty results, ACTIVE/RESOLVED
DTOs and evidence, deterministic tied timestamps, default/maximum pagination,
individual/combined filters, inclusive/exclusive dates, invalid inputs, all four
local roles, inactive/unprovisioned accounts, foreign IDs and non-members, JWT
rejection, severity summaries and absence of mutation routes. A snapshot of all
eight business tables proves GET does not change persisted data. A health-store
integration test opens and resolves a real alert and reads both transitions.

The existing real-HTTP/JWT tenant-isolation suite is extended for all four
endpoints, covering missing/malformed JWTs, wrong audience/issuer, expiry,
incorrect signatures, membership revocation, mixed tenant IDs, dual membership
and actual persisted alerts. UTC and URL-encoded offset filters are also tested
through real HTTP. No existing test assertions are removed or weakened.

Validation commands from `backend`:

```powershell
.\mvnw.cmd test
# Existing wrapper fails before Maven starts; use the cached offline distribution.
$cachedMaven = 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
& $cachedMaven -o '-Dtest=AlertQueryServiceTest,AlertManagementIntegrationTest,TenantIsolationIntegrationTest' test
& $cachedMaven -o test
& $cachedMaven -o -DskipTests package
# From the repository root:
git diff --check
```

The first focused run caught and corrected a new date-validator initialization
error: Java requires a '+' prefix for the year-10000 exclusive upper boundary.
The next run exposed MockMvc double encoding in a new offset-timestamp fixture;
the fixture now supplies a decoded parameter and a separate real HTTP test
verifies `%2B` encoding. The corrected focused run passed **91 tests, zero
failures, errors or skips**, including all 40 real-JWT tenant-isolation cases.
The complete backend suite passed **524 tests, zero failures, errors or skips**
(463 prior cases plus 61 new cases). The existing evaluator/monitoring tests
remain green, and all eight existing Flyway migrations and Hibernate schema
validation pass against disposable PostgreSQL. No migration was added here.

The wrapper was attempted first and still fails before Maven starts with its
existing PowerShell null-array error. Cached Maven 3.9.16 offline completed
validation without installing dependencies or changing the wrapper. The
packaging command passed and produced the executable Spring Boot JAR
`backend/target/backend-0.0.1-SNAPSHOT.jar`. It skips rerunning tests only after
the complete successful test run. `git diff --check` passes. Execution logs are ignored local backend files:
`alert-api-wrapper.log`, `alert-api-focused-tests.log`, `alert-api-full-tests.log`
and `alert-api-package.log`.

## Files

New files:

- `backend/src/main/java/com/opspilot/backend/application/AlertQuery.java`
- `backend/src/main/java/com/opspilot/backend/application/AlertQueryService.java`
- `backend/src/main/java/com/opspilot/backend/application/exception/AlertNotFoundException.java`
- `backend/src/main/java/com/opspilot/backend/application/exception/InvalidAlertQueryException.java`
- `backend/src/main/java/com/opspilot/backend/web/AlertController.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/AlertResponse.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/AlertPageResponse.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/AlertSummaryResponse.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertQueryServiceTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertManagementIntegrationTest.java`
- `docs/alert-management-api-phase-3c-step-3.md` (this file)

Modified files:

- `backend/src/main/java/com/opspilot/backend/domain/AlertRepository.java`
- `backend/src/main/java/com/opspilot/backend/web/exception/GlobalExceptionHandler.java`
- `backend/src/test/java/com/opspilot/backend/web/TenantIsolationIntegrationTest.java`

## Known limitations

- No browser/React integration is implemented or claimed in this increment.
- No manual mutation or notification API exists; lifecycle is evaluator-owned.
- Offset pagination, ordinary transaction isolation and large-history performance
  limits are described above. Java long counts beyond JavaScript's safe integer
  range would need a future string-count contract at extraordinary scale.
- Existing evaluator ordering, process-local scheduling, evidence-retention and
  clock-synchronisation limits remain documented in
  `alert-evaluation-phase-3c-step-2.md`.
- Deployment still requires the existing V8 schema. Flyway applies migrations
  through normal application startup; no development DB operation is performed
  for this read-only API increment.
