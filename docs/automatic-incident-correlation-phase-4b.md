# Phase 4B - Automatic incident creation and alert correlation

## Starting point and scope

Implementation continued on `feature/phase4-incident-management` from committed
Phase 4A `8830765`, with a clean working tree. Before changes, the existing
Phase 4A suite was executed successfully: 67 tests, no failures, errors or skips.
The [Phase 4A domain and database design](incident-domain-phase-4a.md) remains the
foundation; migrations V1-V9 are unchanged.

This increment adds synchronous correlation of persisted HTTP availability
alerts. It does not add incident REST APIs, manual status/assignment operations,
frontend work, notifications, queues, AI or new dependencies. There is no new
authentication mechanism or public endpoint.

## Correlation policy

The strategy is deliberately exact and explainable:

1. Lock the monitored service by both service ID and organisation ID.
2. Load the persisted alert by alert ID and organisation ID, then verify the
   service ID. Missing/foreign/mismatched input is rejected before incident access.
3. Prefer an existing association for that exact alert. Replays use its incident
   even if the incident has since become RESOLVED or CLOSED; they never reopen or
   reassign that alert to a new incident.
4. For an unassociated ACTIVE alert, look for an incident with the exact same
   organisation and monitored service and an ongoing status: OPEN, ACKNOWLEDGED,
   INVESTIGATING or MITIGATED.
5. If found, attach the alert to that incident. Otherwise open a new, unassigned
   OPEN incident using the alert severity and the injected Clock.
6. Create the incident-alert link and immutable system timeline entries in the
   same transaction. No service URL, human identity or diagnostic exception text
   is copied into incident titles or event details.

Automatic incidents have a fixed title `HTTP availability incident` and a bounded
description identifying the monitored-service UUID. The scalar `monitoredServiceId`
is their stable correlation anchor, independent of names and environment labels.
There is no time-window, URL similarity, service-name matching or cross-service
heuristic. Different organisations and different services get separate incidents.

**A later outage on the same service joins the older incident while that incident
remains ongoing, even if every previously attached alert recovered.** Alert
resolution does not imply incident resolution. Once an incident is RESOLVED or
CLOSED, a genuinely new alert opens a new incident; historical associations remain.
Thus Phase 4B alone intentionally keeps reusing its initial OPEN incident until
a later lifecycle implementation explicitly finishes it.

Existing Phase 4A incidents have a null service anchor. They are not guessed to
belong to a service or automatically selected as new correlation targets. An
already-existing explicit alert association is respected, including an unanchored
incident. If historical manual data contains several links for one alert, lookup
uses ascending link UUID to select deterministically. Conflicting source timeline
evidence is rejected rather than moved, rewritten or duplicated. New/manual
multi-incident grouping policies are outside this increment.

## Severity

Initial severity equals the originating alert severity. Each correlated ACTIVE
alert can raise an ongoing incident to the greater of its existing severity and
the incoming alert severity, with the explicit ordering:

`LOW < MEDIUM < HIGH < CRITICAL`.

Lower-severity alerts and recovery never downgrade incidents. Replays cannot
escalate a finished incident. `Incident.raiseSeverityTo` enforces the domain
policy; a pessimistic incident-row lock prevents lost severity changes, including
when pre-existing explicit associations share an incident. The attachment event
records incoming alert severity and resulting incident severity.

## Timeline and recovery evidence

The existing event types are reused without changing their enum:

| Event type | Meaning in automatic correlation |
| --- | --- |
| CREATED | The incident was opened for the originating ACTIVE alert |
| ALERT_LINKED | That ACTIVE alert was attached to the incident |
| COMMENT_ADDED | A system recovery note: the linked alert became RESOLVED |

Every automatic event has `actorUserId = null`, meaning system-generated, and
`sourceAlertId` referencing the actual persisted alert within its organisation.
No human actor is fabricated. Ordinary Phase 4A events retain null source IDs
and their existing actor semantics.

Recovery notes explicitly include the alert ID, its actual `resolvedAt`, and its
`resolutionHealthCheckResultId`. The source alert retains all existing persisted
health evidence. No incident acknowledgement, resolution timestamp, assignment or
status changes as a recovery side effect. Replaying the resolution appends no
second recovery note. A resolved historical alert without an association does
not open an incident solely because recovery was observed.

Event occurrence time is processing time from the injected Clock, normalised by
the existing domain to microseconds. If the clock is before incident creation,
the event time is clamped to incident creation so domain invariants hold. The
actual recovery observation time remains explicit in the recovery note/alert.
Equal event timestamps retain the existing stable UUID tie-break order; this
does not imply nanosecond ordering or a richer incident timeline API.

All event fields, including sourceAlertId, remain non-updatable. Existing JPA
immutability/removal protections and database UPDATE/DELETE/TRUNCATE rejection
remain active and are exercised by the Phase 4A regression tests.

## Integration and transaction boundaries

The existing flow is now:

