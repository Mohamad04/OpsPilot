# Phase 4C - Incident lifecycle and REST API

**Subsequent extension:** [Phase 4D assignment and comments](incident-assignment-comments-phase-4d.md)
adds `PATCH /{incidentId}/assignee` and `POST /{incidentId}/comments` under this
API's incident base path. It documents permissions, strict request bodies, keyed
comment retries, additive timeline metadata and Flyway V12. The original Phase 4C
scope and validation evidence below are retained as milestone history.

## Scope and repository state

Implementation continued on `feature/phase4-incident-management` from clean HEAD
`9764644`, which contains the complete Phase 4B implementation. Existing
[Phase 4A models](incident-domain-phase-4a.md) and
[Phase 4B correlation](automatic-incident-correlation-phase-4b.md) are extended;
the correlation algorithm, alert thresholds and scheduler are not redesigned.

This phase adds scoped incident reads, validated human status changes and timeline
reads. It does not add incident assignment management, human comment creation,
incident frontend pages, notifications, streaming, postmortems or infrastructure.
No dependencies, credentials, JWT settings or CORS policy are changed.

## Transition matrix

| Current status | Allowed next statuses |
| --- | --- |
| OPEN | ACKNOWLEDGED |
| ACKNOWLEDGED | INVESTIGATING, RESOLVED |
| INVESTIGATING | MITIGATED, RESOLVED |
| MITIGATED | INVESTIGATING, RESOLVED |
| RESOLVED | CLOSED, INVESTIGATING |
| CLOSED | INVESTIGATING |

All other transitions, including repetitions, return 409. New incidents still
start OPEN through Phase 4B; this API does not manually create incidents or reset
them to OPEN. ACKNOWLEDGED -> RESOLVED permits a quickly understood/cleared
incident without forcing unnecessary intermediate states. MITIGATED ->
INVESTIGATING represents an unsuccessful mitigation or renewed investigation.

Reopening is explicitly RESOLVED/CLOSED -> INVESTIGATING. It preserves the first
acknowledgement timestamp and clears the current resolution timestamp. Previous
resolution information remains in immutable timeline details. A later resolution
sets a fresh resolvedAt. Closing preserves resolvedAt. Creation time never changes.
Domain methods reject invalid transitions and timestamps before existing incident
creation/acknowledgement/resolution times.

### Active alerts and reopening conflicts

An incident cannot be resolved or closed while any associated alert is ACTIVE;
the service returns 409 without updating the incident or timeline. There is no
force/override option. Alert recovery does not automatically resolve an incident.
Disabling a monitoring service or alert rule does not resolve its existing alerts,
so that alone cannot satisfy this guard: actual recovery is required under the
existing alert lifecycle. A future override policy would be a separate feature.

Reopening a service-anchored incident is rejected with 409 when another ongoing
incident exists for the same organisation/service. The common service lock and
V10 partial unique index remain authoritative. Existing unanchored Phase 4A
incidents have no service correlation key and are locked individually.

## REST endpoints

All endpoints require the existing validated Keycloak bearer JWT and begin with:

`/api/organisations/{organisationId}/incidents`

| Method | Relative path | Result |
| --- | --- | --- |
| GET | empty | IncidentPageResponse |
| GET | `/{incidentId}` | IncidentResponse |
| PATCH | `/{incidentId}/status` | Updated IncidentResponse |
| GET | `/{incidentId}/events` | IncidentEventPageResponse |

List query parameters:

- `status`: optional exact IncidentStatus enum.
- `severity`: optional LOW, MEDIUM, HIGH or CRITICAL.
- `serviceId`: optional monitored-service UUID, verified within the organisation.
- `page`: zero-based, default 0, nonnegative.
- `size`: default 20, permitted 1-100.
- `sort`: `createdAt,desc` (default) or `createdAt,asc`. ID uses the same direction
  as a stable timestamp tie breaker. Other sort expressions are rejected.

Filters combine with AND. Pagination is executed in PostgreSQL; page*size cannot
exceed Integer.MAX_VALUE. Missing/foreign service filters return 404. A valid empty
result or page beyond the last page returns an empty content array, not an error.
There is no arbitrary sort expression or SQL interpolation from request input.

Timeline uses the same page/size defaults and bounds, ordered occurredAt ASC then
ID ASC. The incident itself is scoped/authorised before timeline data is queried.
There is no unbounded history or human comment POST endpoint.

