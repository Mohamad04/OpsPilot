# Phase 4D - Incident assignment and human comments

## Scope and repository state

Implemented on `feature/phase4-incident-management`. At the start of this task,
HEAD was `eb6aecf` and the Phase 4C source/tests/report were still uncommitted.
Those changes were preserved and extended directly, without a reset, stash,
commit, push or merge. The final Git diff therefore also includes pre-existing
Phase 4C work; it is not exclusively a Phase 4D patch.

This increment adds assignment and immutable human comments to the existing
incident API. It does not introduce incident frontend pages, notifications,
streaming, attachments, comment editing/deletion, AI or new dependencies.
The [Phase 4C lifecycle contract](incident-lifecycle-api-phase-4c.md) and
[Phase 4B correlation](automatic-incident-correlation-phase-4b.md) remain in force.

## Assignment policy

An eligible assignee is an ACTIVE local user with a current membership in the
incident's organisation and role OWNER, ADMIN or ENGINEER. VIEWER and inactive,
unknown, foreign or former members are ineligible. Authentication alone never
grants a membership or assignment permission.

| Actor role | Assignment permission | Human comments |
| --- | --- | --- |
| OWNER / ADMIN | Assign/reassign any eligible member, or unassign | Allowed |
| ENGINEER | Claim an unassigned incident for themselves; retain/release their own assignment | Allowed |
| VIEWER | Read-only | Read-only |
| Non-member | No access | No access |

ENGINEER cannot steal another member's assignment, release it or assign someone
else. An administrator must perform those changes. This narrows assignment
authority while retaining Phase 4C's operational write roles for investigation.

Assignment is allowed only in OPEN, ACKNOWLEDGED, INVESTIGATING and MITIGATED.
RESOLVED and CLOSED retain their assignment unless membership removal requires
automatic unassignment. To change an assignment through the API, reopen the
incident using the existing guarded lifecycle first.

## REST contracts

All routes begin with:

`/api/organisations/{organisationId}/incidents/{incidentId}`

They require the existing validated bearer JWT, ACTIVE local user resolution and
local organisation membership. Tenant and actor identity come from authorised
application lookups and the authenticated subject, never from body-supplied roles
or author fields. Unknown/foreign incident IDs use the same scoped 404 behavior.

### PATCH /assignee

```json
{
  "assigneeUserId": "00000000-0000-0000-0000-000000000001",
  "expectedVersion": 3
}
```

The UUID and version above are illustrative, not fixture evidence. Read the
incident to obtain its actual current `version`. `assigneeUserId` is the local
OpsPilot user UUID, not a Keycloak subject or membership ID. Both JSON properties are
required: `assigneeUserId` must be a canonical UUID string or **explicit null**
to unassign. Omitting it is not interpreted as unassignment. `expectedVersion`
must be a nonnegative int64 JSON integer; strings, floats and overflow are 400.

Success returns **200 IncidentResponse**, including `assignedUserId` and the
resulting version. An effective change increments the version and appends one
assignment event. With a matching version, assigning the already-current user
or unassigning an already-unassigned incident is a no-op: no event or increment.
A stale version remains 409 even if the requested user is already assigned;
the caller must reload rather than blindly overwrite newer work.

An ineligible assignee returns a generic 404 with no distinction between unknown,
foreign, inactive, VIEWER or former users. ENGINEER requests beyond their allowed
self-assignment policy return 403 before probing the target user's eligibility.

### POST /comments

```json
{
  "content": "Investigating increased response times."
}
```

Returns **201 IncidentEventResponse**. The response is the same event shape used
by the existing paginated timeline. `details` contains the comment text;
`eventType` is COMMENT_ADDED, `actorUserId` is the authenticated local author and
`sourceAlertId` is null. The client cannot choose the author or event type.

Content must be a JSON string, nonblank and at most **4000 Java UTF-16 code units
before trimming**. NUL is rejected. Leading/trailing whitespace is stripped;
internal whitespace and markup remain plain text. HTML/scripts are not executed
or interpreted. Consumers must render `details` as text, never trusted HTML.
No edit/delete endpoint exists, and database immutability applies to comments.

