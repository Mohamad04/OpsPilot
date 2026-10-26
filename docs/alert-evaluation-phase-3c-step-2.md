# Phase 3C Step 2 - Persisted alert evaluation

This increment evaluates HTTP availability alerts after accepted health checks.
It adds no public alert endpoints, frontend, notifications, incidents or external
messaging infrastructure. Phase 3C.1 rule APIs and their RBAC remain unchanged.

## Evaluation and state transitions

`HealthCheckRunner` still performs its outbound HTTP probe outside a transaction.
`HealthCheckStore.record()` locks the organisation-scoped monitored service,
rechecks its existence, HTTP type, enabled flag and target URL/path, and discards
ineligible or changed targets. It persists an accepted `HealthCheckResult` and
calls `AlertEvaluationEngine.evaluate(organisationId, serviceId, resultId)` inside
the same transaction. The engine loads the evidence through a scoped repository
query; it cannot evaluate an unpersisted ID or another service's result.

The effective rule is the saved rule, or the existing unsaved defaults:
enabled, three failures, two recoveries, HIGH severity. Evaluation never inserts
a default `AlertRule`. Service monitoring and the alert rule must both be enabled,
and the service must still be HTTP.

| Fresh eligible observation | Counters | Alert action |
| --- | --- | --- |
| DOWN | Increment failures; reset recoveries | Open at the failure threshold, or update the existing ACTIVE alert |
| UP | Reset failures; increment recoveries | Resolve an ACTIVE alert at the recovery threshold |
| UNKNOWN | Reset both streaks | Neither open nor resolve; preserve all alert history |

An UP interrupts a pending failure sequence. A DOWN interrupts a recovery
sequence. UNKNOWN interrupts both, including an incomplete recovery while an
alert is ACTIVE. An internal monitoring failure therefore cannot masquerade as
evidence that the target service is DOWN or recovered. Probe classification is
unchanged.

`Alert` exposes opening, recording a further failure and resolving operations.
A RESOLVED alert cannot be changed or reopened through these methods. A later
outage creates a new UUID and keeps the previous resolved row. Occurrence count
includes the initial failures that reached the opening threshold and each later
eligible DOWN while that alert is ACTIVE; it is not an uptime or health metric.
Counters saturate at their Java numeric limits instead of overflowing.

`firstSeenAt` is the first failure in the opening streak; `lastSeenAt` is the most
recent matching DOWN; `resolvedAt` is the qualifying recovery observation. These
use observation start times, not evaluation wall-clock times. The injected UTC
`Clock` supplies state-update and configuration-reset times. Stored instants are
normalised to PostgreSQL microsecond precision before evaluating a new result.

## Evidence and persisted state

Each alert retains its organisation, service, HTTP_AVAILABILITY category, opening
severity and threshold snapshots. It references the first failure, triggering
health result, latest matching failure and, once resolved, the recovery result.
The triggering HTTP code, response duration and existing sanitised failure
detail are also copied. The result rows provide the remaining observation data.
An alert's opening severity is historical evidence and does not change when a
rule is edited. The recovery threshold actually used at resolution is recorded
separately from the opening recovery-threshold snapshot.

One `AlertEvaluationState` per service stores both counters, the opening failure
of the current streak, the last processed result ID/start time, a configuration
fingerprint, the configuration cutoff and its update timestamp. Counters survive
new transactions, entity managers and evaluator instances; no in-memory counter
or historical scan is required. Existing health history is not backfilled when
V8 is installed: evaluation starts with subsequent accepted observations.

## Transactions, concurrency and idempotency

The service-row pessimistic write lock is acquired before saving a health result
and before any direct evaluation or application configuration update. All paths
use the same organisation-scoped parent row as the lock, including first-time
state creation. This serialises evaluation for one service across application
instances sharing the database; different services can progress independently.
Evaluation joins the caller's transaction using Spring's normal REQUIRED
propagation. No network I/O occurs under the lock.

Health evidence, cursor/counters and alert transitions commit or roll back
together. A database failure during evaluation rolls back the accepted health
insert too. The scheduler's existing error handling remains in place; there is no
durable retry queue for the lost observation. The next scheduled probe is a new
observation, rather than an automatic replay of an uncommitted check.