```text
HTTP probe (outside transaction)
  -> HealthCheckStore.record (transaction and scoped service lock)
  -> accepted health result persistence
  -> AlertEvaluationEngine (same transaction/lock)
  -> saved ACTIVE alert or ACTIVE -> RESOLVED transition
  -> IncidentCorrelationService.process (joins transaction)
  -> incident, association and immutable timeline persistence
```

Correlation is called for saved ACTIVE alerts on fresh DOWN observations and for
the actual resolution transition. Repeated failures repair a missing association
for a pre-deployment ACTIVE alert on its next eligible DOWN, without scanning
all alert history. There is no migration-time history backfill. UNKNOWN and
sub-threshold observations do not create incidents; rejected/changed targets
never reach correlation. Duplicate or out-of-order health results retain the
existing evaluation cursor policy and cannot generate new correlation work.

`IncidentCorrelationService` is an internal transactional application service,
not a controller or an alternative authorization boundary. It re-reads persisted
alert state using scoped IDs and can also be retried independently in its own
transaction. The dependency direction is evaluator -> correlator -> repositories;
the correlator never calls the evaluator.

No network I/O, REQUIRES_NEW transaction or asynchronous event handler is added.
Health evidence, evaluation counters/cursor, alert transition, incident severity,
association and events commit together. Any insert/constraint failure propagates
and rolls them back together. Existing scheduler error handling remains intact.
There is no durable replay queue: after a failed recording transaction, the next
scheduled check is a new observation. Retrying the internal consumer for an
already persisted alert is idempotent.

## Concurrency and idempotency

Lock order is monitored-service row first, then incident row. It matches the
existing evaluator/configuration service locking convention, and applies even
before an incident exists. Different application instances sharing PostgreSQL
therefore serialise correlation for one service using database locks, not an
in-memory mutex. Future lifecycle operations must respect this lock order.
This does not change the scheduler's process-local probe-overlap guarantees or
claim distributed probe scheduling.

Safeguards complement each other:

- Existing alert identity association lookup precedes incident selection.
- Scoped incident lookups and service anchors prevent tenant/service mixing.
- The existing `(incident_id, alert_id)` unique constraint prevents repeated pairs.
- A partial unique index permits at most one service-anchored ongoing incident
  per organisation/service, while permitting resolved/closed history.
- Source event lookup prevents repeated application-level appends.
- A partial unique index on organisation/source alert/event type prevents
  duplicate automatic event evidence, including attempts across incidents. An
  automatic association and its ALERT_LINKED evidence are one transaction, so
  a conflicting source event aborts the duplicate correlation transaction.
- Event immutability preserves prior evidence rather than changing it on retry.

No duplicate-key exception is swallowed to report a false success. Cooperating
consumers use the common lock; conflicting non-cooperating writes fail closed
through database constraints.

## Migration V10

`V10__add_automatic_incident_correlation.sql` adds:

- Nullable `incidents.monitored_service_id` with a composite service/organisation
  foreign key. Existing Phase 4A incidents remain unanchored.
- `incidents_one_ongoing_service_idx`, unique for anchored incidents in the four
  ongoing statuses. This also supports the ongoing incident lookup.
- Nullable `incident_events.source_alert_id`, a composite alert/organisation
  foreign key, and a check requiring system actors and the three supported
  system event types for source-bearing events.
- `incident_events_source_alert_type_idx`, unique only for non-null source IDs.
  Existing manual events are not assigned artificial idempotency keys.
- An insert/update trigger validating same-service incident-alert links when an
  incident is anchored. Unanchored Phase 4A links retain their prior semantics.

No existing incident, association or event is rewritten or deleted. V9 and all
older migrations are unchanged. V1-V10 plus Hibernate schema validation run in
disposable PostgreSQL 18.4 Testcontainers. No local development backend restart,
manual SQL, Flyway repair or development-database migration was performed.
Normal deployment startup must apply V10 before using this backend version.

Foreign keys preserve the Phase 4A retention policy: incident-linked alerts,
referenced users and incident/event history cannot be silently deleted. Automatic
incidents also retain a reference to their service. Evolving retention and manual
grouping require an explicit future policy.

## Tests and validation

Executed before implementation: Phase 4A **67 passed**, zero failures/errors/skips.
The first focused Phase 4B run found one UNKNOWN test-fixture null-unboxing error;
the nullable HTTP-code fixture was corrected without changing probe classification.
The subsequent affected-suite run passed **168 tests**, zero failures/errors/skips.
After adding the final whole-outage/recovery-rollback cases, the standalone real
pipeline suite passed **21 PostgreSQL integration tests**, zero failures/errors/skips.

Coverage includes automatic opening/default severity/system actors, exact alert
identity replay, multiple failures, same-service recurrence across two complete
outages, ongoing/finished status policies, monotonic severity, distinct services
and tenants, foreign IDs, existing explicit associations, recovery evidence,
UNKNOWN interruption, discarded targets, actual concurrent database lock waits,
unique constraints and atomic rollback during creation, attachment/escalation
and resolution. Existing Phase 4A persistence/immutability tests remain enabled.