Comments are accepted in **all six incident statuses**, including RESOLVED and
CLOSED for follow-up evidence. They do not change the incident status, assignment
or optimistic version. They can therefore coexist with subsequent lifecycle
requests without making a version stale merely because a comment was appended.

### Optional retry deduplication

Send a fresh canonical UUID in `Idempotency-Key` for each logical comment:

```http
Idempotency-Key: 00000000-0000-0000-0000-000000000002
```

Reuse that key only when retrying the same comment. Scope is
`(organisationId, incidentId, actorUserId, clientRequestId)`:

- Same key and same trimmed content returns the original event, still HTTP 201,
  without another append. Timestamp and author remain unchanged.
- Same scoped key with different content returns 409.
- Different keys, authors or incidents are independent. Without a key, each
  valid submission creates an event, including legitimate identical messages.
- Membership and write permission are checked again before a replay response.
- Malformed/empty/noncanonical keys return 400. The key is a bounded 16-byte UUID
  and is not exposed in event responses or logged.

Deduplication uses one indexed lookup, not an in-memory cache or a history scan.
Its receipt persists with the immutable event; no expiry/deletion is introduced.
Clients should bound their retries. There is no claim of deduplication without
a key or an exactly-once network delivery guarantee. CORS permits this header
only through the existing configured frontend origin policy.

### HTTP outcomes

| Status | Meaning |
| --- | --- |
| 200 | Assignment updated or matching-version no-op |
| 201 | Comment appended or an identical keyed retry replayed |
| 400 | Invalid body, UUID, version, content or idempotency header |
| 401 | Missing/invalid JWT |
| 403 | Read-only role, restricted ENGINEER operation or inactive caller |
| 404 | Unavailable scoped incident, membership or eligible assignee |
| 409 | Stale version, finished-incident assignment, conflicting retry content or recognised concurrent-write conflict |

Errors reuse the existing `ApiError(status, message, errors)` contract. Failure
does not return user email, identity-provider subject or database diagnostics.

## Timeline and historical compatibility

The repository already defines **ASSIGNMENT_CHANGED**, so this implementation
retains that persisted vocabulary instead of adding ASSIGNEE_CHANGED. Responses
add nullable `previousAssigneeUserId` and `newAssigneeUserId`. Assignment events
store UUID references rather than duplicated names/emails. Their actor is the
authenticated human, except automatic membership-removal events described below.
Legacy events retain null metadata; no historical row is rewritten.

Human COMMENT_ADDED has a nonnull human actor and null source alert. Historical
Phase 4B recovery COMMENT_ADDED notes have a null system actor and source alert
reference; current recovery events use ALERT_RESOLVED. A human comment cannot
suppress the existing per-alert recovery deduplication. The real monitoring /
alert / incident regression test exercises that distinction.

New application events share Phase 4C's monotonic microsecond timestamp policy:
an injected Clock is clamped to incident timestamps and later than the latest
event. Existing timeline ordering and pagination remain unchanged.

## Transactions, concurrency and membership removal

Each operation is a short application-service transaction, without external I/O.
It reuses the scoped projection and **service then incident** pessimistic lock
order used by lifecycle/evaluation/correlation. An incident without a service
anchor is locked directly. Once locked, assignment checks `expectedVersion`;
the incident change and event insert commit/roll back together. Two effective
updates with the same version cannot silently overwrite one another.

Comments use the same incident lock to serialize timestamp allocation and keyed
retry checks. Distinct concurrent requests append distinct events; duplicate
keys see the first committed event. A partial unique index independently
prevents duplicate receipts. No incident version increment is forced for comments.

V9's composite membership FK still performs `ON DELETE SET NULL (assigned_user_id)`.
Because that referential action bypasses JPA, V12 adds a trigger for a nulling
assignment update whose version was not already incremented. It increments
the version and appends one **system** ASSIGNMENT_CHANGED event with previous
user ID and null new user ID. This is atomic with membership removal, applies
also to finished incidents, and rolls back removal if the audit insert fails.
Application updates already increment the version and emit their human event,
so the trigger does not duplicate them.