Examples (IDs are placeholders, not test evidence):

```text
GET /api/organisations/{org}/incidents?status=INVESTIGATING&severity=HIGH&page=0&size=20
GET /api/organisations/{org}/incidents?serviceId={service}&sort=createdAt,asc
GET /api/organisations/{org}/incidents/{incident}
GET /api/organisations/{org}/incidents/{incident}/events?page=0&size=20
PATCH /api/organisations/{org}/incidents/{incident}/status
```

Example PATCH body, using the version just read from the incident:

```json
{
  "status": "ACKNOWLEDGED",
  "expectedVersion": 0
}
```

Both properties are required. Status is a JSON string with an exact enum value;
expectedVersion is a nonnegative int64 JSON integer. Floats, strings used as
versions, numeric enum ordinals and integer overflow return 400. Endpoint-local
Jackson deserializers follow the existing alert-rule DTO convention without
changing coercion for other APIs. The client cannot supply an actor, tenant
identity or role; unknown body fields never become authorization inputs.

### Response contracts

`IncidentResponse` contains:

- id, organisationId, nullable monitoredServiceId.
- title, description, severity and status.
- nullable assignedUserId (read-only in this phase).
- createdAt, nullable acknowledgedAt and resolvedAt, and version.

`IncidentEventResponse` contains:

- id, organisationId, incidentId and eventType.
- nullable actorUserId and sourceAlertId.
- occurredAt and bounded plain-text details.
- nullable previousStatus and newStatus. Legacy entries retain null metadata;
  new human STATUS_CHANGED entries contain both statuses.

Both page DTOs contain content, page, size, totalElements, totalPages, first and
last. DTOs copy their content lists and never expose JPA entities or user emails,
JWT subjects, credentials or database diagnostics. Event details are text; future
frontends must render them as text, not trusted HTML.

### HTTP results

| Status | Meaning |
| --- | --- |
| 200 | Successful retrieval or transition |
| 400 | Invalid JSON/type/enum/UUID, missing properties, pagination or sort |
| 401 | Missing, malformed or invalid JWT |
| 403 | VIEWER mutation or inactive local account |
| 404 | Missing local user/membership or unavailable scoped incident/service |
| 409 | Invalid/repeated transition, stale version, ACTIVE alert guard, reopening conflict or lock conflict |

Errors use the existing ApiError shape: status, message and errors. Concurrency
handlers use safe messages and do not return SQL or constraint diagnostic content.
Unexpected persistence failures are not converted into successful updates or
mislabelled business conflicts; the transaction fails and rolls back.

## Authorization and tenant isolation

The actual OrganisationRole values are OWNER, ADMIN, ENGINEER and VIEWER.
All four can read. Only OWNER, ADMIN and ENGINEER can change status. Roles are
resolved from current local memberships; signed realm-role claims cannot elevate
a VIEWER. AuthenticatedUserService continues to enforce the local account status.

Every application entry point resolves the JWT subject to a local user and
requires membership before accessing incident data. Every incident, service,
timeline and associated-alert query includes organisation scope. Foreign and
nonexistent incidents have the same 404 response. A VIEWER is denied mutation
before resource lookup, consistently with existing write APIs. Membership
revocation is enforced on the next request; assignment and historical authorship
do not grant access. No new security filter or frontend authorization is added.

## Transactions, versioning and timeline ordering

IncidentService owns transactions. GET operations are read-only; PATCH performs
the status update and audit append in one write transaction.

For anchored incidents, lock order is monitored-service row then incident row,
matching Phase 4B. An initial scoped projection obtains only the ID/service key,
so a waiting request does not first cache stale incident status/version in its
persistence context. The incident is loaded under its write lock afterward.
Unanchored incidents need only their own row lock.

The required expectedVersion is compared after locking. JPA @Version additionally
detects stale entity updates and increments on changed incident state, including
Phase 4B severity escalation. A response is produced only after flushing incident
and timeline writes, so it includes the updated version and insert failures cause
rollback. Two same-version requests produce one success and one 409 rather than
applying two sequential stale decisions. Clients must GET/review after a conflict;
there is no silent retry or unconditional last-writer-wins status operation.

