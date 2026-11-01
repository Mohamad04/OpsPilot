# Phase 4A - Incident domain and database foundation

## Scope and starting point

Implemented on `feature/phase4-incident-management`, created from the clean
approved Phase 3 HEAD `70f6ed6`. The starting local branch was
`feat/phase-incident-management`; the new branch preserves that branch and its
history. There were no uncommitted files at the start of this increment.

This phase adds domain models, scoped persistence repositories and Flyway V9.
There are no incident controllers, application services, alert-to-incident
automation, timeline APIs, notifications, frontend changes or new dependencies.
Full lifecycle transitions remain Phase 4C work. Existing authentication,
organisation memberships, monitoring and alert evaluation are unchanged.

Architecture follows the existing [alert domain](alert-evaluation-phase-3c-step-2.md)
and [tenant-isolation conventions](tenant-isolation-verification.md): generated
UUID primary keys, scalar scope/reference IDs, protected JPA constructors,
encapsulated factories and database-enforced tenant associations. Scalar IDs
avoid introducing eager collections or implicit relationship-loading queries.

## Domain models

### Incident

`Incident.open(organisationId, title, description, severity, createdAt, assignee)`
creates an OPEN incident. It requires an organisation ID, nonblank title of at
most 200 characters, nonblank description of at most 4000 characters, an existing
`AlertSeverity` value and an explicit timestamp. Text is stripped at its edges;
NUL characters are rejected. Timestamps are normalised to PostgreSQL microsecond
precision. There is no scattered system-clock access: future application services
can supply an injected Clock's instant.

The optional assignee is supplied as an `OrganisationMembership` and stored as
`assignedUserId`. The factory checks its organisation and persisted user ID;
the database independently verifies current membership. This association grants
no role or permission. Unassigned incidents store null. `createdAt` is the
creation/start instant; acknowledgement and resolution timestamps begin null.

`IncidentStatus` defines OPEN, ACKNOWLEDGED, INVESTIGATING, MITIGATED, RESOLVED and
CLOSED. There are no public setters or status-transition methods in Phase 4A.
The schema can represent all six statuses, with these consistency checks:

- OPEN: no acknowledgement or resolution timestamp.
- ACKNOWLEDGED / INVESTIGATING / MITIGATED: acknowledgement required, resolution absent.
- RESOLVED / CLOSED: acknowledgement and resolution required.
- Acknowledgement cannot precede creation; resolution cannot precede acknowledgement.

These are stored-state invariants, not a transition graph. Legal transitions,
acknowledgement policy, reassignment, optimistic concurrency and audit emission
belong to the subsequent application/lifecycle increments.

### IncidentAlert

`IncidentAlert.link(incident, alert)` requires persisted incident and alert IDs
and the same organisation. The explicit link entity has its own UUID and stores
organisation, incident and alert IDs. It does not change the linked alert's
severity, status, evidence or counters.

An incident can reference multiple alerts. Each `(incident_id, alert_id)` pair
is unique, including under concurrent database inserts. The foundation does not
impose a global one-incident-per-alert policy: the same alert may be referenced
by different incidents. Automatic grouping policy is outside this phase.

### IncidentEvent

`IncidentEvent.record(incident, type, actor, occurredAt, details)` creates an
immutable entry for a persisted incident. The optional actor is an organisation
membership input, stored as a user ID; null represents a system-originated entry.
The event time cannot precede incident creation.

`IncidentEventType` defines CREATED, STATUS_CHANGED, ASSIGNMENT_CHANGED,
ALERT_LINKED and COMMENT_ADDED. This is vocabulary for later handlers; no handlers
or automatic events are implemented now. The factory does not infer a lifecycle
transition from an event type.

Details are clearly defined as nonblank plain-text audit context, at most 4000
characters, with stripped edges and no NUL characters. They are not an opaque
JSON document, rendered HTML, executable instructions or a root-cause model.
Future API writers must avoid secrets and render this text safely.

Append-only enforcement has multiple layers:

- No public mutators; all mapped columns are non-updatable.
- Hibernate `@Immutable` prevents managed or merged entity changes from being
  persisted; attempted in-memory changes do not become SQL updates.