The cursor ignores the same result ID and any observation whose microsecond
start time is older than or equal to the cursor. Replaying a result cannot
increment occurrences or recovery counts. Distinct results with equal start
times are conservatively ignored after the first, rather than ordered by random
UUID. An older result may still be stored as health history, but cannot regress
counters, resolve an alert or reopen one.

This is a monotonic processed-stream policy, not a chronological replay engine.
If a newer result acquires the lock before an older result, the older result is
skipped even if it would have interrupted a streak in full chronological order.
It deliberately does not revise already committed alert history. Production
instances require synchronised clocks; strict chronological multi-instance
processing would require coordinated probe sequencing or an ordered queue.
The existing scheduler still provides only process-local overlapping-probe
prevention. Database locking does not claim distributed scheduling guarantees.

## Configuration changes

Actual rule changes (enabled, thresholds or severity) and service enable/disable
operations reset both counters transactionally under the parent lock. Even a
disable followed by an enable with no observations between them starts fresh.
An identical rule PUT, including the first explicit persistence of unchanged
effective defaults, preserves the streak.

Reset keeps the cursor and all ACTIVE/RESOLVED alerts. Its cutoff is at least
the current injected time, the last processed start time and the latest stored
observation start time. Results started at or before that cutoff are consumed
without counting. This prevents in-flight observations from before a change
being reused toward the new configuration. A fingerprint of service type,
enabled flag, target URL/path and effective rule provides a fallback for changes
detected on the next evaluation; that evaluation is conservatively skipped.

Changing thresholds cannot open an alert immediately from old counters.
Disabling either flag pauses evaluation without deleting or silently resolving
an existing ACTIVE alert. After re-enabling, fresh recoveries use the current
recovery threshold; fresh failures update the same ACTIVE alert. New outages
after resolution use the current severity and thresholds.

Configuration changes must use application services. Arbitrary direct SQL
changes bypass synchronous resets; the fingerprint only detects a changed final
value, so an unobserved disable/enable cycle with identical final values cannot
be inferred. Future application methods that edit service type or target must
participate in the same lock/reset protocol. Clock skew can delay fresh counting
at a cutoff or cause observations to be skipped; time synchronisation matters.

## Migration and tenant constraints

`V8__create_alerts_and_evaluation_state.sql` creates `alerts` and
`alert_evaluation_states`. V1-V7 are untouched, and existing rows are not
rewritten. It also adds a scoped unique key to `health_check_results` for evidence
foreign keys. Both new tables use UUID primary keys and TIMESTAMPTZ instants.

- Composite service/organisation foreign keys prevent cross-tenant association.
- Every evidence foreign key contains result ID, organisation ID and service ID,
  rejecting another tenant's or another same-tenant service's evidence.
- A unique service key permits one evaluation state per service.
- A partial unique index on service/category WHERE status='ACTIVE' independently
  prevents duplicate active alerts while permitting multiple resolved outages.
- CHECK constraints validate status/category/severity, threshold ranges, positive
  occurrence semantics, timestamp order, complete resolution evidence,
  nonnegative exclusive streaks, cursor pairs and first-failure consistency.
- Scoped service-history and unique state/active indexes support evaluation and
  future reads without scanning all historical results.

Building the additional health-result unique constraint scans the existing
table and requires PostgreSQL DDL locks; schedule deployment accordingly if its
history is large. No table, volume or existing business record is reset.

Evidence foreign keys prevent deletion of referenced individual health rows.
A future retention policy must preserve or archive those references. Parent
service deletion cascades its alerts, state and health history; this is verified
in PostgreSQL. No retention job or data purge is introduced here.

Migration V8 and Hibernate validation were exercised against disposable
PostgreSQL 18.4 Testcontainers databases. The local development database was not
manually changed or reset and the backend was not restarted by this task.
Application of V8 to that database is therefore not claimed. Normal application
startup will run Flyway using the existing configuration.

## Tests and validation

The new unit tests cover domain lifecycle/invariants, counter interruptions,
duplicate and older observations, cutoffs, saturation, effective defaults,
scope rejection and disabled evaluation. PostgreSQL integration tests cover:

- Default and configured thresholds, opening evidence, additional failures,
  recovery, subsequent outages and retained history.
