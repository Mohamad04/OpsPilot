# Phase 3A — Scheduled HTTP health checks

This milestone adds backend HTTP monitoring, persisted observations and authorised
read APIs. It does not add alert rules, incidents, a broker, UI monitoring screens
or new dependencies. Existing frontend work, authentication, organisation handling,
registration contracts and RBAC are preserved.

## Execution and status semantics

After the initial delay, a Spring fixed-delay task discovers enabled services whose
type is `HTTP`. It checks them sequentially, then waits the configured interval
before the next round. The interval is a delay after a completed round, not a
per-service exact cadence. TCP, ICMP, SNMP and disabled services are skipped.

Each check takes a detached configuration snapshot in a short read transaction.
Network work runs outside database transactions on a bounded worker pool with no
task backlog. The result is stored in a separate transaction. A result is discarded
if the service has since been removed, disabled, changed type or changed its checked
URL/endpoint. An already-started request cannot be recalled merely by disabling it.

A process-local, service-ID lock prevents overlapping checks, including while
persisting the result. If the caller times out, the lock remains held until the
network worker actually exits. A late result is discarded rather than stored twice.
Lock release checks the lock's identity so an old completion cannot release a newer
check's lock. This is **not distributed locking**: multiple application replicas
can independently check the same service.

The scheduler and socket-deadline timer have separate executors. The deadline timer
closes the actual socket, including during TLS handshakes and incomplete header
reads. Native JVM/OS DNS resolution can outlive cancellation; caller waiting remains
bounded, affected workers retain their service locks, and total worker count is
bounded. Exhausted capacity records UNKNOWN without sending another request.
One service/probe/persistence failure does not abort the other services or future rounds.

| State | Meaning |
| --- | --- |
| `enabled` / `disabled` | Existing service configuration; independent of health. |
| `UP` | The latest completed HTTP probe received a final 2xx response. |
| `DOWN` | Non-2xx response, connection/DNS/TLS failure, timeout, or invalid/incomplete HTTP response. |
| `UNKNOWN` | No observation yet, target blocked by policy, capacity unavailable, or an internal probe failure. |

Latest status means the **last recorded observation**, not a guarantee of current
availability. Disabling a service preserves its history and can return
`enabled: false` with a historical `UP`. Timestamps show when that observation
occurred. No automatic staleness threshold is applied in this milestone.
Checks use HTTP status only; JSON health payloads are not interpreted.
Response time uses a monotonic timer through response headers, including DNS and
connection/TLS setup. It does not measure downloading a response body.

## Configuration

Configuration follows the existing environment-variable-backed `application.properties` convention.
Durations below are integer milliseconds. Invalid bounds/order fail application startup.

| Property prefix: `opspilot.monitoring.` | Environment variable | Default | Allowed values |
| --- | --- | --- | --- |
| `enabled` | `MONITORING_ENABLED` | `true` | Boolean; false removes the scheduled task. Read APIs remain available. |
| `interval-ms` | `MONITORING_INTERVAL_MS` | `60000` | 1000–86400000 |
| `initial-delay-ms` | `MONITORING_INITIAL_DELAY_MS` | `10000` | 0–86400000 |
| `connect-timeout-ms` | `MONITORING_CONNECT_TIMEOUT_MS` | `2000` | 100–10000; no greater than request timeout |
| `request-timeout-ms` | `MONITORING_REQUEST_TIMEOUT_MS` | `5000` | 100–30000 |
| `workers` | `MONITORING_WORKERS` | `4` | 1–16; bounds workers, including stuck DNS work |
| `allowed-ports` | `MONITORING_ALLOWED_PORTS` | `80,443` | Nonempty list, each 1–65535; deployment-admin configuration |

Changing ports never permits private/loopback destinations. There is deliberately
no development switch that bypasses outbound address or TLS validation.

## Outbound HTTP security