- A JPA `@PreRemove` callback rejects entity removal.
- The repository declares no delete or update operation.
- PostgreSQL row triggers reject UPDATE and DELETE with SQLSTATE 55000; a
  statement trigger rejects TRUNCATE, including a parent `TRUNCATE ... CASCADE`.
  These triggers are ENABLE ALWAYS, including the insert-validation trigger.

## Flyway V9 and relationships

`V9__create_incident_management_tables.sql` was available and is the only new
migration. V1-V8 are unchanged. It creates `incidents`, `incident_alerts` and
`incident_events`; existing business rows are neither backfilled nor rewritten.

- Each table has a UUID primary key. All required fields are NOT NULL.
- Incidents reference `organisations(id)` and have a unique `(id, organisation_id)`
  key for tenant-scoped foreign keys.
- V9 adds a unique `(id, organisation_id)` key to existing `alerts`.
- Links reference both incident and alert through composite ID/organisation
  foreign keys. Either foreign tenant association is rejected by PostgreSQL.
- Events reference incidents through the same composite tenant key.
- Assignees reference the existing `(organisation_id, user_id)` membership key.
  Deleting a membership sets only `assigned_user_id` to null, preserving the
  incident and its organisation. This uses PostgreSQL's column-specific
  `ON DELETE SET NULL` support, verified against the project's PostgreSQL 18.4.
- Event actor IDs reference `users(id)`. An insert trigger checks membership with
  a key-share lock, preventing concurrent membership removal during that append.
  Subsequent membership revocation does not erase or prevent reading historical
  authorship, and later appends by that former member are rejected.
- Severity/status/event-type checks match Java enums; text and timestamp checks
  also reject invalid direct SQL inserts/updates.
- Organisation/creation and organisation/status indexes support incident lists;
  scoped link indexes support incident and alert lookup. Timeline indexing is
  `(organisation_id, incident_id, occurred_at, id)` for stable chronological
  pagination. Optional assignee/actor foreign-key lookups are indexed.

No event or link cascades are configured. Deleting an incident with history, an
alert with an incident link, or a user with authored events is restricted by
foreign keys. This preserves evidence rather than silently deleting it. Because
the existing service deletion cascades to alerts, a service with incident-linked
alerts is likewise protected from deletion. Retention, archival and anonymisation
require an explicit future policy. Nothing prevents existing alert evaluation
from updating or resolving a linked alert.

The added alert unique key scans existing alerts and requires DDL locks. Plan
normal Flyway deployment accordingly for large tables. No local backend restart
or development-database migration was performed for this work: V9 was verified
only in disposable PostgreSQL containers. Normal application startup will apply
it to an existing development deployment when explicitly started with this code.

## Repositories and security boundary

`IncidentRepository`, `IncidentAlertRepository` and `IncidentEventRepository`
extend Spring Data's narrow Repository marker and declare only save plus scoped
lookup/list operations. They intentionally do not inherit global findById,
findAll, delete or bulk mutation methods.

Incident lists use database Page pagination with caller-supplied Pageable. Link
and event lists use Slice pagination; events order by occurredAt ASC, id ASC,
links by id ASC. Every declared read method includes organisationId; subordinate
lists include incidentId too. No JPA entities or APIs are exposed to the frontend.

These repositories are not authentication boundaries. Future application services
must reuse validated JWT/local-user resolution, current membership/RBAC checks,
bounded pagination and scoped loading before every operation, as existing
[alert query services](alert-management-api-phase-3c-step-3.md) do. No incident
HTTP operation exists in Phase 4A, and no permission is inferred from assignment
or event authorship. PostgreSQL composite keys enforce association consistency;
they are not a row-level-security replacement for application authorization.

Database immutability assumes normal application SQL access without DDL authority.
A table owner/superuser capable of dropping triggers or tables is outside that
boundary. Deployment credentials should separate migration DDL privileges from
runtime DML privileges; this increment does not change credentials or grants.

## Validation

