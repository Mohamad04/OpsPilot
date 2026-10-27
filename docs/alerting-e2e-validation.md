# Alerting end-to-end validation

## Result and evidence source

**The Phase 3C alert lifecycle passed manual end-to-end validation, as confirmed
by the user:** HTTP 200 / UP -> HTTP 503 / DOWN -> ACTIVE after three consecutive
DOWN checks -> HTTP 200 / UP -> RESOLVED after two consecutive UP checks.

These are user-confirmed observations from the running application. The agent
did not independently observe the authenticated browser session. This report
does not present the manual test as an automated browser test or a new execution
of the backend/frontend test suites. Exact execution timestamps, service and
organisation IDs, alert IDs, screenshots and API/SQL exports were not supplied
and are not invented here.

## Environment and setup

The test used the existing local OpsPilot development environment:

- React frontend at `http://localhost:5173`, with existing Keycloak authentication
  at `http://localhost:8082` and an authorised organisation context.
- Spring Boot / Java 21 backend at `http://localhost:8080`, PostgreSQL, scheduled
  HTTP checks and the Phase 3C alert rule, evaluation, read API and frontend work.
- The disposable Node.js demo under [`tools/alert-e2e-demo/`](../tools/alert-e2e-demo/README.md),
  using built-in modules, bound only to `127.0.0.1:8765` with GET `/health`.
- A temporary localhost.run SSH tunnel exposing only that demo over public
  HTTPS. The working origin recorded in the preceding tooling verification was
  `https://d991feded1488c.lhr.life`; it is historical, not a permanent or currently
  verified endpoint. A repeat run must use its newly assigned URL.
- An isolated TEST monitored service pointing at the tunnel origin and `/health`,
  with monitoring and alerting enabled. The tested thresholds were three
  consecutive failures and two consecutive recoveries. Other effective rule
  defaults are documented in the [alert-rule report](alert-rule-configuration-phase-3c-step-1.md);
  the user's confirmation does not separately establish the severity value.

The experiment used a disposable demo target and did not affect production
services or the portfolio website. There was no need to change system DNS,
OpsPilot's SSRF policy, TLS verification or authentication. Backend requests
targeted the public HTTPS tunnel, rather than permitting a private-network
monitoring exception. No Python, npm dependency or global tunnel installation
was required.

## Execution and observed results

| Step | Action | Expected result | User-confirmed observation |
| --- | --- | --- | --- |
| 1 | Demo serves HTTP 200 | Scheduled check reports UP | OpsPilot displayed UP |
| 2 | Switch demo to HTTP 503 | Scheduled check reports DOWN | OpsPilot detected DOWN |
| 3 | Keep demo DOWN for three consecutive checks | Open an ACTIVE alert at threshold 3 | An ACTIVE alert was created |
| 4 | Restore HTTP 200 | Scheduled check reports UP | OpsPilot detected UP |
| 5 | Keep demo UP for two consecutive checks | Resolve the active alert at threshold 2 | The alert transitioned to RESOLVED |

This validates the manually observed failure and recovery path through the live
demo, scheduler, monitoring UI and alert lifecycle. UP is a health observation;
ACTIVE and RESOLVED are alert lifecycle states. A service can report UP while an
alert remains ACTIVE until the recovery threshold is reached.

The user confirmed the alert's ACTIVE -> RESOLVED transition, but supplied no
record ID comparison. Independent SQL persistence inspection, evidence-reference
comparison and a check for duplicate ACTIVE alerts are not claimed by this
manual confirmation. A second outage, UNKNOWN handling, restarts, concurrency,
permissions and cross-tenant behaviour were not reported as part of this run.

## Automated checks versus manual confirmation

The earlier tooling verification recorded six successful HTTP assertions: local
and public UP -> DOWN -> UP each returned 200/503/200 with the expected JSON.
Java 21 on the same Windows host also resolved the public hostname and received
HTTPS 200 with normal TLS validation. These were network/demo checks, not
authenticated alert lifecycle assertions.

That earlier attempt could not access a browser and correctly left the full
lifecycle unverified. The user's subsequent manual confirmation completes that
specific lifecycle check; it does not retroactively turn the earlier automation
into a passing browser E2E test. The tooling README retains the earlier failures
and handoff as historical evidence.