- Real runner -> controlled local HTTP endpoint -> persistence -> evaluation.
- UNKNOWN and opposite-status interruptions; disabled rule/service and non-HTTP
  or deleted services; no writes for discarded changed targets.
- Persisted state used by a new evaluator instance and transaction.
- Duplicate, equal-time and out-of-order evidence; simultaneous evaluations
  using separate transactions and verified PostgreSQL lock contention.
- Configuration reset/no-op policy, in-flight cutoff and active-alert preservation.
- Database rejection of duplicate ACTIVE alerts, duplicate states, invalid
  counters/resolution and cross-tenant/cross-service evidence.
- Evidence retention, parent deletion cascades and full transaction rollback on
  an intentionally injected alert-table constraint failure.

Existing rule, authentication, RBAC, monitoring, SSRF and tenant tests remain in
the full regression suite. Existing tests were adapted for the added constructor
dependency, service write locks and child-table cleanup, without removing their
assertions. Fixture-only loopback DNS pinning is confined to the new local HTTP
test; production outbound restrictions are unchanged.

The Maven wrapper still fails before Maven starts with the existing PowerShell
null-array error. Validation uses the already cached Maven 3.9.16 distribution
with `-o`; no dependency installation or wrapper change was made. The focused
new-test run passed 36 tests with zero failures, errors or skips. The initial
focused/regression run exposed a null-unboxing error in the new UNKNOWN fixture;
the fixture was corrected and the focused run passed. The complete backend run
then passed **463 tests, zero failures, zero errors, zero skips** (427 existing
plus 36 new). Flyway applied all eight migrations and Hibernate validated the
schema in the disposable integration databases. Existing cached test contexts
emitted closed-container connection/pool warnings during teardown; they did not
fail tests. No tests were skipped to hide these messages.

Commands executed from `backend`:

```powershell
.\mvnw.cmd test
# Wrapper fails before Maven starts; use the existing cached distribution:
$cachedMaven = 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
& $cachedMaven -o '-Dtest=AlertTest,AlertEvaluationStateTest,AlertEvaluationEngineTest,AlertEvaluationIntegrationTest' test
& $cachedMaven -o test
& $cachedMaven -o -DskipTests package
```

The `-DskipTests` packaging command passed and produced the executable Spring
Boot JAR `backend/target/backend-0.0.1-SNAPSHOT.jar`. It follows the successful
full test run; tests are not claimed to execute again during that packaging step. Validation logs
remain in ignored backend log files. `git diff --check` is run from the repository
root. No frontend checks are needed for this backend-only increment.

## File inventory

New production files:

- `backend/src/main/java/com/opspilot/backend/application/AlertEvaluationEngine.java`
- `backend/src/main/java/com/opspilot/backend/domain/Alert.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertCategory.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertStatus.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertRepository.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertEvaluationState.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertEvaluationStateRepository.java`
- `backend/src/main/resources/db/migration/V8__create_alerts_and_evaluation_state.sql`

Modified production files:

- `backend/src/main/java/com/opspilot/backend/application/HealthCheckStore.java`
- `backend/src/main/java/com/opspilot/backend/application/AlertRuleService.java`
- `backend/src/main/java/com/opspilot/backend/application/MonitoredServicesService.java`
- `backend/src/main/java/com/opspilot/backend/domain/HealthCheckResultRepository.java`

New tests:

- `backend/src/test/java/com/opspilot/backend/domain/AlertTest.java`
- `backend/src/test/java/com/opspilot/backend/domain/AlertEvaluationStateTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertEvaluationEngineTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertEvaluationIntegrationTest.java`

Modified tests:

- `backend/src/test/java/com/opspilot/backend/application/AlertRuleServiceTest.java`
- `backend/src/test/java/com/opspilot/backend/application/MonitoredServicesServiceTest.java`
- `backend/src/test/java/com/opspilot/backend/application/ResponseDtoIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertRuleIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/application/HealthMonitoringIntegrationTest.java`
- `backend/src/test/java/com/opspilot/backend/web/MonitoredServiceAuthorizationTest.java`

Documentation: `docs/alert-evaluation-phase-3c-step-2.md` (this file).

No new dependencies, application properties, authentication paths or REST
contracts were added. No frontend or previous migration was modified.