The checker validates the configured URL before connecting, rejects credentials,
base-URL queries/fragments, endpoint authority changes, disallowed schemes/ports,
local hostnames and non-public/special destination addresses. All resolved IPv4/IPv6
answers must pass policy. A mixed public/private DNS answer fails closed. The
connection uses the already-validated IP directly, with the original HTTP Host and
TLS peer hostname; it performs no second DNS lookup, fallback or redirect. This
addresses the validation/connection gap described by the
[OWASP SSRF prevention guidance](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

The public-address policy excludes private, loopback, link-local, shared-address,
multicast, reserved/documentation and relevant IPv6 transition ranges. It also
blocks the [Azure platform virtual IP](https://learn.microsoft.com/en-us/azure/virtual-network/what-is-ip-address-168-63-129-16).
Deployment egress controls should additionally restrict access to the platform's
own infrastructure, including infrastructure using publicly routed addresses.

The existing dependencies do not provide an HTTP client with per-request DNS
pinning. To avoid a DNS check followed by an unchecked second lookup, this probe
uses a small HTTP/1.0/1.1 status/header reader over JDK sockets. It sends GET with
Connection: close, bounds headers to 16 KiB, never follows redirects and never
reads/stores response bodies, cookies, response headers or exception messages.
Failure details are fixed/sanitised strings, at most 255 characters; logs include
service IDs and exception class names, not URLs, credentials or database messages.

For HTTPS, the socket is layered over the pinned connection with the original
hostname and JDK default trust validation. HTTPS endpoint identification is explicitly
enabled; no trust-all manager or hostname-verification bypass is used. See the
[JDK socket API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLSocketFactory.html).
Requests do not carry the user's JWT, cookies or application credentials and do not
use an ambient proxy. Base paths are preserved: `https://host/api/` + `/health?full=true`
checks `https://host/api/health?full=true`.

## Database change

`V6__create_health_check_results.sql` adds `health_check_results`:

- UUID primary key, organisation ID and monitored service ID.
- UTC `started_at` / `completed_at`, observed status, nullable HTTP status code,
  nonnegative response time in milliseconds, nullable failure detail.
- Status/code/timestamp constraints.
- A composite foreign key to `(monitored_services.id, organisation_id)` prevents
  results referencing a service from a different organisation, even via a bad insert.
  Deleting a service cascades to its observations.
- A matching unique key on the service table and a history index on organisation,
  service, descending start timestamp and ID.

Existing migrations and service rows are unchanged. There is no result backfill:
existing services start with UNKNOWN until a result is recorded. Results are append-only;
API history is bounded, but automatic database retention/purging is not implemented.

## API contracts

Both GET routes require the existing validated bearer JWT. They reuse
`MonitoredServicesService.findById` for ACTIVE local-user resolution, membership
and `(organisationId, serviceId)` scoping. All four organisation roles can read.
Non-members and service/tenant mismatches receive the existing 404 behaviour;
missing/invalid JWTs receive 401. No endpoint can trigger arbitrary checks or accept
a caller-supplied identity, role or result.

Prefix: `/api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/health`

`GET /latest` → 200:

```json
{
  "organisationId": "<uuid>",
  "monitoredServiceId": "<uuid>",
  "enabled": true,
  "status": "UP",
  "latestResult": {
    "id": "<uuid>",
    "organisationId": "<uuid>",
    "monitoredServiceId": "<uuid>",
    "startedAt": "2026-10-26T12:00:00Z",
    "completedAt": "2026-10-26T12:00:00.025Z",
    "status": "UP",
    "httpStatusCode": 200,
    "responseTimeMs": 25,
    "failureDetail": null
  }
}
```

Without results: `status: "UNKNOWN"`, `latestResult: null`.

`GET /history?limit=20` → 200 array of result objects in newest-started-first
order (UUID breaks timestamp ties). Default limit 20; allowed 1–100; bad limits
return the existing JSON API error shape with HTTP 400. Queries apply the limit
in PostgreSQL and always constrain both organisation and service IDs.
Existing service list/detail JSON is unchanged.

## Files created

Under `backend/src/main/java/com/opspilot/backend/`:

- `application/HealthCheckTarget.java`, `application/HealthObservation.java`: detached check input/output.
- `application/HealthTargetPolicy.java`: URI, address and port policy/DNS pinning.
- `application/HttpHealthProbe.java`: bounded HTTP/TLS transport and outcome classification.
- `application/HealthCheckStore.java`: short configuration/result transactions.
- `application/HealthCheckRunner.java`: bounded workers, per-service locks and timeout handling.
- `application/HealthCheckScheduler.java`: periodic discovery and failure isolation.
- `application/HealthResultService.java`: authorised latest/history queries.
- `config/MonitoringProperties.java`, `config/MonitoringConfiguration.java`: validated settings and separate executors.
- `domain/HealthStatus.java`, `domain/HealthCheckResult.java`, `domain/HealthCheckResultRepository.java`: persisted observations.
- `web/HealthCheckController.java`, `web/dto/HealthCheckResponse.java`, `web/dto/LatestHealthResponse.java`: read API and DTOs.

Other new files:

- `backend/src/main/resources/db/migration/V6__create_health_check_results.sql`.
- `backend/src/test/java/com/opspilot/backend/application/HealthTargetPolicyTest.java`.
- `backend/src/test/java/com/opspilot/backend/application/HttpHealthProbeTest.java`.
- `backend/src/test/java/com/opspilot/backend/application/HealthCheckRunnerTest.java`.
- `backend/src/test/java/com/opspilot/backend/application/HealthCheckSchedulerTest.java`.
- `backend/src/test/java/com/opspilot/backend/application/HealthMonitoringIntegrationTest.java`.
- `docs/scheduled-http-health-checks-phase-3a.md`.

Modified files:

- `backend/src/main/java/com/opspilot/backend/domain/MonitoredServiceRepository.java`: select enabled HTTP services.
- `backend/src/main/java/com/opspilot/backend/web/exception/GlobalExceptionHandler.java`: preserve API error shape for history-limit validation.
- `backend/src/main/resources/application.properties`: monitoring defaults/environment overrides.
- `backend/src/test/java/com/opspilot/backend/application/UserProvisioningIntegrationTest.java`: disable background monitoring in unrelated fixtures.
- `backend/src/test/java/com/opspilot/backend/web/TenantIsolationIntegrationTest.java`: preserve existing tests, disable background fixture polling and add real-JWT health API isolation tests.

The tenant-isolation test was already uncommitted before this milestone. Its prior
contents and all unrelated uncommitted frontend/documentation work are preserved.

## Verification

Controlled tests cover 2xx, non-2xx, redirects, malformed/incomplete/oversized HTTP
responses, DNS/connection/TLS failures, deadlines, disabled/non-HTTP services,
configuration changes, persistence, history ordering/limits, database tenant FK,
lock/capacity behaviour, unexpected errors and scheduler recovery. Spring context
tests verify repeated scheduling, the disabled flag and deadline-executor independence.
Real JWT/HTTP/PostgreSQL tests exercise authenticated health reads and cross-tenant
rejection. No external websites are used for health probes in tests.

Local HTTP transport fixtures replace **only the target policy** in their test
context to route to loopback. Separate policy tests exercise the real production
address restrictions, including mixed DNS answers and rebinding lookup count.
No production private-network exception is added. Other integration tests use
disposable PostgreSQL containers; no business database or Keycloak account is changed.

Commands from `backend` (existing cached Maven and dependencies, offline):

```powershell
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o -DskipTests package
```

From `frontend`:

```powershell
npm.cmd run lint
npm.cmd run build
npm.cmd run test:run -- --maxWorkers=1 --testTimeout=15000
```

Final results are recorded after the final scheduler regression run and packaging complete.
The first complete backend run passed 345 tests. Initial sandbox compilation could
not access an existing cached JAR; using authorised Maven/Docker access resolved it.
One automatic review timed out before starting a later run; the permitted retry started successfully.

## Local verification and limitations

1. Start the existing database/Keycloak and backend using the authentication guide.
   Flyway applies V6 automatically. No new Keycloak role or audience setup is required.
2. Leave `MONITORING_ENABLED=true`. For quicker manual checks, use
   `MONITORING_INTERVAL_MS=5000`; retain bounded timeout values.
3. Through the existing authenticated registration workflow, add an enabled HTTP
   service with a public destination and a permitted port. Private/localhost targets
   intentionally produce UNKNOWN; use the automated fixtures for entirely local tests.
4. After the initial delay/round, read `/health/latest` and `/health/history` with
   the existing API client or authorised API tooling. A 2xx endpoint yields UP;
   non-2xx, refusal or timeout yields DOWN. Redirects are not followed.
5. Disable the service using the existing PATCH endpoint. Future rounds skip it;
   history stops growing and latest still reports the last observation separately
   from `enabled: false`.
6. Confirm a non-member cannot read either route, a VIEWER can read its own tenant,
   and another tenant's service ID cannot be substituted into the path.

Not exercised: live public HTTPS/Keycloak browser smoke tests or a distributed
deployment. The frontend is intentionally unchanged and does not yet display the
new observations; its earlier monitoring placeholders will need a separate UI phase.
Further work includes retention, larger-fleet scheduling/pagination, stale-result
presentation, controlled private-network monitoring architecture, TLS fixture depth,
and multi-instance coordination. Do not add alerting/incident behaviour as part of this slice.