Existing automated implementation results remain in the
[evaluation report](alert-evaluation-phase-3c-step-2.md),
[read API report](alert-management-api-phase-3c-step-3.md) and
[frontend report](alerting-frontend-phase-3c-step-4.md).
No application tests, lint or build were rerun for this documentation-only update.

## Reproduce the experiment

1. Start the existing local OpsPilot stack normally. Keep authentication and
   outbound security enabled. From the repository root, restore the fixture to UP:

   ```powershell
   .\tools\alert-e2e-demo\Set-Health.ps1 -State UP
   node .\tools\alert-e2e-demo\server.mjs
   ```

   If the existing demo is already running, reuse it rather than starting a
   second listener.
2. In another terminal, start a localhost.run HTTPS tunnel forwarding to
   `127.0.0.1:8765`. Use the host-key-checking command in the
   [demo instructions](../tools/alert-e2e-demo/README.md#processes-intentionally-left-running).
   It requires the previously pinned host key; for a fresh environment, establish
   trust through normal SSH host-key verification first. Do not bypass a changed
   key. The explicit IPv4 target is the configuration that worked in the preceding
   verification; attempts forwarding to `localhost` returned 502.
3. Verify the newly assigned public HTTPS `/health` returns HTTP 200. Sign in to
   OpsPilot, select an authorised organisation and create a dedicated disposable
   HTTP service in environment TEST. Use the public origin as Base URL and
   `/health` as Health Endpoint. Enable monitoring and verify alerting is enabled
   with failure threshold 3 and recovery threshold 2.
4. Wait for an actual scheduled UP observation, then run:

   ```powershell
   .\tools\alert-e2e-demo\Set-Health.ps1 -State DOWN
   ```

   Verify public HTTP 503 and wait for three distinct consecutive scheduled DOWN
   observations. Refresh monitoring and alert history as needed; alert data uses
   REST/manual refresh. Confirm the ACTIVE alert and record its ID privately.
5. Restore the demo:

   ```powershell
   .\tools\alert-e2e-demo\Set-Health.ps1 -State UP
   ```

   Verify public HTTP 200. Wait for two distinct consecutive scheduled UP
   observations, then refresh alerts and confirm the alert is RESOLVED. On a
   repeat run, compare IDs and evidence explicitly if those assertions are needed.

The documented scheduler default is a 60-second delay after each completed round;
local overrides and round duration can change timing. Count scheduled observations,
not elapsed time or manual requests. Manually calling `/health` does not advance
the OpsPilot evaluator. Do not change the rule during the sequence, because
configuration changes reset streaks.

## Cleanup procedure and status

After collecting evidence:

1. Disable only the dedicated TEST service before stopping the tunnel, so an
   expired hostname cannot create a later artificial outage. Preserve alert and
   health history; no database deletion is required.
2. Restore the demo state to UP with `Set-Health.ps1 -State UP`.
3. Stop only the experiment's SSH tunnel and demo server, using Ctrl+C in their
   terminals. For background processes, use the ignored process manifest and
   inspection instructions in the tooling README. Verify current command lines
   before stopping a PID; historical PIDs may have been reused.
4. Stop the experiment-started Vite process if no longer needed. Do not stop
   unrelated backend, database, Keycloak or SSH processes.
5. Keep `server.mjs`, `Set-Health.ps1` and `verify-public.mjs` for reproduction.
   The existing `verify-public.mjs` targets Cloudflare Quick Tunnels; it is not a
   localhost.run lifecycle verifier. Runtime state, logs and local binaries remain
   ignored under `.runtime/`; do not publish tokens or credentials with evidence.

**At the original documentation handoff, final cleanup was not verified.** The preceding agent handoff intentionally
left the demo, tunnel and frontend running for manual testing. The user's success
confirmation does not establish that the TEST service was disabled, processes
stopped or its history inspected. This documentation update performs no cleanup
and changes no application/database state.

## Final Cleanup Verification

**Current outcome: temporary process cleanup completed on 2026-10-27**, after
the user manually confirmed the service was Disabled following a refreshed
authenticated UI view. This is **user confirmation, not an API verification
performed by the agent**. The resumed shutdown and post-cleanup checks are
recorded below; the initial blocked attempt is retained as history.

### Initial blocked attempt

**Cleanup attempt date: 2026-10-27.** Read-only verification completed at
13:35:48 UTC. **At that time cleanup was blocked, not complete.** The successful user-confirmed
manual lifecycle above is preserved; this cleanup attempt does not rerun it.

### Monitoring disablement and authentication boundary

The target for cleanup is the existing service named `opspilot-alert-e2e` in
environment TEST. Its organisation/service IDs and current enabled state could
not be verified through an authenticated API request:

- Browser tooling returned `No browser is available`.
- No approved token was supplied; the explicit `OPSPILOT_API_TOKEN` and
  `OPSPILOT_ACCESS_TOKEN` environment variables were absent. No credentials were
  extracted from browser profiles, logs or other storage.
- An unauthenticated GET `/api/organisations` returned HTTP 401 as expected.
  No disabling request was attempted without authentication.

Therefore monitoring is **not confirmed disabled**. Per the cleanup safety gate,
no demo, tunnel or frontend process was terminated. No database access was used
to bypass authentication, and no service or history record was changed.

### Verified process, port and infrastructure state

The historical PIDs were rechecked using live process executable paths and
command lines, rather than trusted from the earlier manifest alone.

| Item | Verified state at inspection | Evidence |
| --- | --- | --- |
| Test service monitoring | Blocked / enabled state unknown | No authenticated organisation/service lookup or post-disable GET available |
| Demo server | Running, PID 43372 | `node.exe` running `C:\projects\OpsPilot\tools\alert-e2e-demo\server.mjs`; listener `127.0.0.1:8765`; GET `/health` returned 200 with `{"status":"UP"}` |
| localhost.run tunnel | Running, PID 43424 | Windows OpenSSH `ssh.exe`, project demo known-hosts file, `-R 80:127.0.0.1:8765 nokey@localhost.run`, strict host-key checking enabled |
| Temporary frontend | Running, PID 27476 | `node.exe` running the project's Vite CLI with `--host 127.0.0.1 --port 5173 --strictPort`; IPv4 listener on port 5173 |
| Demo-specific descendants | None found beneath the three verified roots | Recursive parent/child process snapshot returned only those three roots; no child termination was needed |
| Other frontend listener | Preserved | Separate PID 29072 listening on `[::1]:5173`; not identified as a demo-owned process and left untouched |
| OpsPilot backend | Operational | Port 8080 listener PID 36128; GET `/actuator/health` returned HTTP 200 with status UP |
| PostgreSQL | Running and healthy; untouched | `opspilot-postgres-1`, `postgres:18.4-alpine`, Docker status `Up 4 days (healthy)`; port 5432 listeners present |
| Keycloak | Running and reachable; untouched | `opspilot-keycloak-1`, `quay.io/keycloak/keycloak:26.7.1`, Docker status `Up 4 days`; port 8082 listeners and HTTP 302 response |

**Processes stopped: none. Ports freed: none.** The tunnel process is running;
public tunnel delivery was not retested during this blocked cleanup. Container
status and HTTP/listener checks establish the reported infrastructure state,
not a comprehensive validation of every development service or database record.

### Required manual action and resumption

1. Open `http://localhost:5173` and sign in. Select the organisation containing
   `opspilot-alert-e2e`, open Services and find that exact existing service.
2. Open its details and verify environment **TEST** and the expected demo target
   (`https://d991feded1488c.lhr.life`, health endpoint `/health`). Do not create
   another service, disable a similarly named production service or delete data.
3. Choose **Disable service** and confirm **Disable service** in the dialog.
   If it already displays Disabled, do not toggle it back on.
4. Reload the details and verify the authenticated service GET response contains
   `enabled: false` for the same organisation/service IDs. The UI must show
   Disabled. If the action fails or returns 403, use an account authorised to
   disable that TEST service; do not change memberships or bypass security.
5. Confirm that result before resuming process cleanup. Never share access tokens.
   On resumption, reverify all PIDs/command lines and descendants, attempt graceful
   shutdown of only the demo-owned processes, and repeat port/backend/container
   checks. The process information above is an inspection snapshot, not permission
   to kill a later process reusing one of these PIDs.

Only this report and the demo README's safety instructions were updated during
this attempt. Application code, tests, dependencies, security settings, demo
scripts, health results, resolved alerts and Git history were preserved. No
process was stopped, database reset performed, or commit/push/merge made.

### Resumed cleanup after manual disablement confirmation

The user subsequently confirmed that they disabled `opspilot-alert-e2e` through
the authenticated OpsPilot interface and refreshed the page to confirm Disabled.
They explicitly authorised resuming process cleanup based on that manual
confirmation. No authenticated service API verification was performed by the
agent, no organisation/service IDs were invented, and no evidence contradicting
the user's confirmation was observed. No API mutation or database access was
used during this resumed cleanup.

**Execution date: 2026-10-27 UTC.** Shutdown finished at 13:40:38 UTC; final
process, listener and infrastructure verification finished at 13:41:14 UTC.

Before termination, all three processes still matched the expected executable
paths, command lines and creation times. The demo and Vite executables were the
existing JetBrains-managed Node 24.19.0 runtime; SSH was Windows OpenSSH. The
actual commands identified the project demo script, the project-specific tunnel
known-hosts file and forwarding target, and the temporary Vite invocation.
Process identity was checked again immediately before shutdown. Recursive
inspection found no exclusively associated children requiring termination.

A graceful window-close request was attempted for each process. All three were
headless and returned false, so graceful window closure was unavailable. Only
those verified process instances were then forcibly terminated and their exit
confirmed; this is not reported as graceful shutdown.

| Item | Final status | Evidence source |
| --- | --- | --- |
| `opspilot-alert-e2e` monitoring | Disabled, user-confirmed | User's authenticated UI action and refresh; not independently API-verified by the agent |
| Demo Node server | Stopped | PID 43372 had run `tools/alert-e2e-demo/server.mjs`; exit confirmed and no matching demo command remained |
| localhost.run SSH tunnel | Stopped | PID 43424 had used the project demo known-hosts file and `-R 80:127.0.0.1:8765 nokey@localhost.run`; exit confirmed and no matching tunnel command remained |
| Temporary Vite frontend | Stopped | PID 27476 had run the project Vite CLI with `--host 127.0.0.1 --port 5173 --strictPort`; exit confirmed |
| Demo-specific child processes | None remaining | Post-cleanup process snapshot found no descendants of these roots; none had been present before shutdown |
| Port 8765 | Freed | No listener on port 8765; GET `http://127.0.0.1:8765/health` failed with ECONNREFUSED |
| Temporary IPv4 frontend port | Freed | No `127.0.0.1:5173` listener; HTTP connection failed with ECONNREFUSED |
| Separate IPv6 frontend | Preserved | PID 29072 still listening on `[::1]:5173`; port 5173 is therefore not globally free |
| Spring Boot backend | Operational and preserved | Same listener PID 36128 on port 8080; actuator health returned HTTP 200 / UP after shutdown |
| PostgreSQL | Preserved, running and healthy | `opspilot-postgres-1` remained `Up 4 days (healthy)` with port 5432 listeners |
| Keycloak | Preserved and reachable | `opspilot-keycloak-1` remained `Up 4 days`, port 8082 listeners remained, and HTTP returned 302 |
| Docker infrastructure | Preserved | Docker remained responsive and the existing PostgreSQL/Keycloak containers remained running; no container stop/restart operation was issued |

These are process/listener, HTTP and container-status observations. The public
tunnel URL was not retested after shutdown; tunnel termination is established
by the verified SSH process exit and absence of matching tunnel processes.
Infrastructure checks do not claim a complete audit of unrelated development
services. The service's disabled state continues to rely on the user's manual
confirmation; no fresh health/alert record comparison or direct SQL inspection
was performed.

Only this documentation file changed during the resumed cleanup. Demo source,
state-switch and verifier scripts remain present and unchanged. No application
code, credentials, dependencies, security settings, service records, health-check
history or resolved alerts were modified or deleted by the agent. Existing
uncommitted work was preserved. No commit, push, merge or database reset occurred.
No further manual action is required for this authorised process cleanup.

## Related documentation

- [Scheduled HTTP health checks (Phase 3A)](scheduled-http-health-checks-phase-3a.md)
- [Monitoring dashboard (Phase 3B)](monitoring-dashboard-phase-3b.md)
- [Configurable rules (Phase 3C.1)](alert-rule-configuration-phase-3c-step-1.md)
- [Persisted evaluation (Phase 3C.2)](alert-evaluation-phase-3c-step-2.md)
- [Read-only alert API (Phase 3C.3)](alert-management-api-phase-3c-step-3.md)
- [React alerting frontend (Phase 3C.4)](alerting-frontend-phase-3c-step-4.md)