The application checks reopening conflicts under the common lock; the existing
partial unique index remains the final safeguard. The specific index violation
is translated to a safe 409. Other integrity violations, such as an audit append
failure, propagate and roll back the incident status, version and timestamps.

Each successful transition appends one STATUS_CHANGED event with the authenticated
membership's user ID, typed previous/new status and the previous resolution time
in its text details. Failed, repeated or stale operations append nothing.

Injected Clock time is truncated to microseconds and bounded by existing incident
timestamps. Both lifecycle and automatic correlation appends choose a timestamp
strictly after the latest event when necessary. Their shared incident/service
locks make this consistent across cooperating instances. This preserves logical
per-incident order during equal clock ticks or clock regression; occurrence times
may therefore be slightly ahead of wall-clock time after such corrections.
The actual health recovery observation time remains available on the source alert.

## Flyway V11 and historical compatibility

`V11__add_incident_lifecycle_audit.sql` adds:

- incidents.version: nonnegative BIGINT, initially 0 for existing rows.
- Nullable previous_status/new_status event metadata with enum/pair checks. New
  populated metadata requires STATUS_CHANGED, a human actor and no alert source.
  Legacy events are not assigned invented status metadata.
- ALERT_RESOLVED in the existing event-type and automatic-source constraints.
- A unique recovery index spanning source-bearing COMMENT_ADDED and ALERT_RESOLVED,
  so the same alert cannot gain a duplicate recovery receipt after upgrade.

V9-V10 and all earlier migrations remain unchanged. There is no history UPDATE,
DELETE or event-type conversion. New automatic recoveries use ALERT_RESOLVED with
a null human actor. Phase 4B legacy recovery COMMENT_ADDED entries remain visible
and suppress new duplicate recovery entries on replay. Existing append-only
UPDATE/DELETE/TRUNCATE triggers remain enabled.

Migration was validated both from an empty PostgreSQL database and by migrating
a populated disposable V10 database to V11. The latter test checks unchanged
legacy event type, text, source alert and timestamp, null new metadata, initial
version 0, cross-type recovery uniqueness and retained deletion rejection.

Only disposable PostgreSQL 18.4 Testcontainers were migrated during validation.
The development backend was not restarted and its database was not modified.
Normal deployment startup must apply V11 before running this code. DDL/index
creation requires normal deployment planning; database owners/superusers remain
outside the normal DML immutability boundary.

## Validation results

Focused lifecycle/API/correlation/domain run: **166 passed**, zero failures,
errors or skips. Migration-upgrade, REST integration and real signed-JWT tenant
run: **92 passed**, zero failures, errors or skips. Strict request-coercion tests
were then added to the complete regression run.

Final full backend regression: **735 tests passed, 0 failures, 0 errors,
0 skipped**. Maven returned exit code 0 / BUILD SUCCESS; independent Surefire XML
totals agree. This is the 630-test baseline plus 105 additional/expanded cases,
including all strict request-coercion cases. Total Maven time was 4:44.

The full run logged slow connection-pool/context teardown and the delayed Surefire
fork-shutdown warning seen in an earlier phase (the fork did not exit within 30
seconds after System.exit(0)). All tests completed successfully, but no root cause
for that shutdown warning was established. No unrelated pool or infrastructure
settings were changed to suppress it. Expected constraint failures in negative
fixtures are passing rollback/rejection assertions.

Offline backend JAR packaging and Spring Boot repackaging passed. Tests were
skipped only for that separate package command after the full successful run.
Artifact: `backend/target/backend-0.0.1-SNAPSHOT.jar`. Packaging did not start
the backend or migrate development data. Archive inspection confirmed the new
service/controller classes and V11 migration. Final tracked/new-file whitespace
checks and local documentation link checks passed.

Tests cover all 36 status pairs (allowed, invalid and repeated), all ten allowed
API transitions, timestamp/reopening semantics, human actor attribution/spoofing,
all actual organisation roles, non-members, revocation, foreign resources,
filtering/paging/sorting, empty reads, invalid requests, concurrent lock waits,
one-success/one-409 behavior, reopening conflicts, rollback on timeline failure,
legacy recovery replay and the real Phase 4B monitoring-to-incident path.