The new pipeline suite uses the real HealthCheckStore, evaluator, correlator and
repositories. It uses fresh disposable tenant fixtures per case and scoped
assertions rather than deleting immutable history. Only the JWT decoder and
Clock are fixture replacements; no incident component is mocked. Controlled
persisted health observations avoid reliance on external websites.

Two older focused Phase 3 integration suites (`AlertEvaluationIntegrationTest`
and `AlertManagementIntegrationTest`) mock only the newly added incident consumer.
Their existing alert evaluation/query assertions and real PostgreSQL work remain.
This preserves their original delete-between-tests fixture isolation and the
unlinked-service deletion case, without disabling audit triggers or destroying
incident history. Full real downstream integration is tested in the new suite,
including alert recovery and rollback. No existing test is removed or skipped.

The Phase 4A Flyway assertion advances from version 9 to 10 because the same
fixture now validates the complete current schema; other assertions remain.

Final complete backend regression: **630 tests passed, 0 failures, 0 errors,
0 skipped** (591 baseline cases plus 39 new cases: 21 PostgreSQL pipeline,
6 correlation-service unit and 12 domain cases). Maven returned BUILD SUCCESS;
the Surefire XML totals independently agree. The full run completed in 2:23.
The previous Phase 4A run's clock-leap/fork-shutdown warning did not recur in
this complete run. Expected database-rejection/rollback fixtures log their
intentional constraint errors; those are passing negative assertions.

Offline JAR packaging and Spring Boot repackaging also passed. Tests were skipped
only for this separate package command after the full successful test run.
Artifact: `backend/target/backend-0.0.1-SNAPSHOT.jar`. It contains the correlation
service and V10 migration. Packaging does not start the application or migrate
development data. Final repository checks include tracked/new-file whitespace
and local documentation link verification.

The existing Maven wrapper was tried first and still fails before Maven starts
with the PowerShell null-array error. Cached Maven 3.9.16 is used offline; neither
the wrapper nor dependencies are changed. Commands from `backend`:

```powershell
.\mvnw.cmd -o '-Dtest=IncidentTest,IncidentAlertTest,IncidentEventTest,IncidentPersistenceIntegrationTest' test
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=Incident*,AlertEvaluationEngineTest,AlertEvaluationIntegrationTest,AlertManagementIntegrationTest' test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o '-Dtest=IncidentCorrelationIntegrationTest' test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o -DskipTests package
```

Logs remain ignored under `backend/target/phase4b-*.log`. No frontend validation
was rerun because this increment changes no frontend files.

## File inventory

Created:

- `backend/src/main/java/com/opspilot/backend/application/IncidentCorrelationService.java`
- `backend/src/main/resources/db/migration/V10__add_automatic_incident_correlation.sql`
- `backend/src/test/java/com/opspilot/backend/application/IncidentCorrelationServiceTest.java`
- `backend/src/test/java/com/opspilot/backend/application/IncidentCorrelationIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentCorrelationDomainTest.java`
- `docs/automatic-incident-correlation-phase-4b.md` (this file)

Modified:

- `backend/src/main/java/com/opspilot/backend/application/AlertEvaluationEngine.java`
- `backend/src/main/java/com/opspilot/backend/domain/Incident.java`
- `backend/src/main/java/com/opspilot/backend/domain/IncidentStatus.java`
- `backend/src/main/java/com/opspilot/backend/domain/IncidentAlert.java`
- `backend/src/main/java/com/opspilot/backend/domain/IncidentRepository.java`
- `backend/src/main/java/com/opspilot/backend/domain/IncidentAlertRepository.java`
- `backend/src/main/java/com/opspilot/backend/domain/IncidentEvent.java`
- `backend/src/main/java/com/opspilot/backend/domain/IncidentEventRepository.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertEvaluationEngineTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertEvaluationIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertManagementIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/IncidentPersistenceIntegrationTest.java`

## Remaining boundaries

There is no incident management API, assignment flow, manual state transition,
UI, notification delivery or automatic closure. No historical scan, retry queue
or maximum incident duration is added. An unresolved incident can intentionally
accumulate later outages indefinitely. A legacy resolved alert without an incident
is left untouched. Existing explicit multi-incident/manual associations have the
conservative deterministic/conflict policy above, not a new grouping redesign.

V10 is not applied to the existing development database by these tests. Database
DDL/index creation needs normal deployment planning. Database owners/superusers
can bypass triggers with DDL; runtime roles must not have such privileges.
There is no claim of browser-level E2E or live deployment verification in this
backend-only increment. No commit, push or merge is performed.

Phase 4B acceptance criteria are met for synchronous automatic creation,
deterministic correlation, tenant-safe/idempotent persistence, system audit
events, monotonic severity and recovery preservation. The excluded manual/API/UI
capabilities remain subsequent phases.