Focused tests have passed: **67 tests, 0 failures, 0 errors, 0 skipped**, including
**38 PostgreSQL integration cases**. They cover factory defaults and invalid
inputs, persisted retrieval, bounded scoped lists, multiple linked alerts, unique
pairs, both directions of cross-tenant association, actor/assignee membership,
membership removal, managed/detached event immutability, JPA removal rejection,
direct SQL UPDATE/DELETE/TRUNCATE rejection, parent deletion restrictions, all
stored status representations, and full Flyway V1-V9 / Hibernate validation.

The JPA tests use PostgreSQL 18.4 Testcontainers and rollback-isolated fixtures.
Rejected SQL statements use savepoints to verify original rows remain intact.
No local development database or external website is used by these tests.

The **complete backend suite passed: 591 tests, 0 failures, 0 errors, 0 skipped**.
This includes all existing monitoring, alert-rule, evaluation, alert API and
real-JWT tenant-isolation regression tests. Surefire XML report totals independently
agree with Maven's summary. No existing test was removed, weakened or skipped.

Backend JAR packaging and Spring Boot repackaging also passed offline. Tests were
skipped only for that separate packaging command, after the complete suite above.
Artifact: `backend/target/backend-0.0.1-SNAPSHOT.jar`. Packaging did not start the
backend or apply V9 to the development database.

The full test run logged Hikari thread-starvation/clock-leap warnings and a
Surefire delayed fork-shutdown warning (`System.exit(0)` exceeded 30 seconds).
Maven nevertheless returned exit code 0 / BUILD SUCCESS, with all tests passing.
No root cause for those runtime/shutdown warnings was established, and no
unrelated scheduling or connection-pool changes were made to suppress them.
The recorded Maven wall time was 53:48 minutes, including the reported clock leap;
it should not be treated as a stable performance benchmark.

The Maven wrapper was tried first and failed before Maven started with the existing
PowerShell null-array error. It was not modified. Cached Maven 3.9.16 was then
used offline; the first sandboxed invocation could not read a cached dependency,
so the test invocation was rerun with access to the existing cache and Docker.
No dependencies or frameworks were installed or changed.

Commands from `backend`:

```powershell
.\mvnw.cmd -o '-Dtest=IncidentTest,IncidentAlertTest,IncidentEventTest,IncidentPersistenceIntegrationTest' test
# Wrapper fallback:
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=IncidentTest,IncidentAlertTest,IncidentEventTest,IncidentPersistenceIntegrationTest' test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o -DskipTests package
```

Logs remain ignored under `backend/target/phase4a-focused.log` and
`backend/target/phase4a-full.log` and `backend/target/phase4a-package.log`.
Frontend tests/lint are not rerun because this
phase changes no frontend code. There is no configured separate Java lint task.

## File inventory

Created production files under `backend/src/main/java/com/opspilot/backend/domain/`:

- `Incident.java`, `IncidentStatus.java`, `IncidentRepository.java`
- `IncidentAlert.java`, `IncidentAlertRepository.java`
- `IncidentEvent.java`, `IncidentEventType.java`, `IncidentEventRepository.java`

Other created files:

- `backend/src/main/resources/db/migration/V9__create_incident_management_tables.sql`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentAlertTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentEventTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentPersistenceIntegrationTest.java`
- `docs/incident-domain-phase-4a.md` (this report)

Existing production, test and migration files are unchanged. No commit, push,
merge or development database reset is part of this increment.

## Acceptance and remaining boundaries

Phase 4A acceptance criteria are satisfied for the domain/database foundation:
validated incident creation, tenant-safe unique links, append-only events,
scoped persistence, migration/schema validation and the complete regression suite.
`git diff --check` passed. All 14 new files also passed explicit whitespace
checks, and local documentation links were verified. Existing tracked files
and migrations are unchanged.

V9 has not been deployed to the existing local development database. There are
no incident REST APIs or incident application authorization entry points to test
yet. Future services must enforce membership/RBAC and transactional event writes;
schema support does not imply that any status transition or incident automation
has been implemented. Evidence retention restricts deletion as described above;
there is no purge/anonymisation facility. The runtime/shutdown warnings above
remain an operational follow-up rather than a claimed fix in this increment.