The extended real-JWT suite uses actual RSA signatures and a disposable local
JWKS endpoint to check missing/malformed, wrong-signature, wrong-issuer,
wrong-audience and expired credentials on every new route. It also checks local
VIEWER restrictions despite signed OWNER claims. MockMvc incident tests replace
only the JWT decoder and Clock, with real services/repositories and fresh tenant
fixtures; no immutable event cleanup bypass is used.

The Maven wrapper was tried and still fails before Maven starts with its existing
PowerShell null-array error. Cached Maven 3.9.16 is used offline instead; no wrapper
or dependency change is made. From `backend`:

```powershell
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=IncidentLifecycleTest,IncidentServiceTest,IncidentManagementIntegrationTest,IncidentCorrelation*,IncidentPersistenceIntegrationTest' test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=IncidentLifecycleMigrationTest,TenantIsolationIntegrationTest,IncidentManagementIntegrationTest' test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o -DskipTests package
```

Logs are ignored under `backend/target/phase4c-*.log`. No frontend code changed,
so frontend tests/lint/build and browser E2E were not rerun or claimed.

## File inventory

Created production files under `backend/src/main/java/com/opspilot/backend/`:

- `application/IncidentQuery.java`
- `application/IncidentService.java`
- `application/exception/IncidentConflictException.java`
- `application/exception/IncidentNotFoundException.java`
- `application/exception/InvalidIncidentQueryException.java`
- `domain/exception/InvalidIncidentTransitionException.java`
- `web/IncidentController.java`
- `web/dto/ChangeIncidentStatusRequest.java`
- `web/dto/IncidentResponse.java`
- `web/dto/IncidentPageResponse.java`
- `web/dto/IncidentEventResponse.java`
- `web/dto/IncidentEventPageResponse.java`

Other created files:

- `backend/src/main/resources/db/migration/V11__add_incident_lifecycle_audit.sql`
- `backend/src/test/java/com/opspilot/backend/application/IncidentManagementIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/application/IncidentServiceTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentLifecycleTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentLifecycleMigrationTest.java`
- `docs/incident-lifecycle-api-phase-4c.md` (this report)

Modified production files under `backend/src/main/java/com/opspilot/backend/`:

- `application/IncidentCorrelationService.java`: dedicated recovery type, legacy deduplication and event ordering.
- `domain/Incident.java`: version and validated transitions.
- `domain/IncidentStatus.java`: explicit transition matrix.
- `domain/IncidentEvent.java`: typed human status audit metadata and dedicated recovery support.
- `domain/IncidentEventType.java`: ALERT_RESOLVED.
- `domain/IncidentRepository.java`: scoped filtering, flush and lock-scope projection.
- `domain/IncidentAlertRepository.java`: scoped ACTIVE association count.
- `domain/IncidentEventRepository.java`: bounded timeline pages and latest-event lookup.
- `web/exception/GlobalExceptionHandler.java`: incident and concurrency ApiError responses.

Other modified files:

- `backend/src/test/java/com/opspilot/backend/application/IncidentCorrelationIntegrationTest.java`: current migration and recovery-type expectations.
- `backend/src/test/java/com/opspilot/backend/application/IncidentCorrelationServiceTest.java`: dedicated recovery-type expectation.
- `backend/src/test/java/com/opspilot/backend/domain/IncidentPersistenceIntegrationTest.java`: current complete schema version.
- `backend/src/test/java/com/opspilot/backend/web/TenantIsolationIntegrationTest.java`: actual signed-JWT incident coverage.
- `docs/automatic-incident-correlation-phase-4b.md`: link to the subsequent lifecycle/recovery contract.

## Remaining limitations

There is no assignment API, human comment creation, frontend, notification or
force-resolution operation. Legacy status events can lack typed status metadata.
Offset pagination has normal concurrent-insert/page-shift limitations. Status
versioning does not promise a frozen snapshot of independently appended timeline
pages. A disabled service with an ACTIVE alert remains blocked from incident
resolution until its alert lifecycle recovers, as explicitly defined above.

Direct administrative SQL that bypasses application locks/version updates is not
a supported lifecycle interface. Future incident writers must retain the same
lock order, scoped authorization, version checks and atomic audit writes.
No commit, push or merge is performed by this work.

Phase 4C acceptance criteria are satisfied for the backend lifecycle/API scope.
Deployment to the development database, frontend integration and the excluded
features remain separate work. The test-process shutdown warning remains a
documented operational follow-up.
