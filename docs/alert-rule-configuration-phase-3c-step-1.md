# Phase 3C Step 1 — Configurable alert rules

This increment configures rules only. It adds no evaluator, alert generation,
automatic resolution, incidents, messaging infrastructure or frontend changes.
Development stays on `feat/alerting`; no patch or archive is used.

## Model and defaults

`AlertRule` stores UUID identity, immutable organisation/service IDs, enabled,
consecutive failure/recovery thresholds, severity, and creation/update instants.
Scalar tenant/service IDs follow the existing `HealthCheckResult` mapping.
Configuration is changed through an atomic validated domain method; there are no
public mutable fields or setters that can move a rule between tenants/services.

| Setting | Effective default | Allowed values |
| --- | --- | --- |
| enabled | true | JSON boolean |
| consecutiveFailures | 3 | JSON integer, 1–20 inclusive |
| consecutiveRecoveries | 2 | JSON integer, 1–20 inclusive |
| severity | HIGH | LOW, MEDIUM, HIGH, CRITICAL (exact strings) |

Creation/update timestamps use the existing injected UTC Clock and PostgreSQL
microsecond precision. Creation time remains fixed. A real configuration change
updates `updatedAt` without allowing it to precede the previous timestamp; an
identical PUT preserves timestamps as well as identity and configuration.

Rules are exposed only for HTTP services. A disabled HTTP service may retain and
edit its rule independently. `allowsEvaluation(monitoredServiceEnabled)` expresses
the required gate: both flags must be true. It performs no evaluation. The later
evaluator must check current service eligibility and this gate before processing
checks; enabling a rule does not start generating alerts in this increment.
Disabling a rule does not disable scheduled health checks.

## Database

New migration: `V7__create_alert_rules.sql`. V1–V6 remain unchanged.

- UUID primary key and NOT NULL configuration/association/timestamp columns.
- Unique service ID: at most one persisted rule per globally identified service.
- Composite foreign key `(monitored_service_id, organisation_id)` references the
  existing V6 service key `(id, organisation_id)`, enforcing tenant consistency.
- Parent service deletion cascades to its rule. No existing services are changed
  or backfilled; no rows are created until PUT.
- CHECK constraints enforce threshold bounds, severity names and timestamp order.
- `TIMESTAMPTZ` timestamps and an organisation-ID index; the unique service
  constraint provides its lookup index. SQL defaults agree with domain defaults.

Migration/JPA compatibility and constraints are tested on disposable PostgreSQL
18.4 containers. V7 was not manually applied to the user's existing development
database and no backend restart was performed. Normal deployment startup will
apply V7 with Flyway. HTTP eligibility is an application business check; SQL
constraints independently enforce tenant identity, uniqueness and stored values.

## REST contract

Both operations require the existing validated Keycloak access token:

```text
GET /api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/alert-rule
PUT /api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/alert-rule
```

PUT replaces all four settings and requires all four fields:

```json
{
  "enabled": true,
  "consecutiveFailures": 3,
  "consecutiveRecoveries": 2,
  "severity": "HIGH"
}
```

Both GET and PUT return HTTP 200. Before the first PUT, GET is side-effect-free:

```json
{
  "id": null,
  "organisationId": "<organisation UUID>",
  "monitoredServiceId": "<service UUID>",
  "enabled": true,
  "consecutiveFailures": 3,
  "consecutiveRecoveries": 2,
  "severity": "HIGH",
  "createdAt": null,
  "updatedAt": null
}
```

After PUT, the same response shape includes a persisted UUID and timestamps.
Enabled is the rule's configuration flag, independently of service configuration;
the existing service API supplies service enabled state.

Errors reuse `{ "status": ..., "message": ..., "errors": { ... } }`:

- 400: invalid/missing fields, malformed JSON/UUIDs or a non-HTTP service.
- 401: missing/invalid JWT, using the existing signature/issuer/audience checks.
- 403: VIEWER attempts a write, or the local account is inactive.
- 404: absent membership or a missing/foreign service in an authorised scope.
  As with existing service endpoints, write permission is checked before service
  lookup, so a VIEWER receives 403 for writes regardless of whether the ID exists.

Jakarta Bean Validation supplies required-field/range errors. DTO-local JSON
deserializers reject fractional/string thresholds, numeric severity ordinals and
non-boolean enabled values instead of silently coercing them. Other endpoints'
Jackson configuration is untouched. Invalid input cannot partially update a rule.

## Authorization, isolation and concurrent PUT

Controllers extract only the validated JWT subject and delegate to the application
service. Existing `AuthenticatedUserService` checks the local active user; existing
membership services authorize each request. OWNER, ADMIN and ENGINEER can read and
write; VIEWER can read. Frontend-supplied role/subject/tenant fields are never used
as identity or permission inputs.

Service and rule lookups include organisation and service IDs. Membership is
checked before touching service/rule storage. Non-members receive the existing
404 without revealing whether another organisation exists. Foreign service IDs
cannot be moved into a different tenant path, even by a member of both tenants.

PUT obtains a pessimistic write lock on the existing, tenant-scoped **service** row
within the transaction before looking for a rule. This handles the absent-child
case that locking an alert-rule row alone cannot protect. Under PostgreSQL's
existing READ COMMITTED isolation, a waiting writer observes the prior writer's
committed rule and updates it rather than inserting a duplicate. The lock ends at
transaction completion and coordinates writers across application instances;
database uniqueness remains an independent safeguard.