The trigger uses database time clamped after prior events; it cannot use Java's
Clock. Historical comment authors and assignment metadata reference `users`,
not live memberships, so revocation preserves authorship. Membership removal
does not grant access, delete comments or remove history. Its version bump also
makes stale application transitions fail rather than overwrite the unassignment.

Concurrent membership administration may contend with incident writes. PostgreSQL
deadlock/lock conflicts use the existing 409 handling; reload/retry is required.
New assignment/comment writes also translate FK SQLSTATE 23503 into a safe 409
when a referenced incident or membership disappears after validation. The catch
covers query-triggered automatic flushes as well as explicit flushes. Other audit
constraints are not disguised as membership conflicts.
Eligibility is checked against current application state, not an eternal promise
that a member's account/role can never later change. Direct administrative SQL
that bypasses application version/locking rules is not a supported mutation API.

## Flyway V12

`V12__add_incident_assignment_and_comments.sql` adds:

- Nullable previous/new assignee UUIDs with user foreign keys, plus comment
  request UUIDs on `incident_events`.
- Checks limiting assignment metadata and comment request keys to appropriate
  event types, preserving legacy rows without new metadata.
- A partial unique index on the scoped human comment request identity.
- The atomic membership-unassignment version/audit trigger on `incidents`.

There are no new tables, no rewriting of V1–V11, no historical backfill and no
change to existing tenant-safe incident/alert FKs or append-only event triggers.
Migration and schema validation are exercised in disposable PostgreSQL 18.4
containers. The local development backend/database are not restarted or migrated
by this task; deployment uses the normal startup/Flyway workflow.

## Validation

Executed on 2026-10-28 with Java 21, cached Maven 3.9.16, existing dependencies
and disposable PostgreSQL 18.4 Testcontainers:

| Run | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| Initial collaboration/domain/service run | 62 | 1 | 0 | 0 |
| Corrected and expanded focused regression | 167 | 0 | 0 | 0 |
| Complete backend suite before final race regression | 796 | 0 | 0 | 0 |
| Added automatic-flush race reproduction | 11 | 1 | 0 | 0 |
| Focused regression after race correction | **170** | **0** | **0** | **0** |
| Complete rerun interrupted by container readiness timeout | 762 | 0 | 1 | 0 |
| Unchanged affected persistence class rerun | 38 | 0 | 0 | 0 |
| Final complete backend rerun | **799** | **0** | **0** | **0** |

The initial failure was a new test expecting DataIntegrityViolationException for
the existing append-only PostgreSQL trigger. SQLSTATE 55000 is actually translated
by JdbcTemplate as UncategorizedSQLException. The assertion now verifies that
exception and the exact SQLSTATE for UPDATE, DELETE and TRUNCATE; neither the
trigger nor its protection was weakened. All assignment/comment behavior tests
in that initial run passed.

Final review found that an assignment UPDATE can auto-flush during the timeline
query, before the initially narrower catch block. A focused unit regression first
failed with an unhandled DataIntegrityViolationException. The correction covers
all new write persistence steps and translates FK SQLSTATE 23503 to 409, including
author membership races during comment append. A real PostgreSQL fixture injects
membership removal between eligibility validation and FK checking and verifies
HTTP 409 plus rollback of assignment, version and audit writes. This is controlled
database fault injection, not a claim of live-user browser race testing.

Focused coverage now includes 45 collaboration HTTP/PostgreSQL tests, 9 domain
tests, the V11-to-V12 migration test, 46 lifecycle/correlation API regressions,
12 service unit tests, the earlier migration compatibility test and 56 real-JWT
tenant-isolation tests. Complete regression runs include all existing monitoring,
alerting, membership, provisioning, lifecycle and persistence tests.

The first full run logged a 10-minute clock-leap/thread-starvation warning and closed
test-connection warnings, then continued to BUILD SUCCESS. No environment or
application change was needed to resume it, and no root cause for those runtime
warnings is asserted. Elapsed time was 16:38, including that interruption.

The first full run after the race correction failed in Testcontainers before
`IncidentPersistenceIntegrationTest` could execute: PostgreSQL's log-readiness
wait timed out. Its captured container log did contain ready messages; the cause
of the missed readiness detection is not established. That unchanged class then
passed all 38 tests in a fresh disposable container. No wait criteria, assertions,
production configuration or dependencies were changed. The complete suite was
repeated again to obtain a result for the final code.