There is no network I/O while holding this lock. GET takes no write lock and does
not save anything. Different submitted configurations use normal full-replacement,
last-serialized-writer semantics; there is no ETag/version-based conflict UI in
this increment. Existing service/monitoring APIs and scheduler logic are unchanged.

## Verification

The unchanged backend baseline passed 346 tests before implementation. The first
focused run executed 98 tests: 95 passed and 3 failed, with no errors/skips.
Those failures reproduced Jackson's default coercion of severity `2` and threshold
`1.5`. Strict parsing was added only to this DTO and the regression cases retained.

Tests cover domain defaults/validation/timestamps, side-effect-free GET, create/
update/idempotence, role matrix, HTTP eligibility, malformed/missing input, forged
identity fields, missing/invalid JWT, tenant-scoped reads/writes and revocation,
PostgreSQL defaults/uniqueness/composite FK/checks, and unchanged health persistence.
The concurrency fixture holds the parent lock, verifies multiple actual lock
waiters in PostgreSQL, releases them, and checks all four PUTs return one identical
persisted rule. The existing real HTTP/RSA JWT/JWKS tenant suite also includes the
new operations and distinct configurations in two organisations.

Commands (from `backend`):

```powershell
# Normal entry point; the previously observed wrapper error is unchanged.
.\mvnw.cmd test

# Already-cached Maven 3.9.16 fallback; no dependencies downloaded.
$mavenExecutable = (Get-ChildItem "$env:USERPROFILE/.m2/wrapper/dists/apache-maven-3.9.16" -Recurse -Filter mvn.cmd | Select-Object -First 1).FullName
& $mavenExecutable -o '-Dtest=AlertRuleTest,AlertRuleServiceTest,AlertRuleIntegrationTest,TenantIsolationIntegrationTest' test
& $mavenExecutable -o '-Dtest=AlertRuleIntegrationTest' test
& $mavenExecutable -o test
```

Final results:

| Verification | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- |
| Unchanged baseline | 346 | 0 | 0 | 0 |
| Initial focused run, before strict-JSON correction | 95 | 3 | 0 | 0 |
| Affected integration suite after correction | 47 | 0 | 0 | 0 |
| Complete final backend suite (27 test classes) | **427** | **0** | **0** | **0** |

The final suite includes 17 domain tests, 9 application-service unit tests,
47 alert-rule integration tests and the expanded 30-test real-JWT tenant suite.
All 346 baseline tests remain enabled; the increment adds 81 executed cases.
`git diff --check` passes, and the final review confirms no edits to V1–V6,
frontend, build dependencies, wrapper or monitoring implementation.

The requested `.\mvnw.cmd test` was retried and still fails before Maven launches
with **Cannot index into a null array**. The successful affected/full runs use the
already-cached Maven 3.9.16 fallback above in offline mode. No wrapper change or
dependency download was needed. Maven reports BUILD SUCCESS for the final suite.
Logs are ignored files in `backend`: `alert-rule-focused-tests.log`,
`alert-rule-regression-tests.log`, `alert-rule-full-tests.log` and
`alert-rule-wrapper-test.log`.

Tests use disposable databases/local JWKS servers rather than live Keycloak or
business records. Test output includes Mockito dynamic-agent warnings and Hikari
messages from cached contexts after disposable containers stop. They do not
represent failed test assertions; no unrelated test-infrastructure refactor was
made in this increment.

**Ready for commit: yes, for Phase 3C Step 1 configuration only.** V7 still needs
normal Flyway deployment to the intended environment before using these APIs
there. Full-replacement writes use last-serialized-writer semantics, with no
optimistic conflict detection. Actual alert evaluation and lifecycle remain
unimplemented; a later evaluator must apply the current service/rule gate.
No commit, merge or push was performed.

## Files

Created:

- `backend/src/main/java/com/opspilot/backend/domain/AlertRule.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertSeverity.java`
- `backend/src/main/java/com/opspilot/backend/domain/AlertRuleRepository.java`
- `backend/src/main/java/com/opspilot/backend/domain/exception/InvalidAlertRuleConfigurationException.java`
- `backend/src/main/java/com/opspilot/backend/application/AlertRuleService.java`
- `backend/src/main/java/com/opspilot/backend/web/AlertRuleController.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/ConfigureAlertRuleRequest.java`
- `backend/src/main/java/com/opspilot/backend/web/dto/AlertRuleResponse.java`
- `backend/src/main/resources/db/migration/V7__create_alert_rules.sql`
- `backend/src/test/java/com/opspilot/backend/domain/AlertRuleTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertRuleServiceTest.java`
- `backend/src/test/java/com/opspilot/backend/application/AlertRuleIntegrationTest.java`
- `docs/alert-rule-configuration-phase-3c-step-1.md`

Modified:

- `backend/src/main/java/com/opspilot/backend/domain/MonitoredServiceRepository.java` — scoped parent-lock query.
- `backend/src/main/java/com/opspilot/backend/web/exception/GlobalExceptionHandler.java` — domain configuration error mapped to existing 400 format.
- `backend/src/test/java/com/opspilot/backend/web/TenantIsolationIntegrationTest.java` — expanded real-JWT isolation/permission coverage and positive distinct-tenant configurations.

No dependencies, wrapper, frontend, scheduler, health-check implementation or
previous migrations changed. No alert evaluation/lifecycle is included, and no
commit, merge or push is performed.