That final complete rerun passed in **4:26**. Surefire XML independently totals
799 tests across 47 report files, with no failures/errors/skips. Closed
test-connection warnings still appeared near context/container teardown;
BUILD SUCCESS and the complete test totals are verified, but a root cause or
fix for those environment/lifecycle warnings is not claimed.

The wrapper was attempted first from `backend` with `.\mvnw.cmd test`; it failed
before Maven started with the existing PowerShell null-array error. It was not
modified. The fallback commands below used an existing local cache and do not
install dependencies. Paths identify the verification environment, not portable
installation requirements:

```powershell
# From backend:
$mavenExe = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $mavenExe '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=IncidentCollaboration*,IncidentServiceTest,IncidentManagementIntegrationTest,IncidentLifecycleMigrationTest,TenantIsolationIntegrationTest' test
& $mavenExe '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=IncidentPersistenceIntegrationTest' test
& $mavenExe '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
& $mavenExe '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o -DskipTests package
```

Execution logs are ignored under `backend/target/phase4d-*.log`. No frontend code
changed; frontend tests, lint/build and live browser E2E were not rerun or claimed.
Final JAR packaging and Spring Boot repackaging passed after the final full test
run. Tests were skipped only for that separate package command. Artifact:
`backend/target/backend-0.0.1-SNAPSHOT.jar`. Packaging did not start the application
or apply development migrations. The new DTOs, exception and V12 migration were
verified in the archive. `git diff --check`, new-file whitespace checks and local
documentation link/JSON example checks passed.

## Files changed in this increment

Created:

- `backend/src/main/java/com/opspilot/backend/application/exception/IncidentAssigneeNotFoundException.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/ChangeIncidentAssigneeRequest.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/AddIncidentCommentRequest.java`
- `backend/src/main/resources/db/migration/V12__add_incident_assignment_and_comments.sql`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentCollaborationTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentCollaborationMigrationTest.java`
- `backend/src/test/java/com/opspilot/backend/application/IncidentCollaborationIntegrationTest.java`
- `docs/incident-assignment-comments-phase-4d.md` (this report).

Extended existing working-tree files:

- `Incident.java`, `IncidentEvent.java`, `IncidentEventRepository.java`: domain
  assignment/comment behavior, metadata and scoped retry lookup.
- `IncidentService.java`, `IncidentController.java`: authorised transactional use
  cases and thin REST endpoints; shared existing lock-order helper.
- `IncidentEventResponse.java`: additive assignment metadata.
- `GlobalExceptionHandler.java`: generic eligible-assignee 404.
- `WebConfig.java`: existing CORS origin/method policy plus Idempotency-Key header.
- `IncidentServiceTest.java`: constructor wiring and two early-flush/author-reference race regressions.
- `IncidentManagementIntegrationTest.java`: human-comment/automatic-recovery regression.
- `TenantIsolationIntegrationTest.java`: both new routes under real signed JWTs.
- `IncidentCorrelationIntegrationTest.java`, `IncidentPersistenceIntegrationTest.java`,
  `IncidentLifecycleMigrationTest.java`: expected current migration version 12.
- `README.md` and the Phase 4C API report: public capabilities, contracts and links.

## Known limitations

**Later increment:** [Phase 4D.5](organisation-membership-api-phase-4d5.md) adds a
production paginated member-directory/management API. The historical scope and
test evidence below describe Phase 4D; a frontend member picker is still pending.

There are no frontend incident/assignee controls, member-search endpoint, comment
edit/delete operations or comment rate limit in this increment. Event history and
request receipts have no automatic retention policy. Comment text is untrusted
and must remain text in future consumers. A newly read version is required after
any effective incident mutation or membership-removal unassignment. System-driven
membership unassignment has no fabricated human actor. User-row deletion remains
restricted by historical references, consistent with existing audit authorship.

Phase 4D acceptance criteria are satisfied for the requested backend scope.
Deployment of V12 and live Keycloak/browser verification of these new operations
remain separate steps. No commit, push or merge was performed.
