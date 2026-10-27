# Disposable alert lifecycle demo

**Subsequent manual validation:** the user confirmed the complete UP -> DOWN ->
ACTIVE (three failures) -> UP -> RESOLVED (two recoveries) lifecycle using this
demo and a temporary localhost.run HTTPS tunnel. See the
[E2E validation report](../../docs/alerting-e2e-validation.md) for the observed
results, reproduction steps and cleanup procedure. The earlier automated attempts
and handoff below are historical: their browser limitation does not negate the
later manual result. Current tunnel availability and final cleanup remain
unverified; recorded URLs and PIDs must not be assumed current.

The demo uses Node's built-in `node:http` and other standard modules. No npm
dependencies, Python, virtual environment, Docker or system-wide installation
is needed. It binds **only `127.0.0.1:8765`**.

- GET `/health`: 200 `{"status":"UP"}` when the state file is absent or says UP;
  503 `{"status":"DOWN"}` when it says DOWN.
- Other paths return 404; other methods on `/health` return 405. There is no
  public state-changing endpoint, directory/file serving or backend proxy.
- Invalid/unreadable state returns 500 UNKNOWN rather than a false UP.
- `.runtime/health-state.txt` is local-only. Set-Health.ps1 writes it atomically.
- Responses disable caching. State, binary, logs and verification evidence are
  ignored by Git under `.runtime/`.

## Run locally

From `C:\projects\OpsPilot`, in a terminal:

```powershell
node .\tools\alert-e2e-demo\server.mjs
```

In another terminal:

```powershell
.\tools\alert-e2e-demo\Set-Health.ps1 -State DOWN
.\tools\alert-e2e-demo\Set-Health.ps1 -State UP
```

The switch script does not need a server restart. A previous explicit DOWN state
survives server restarts until changed; an absent file defaults to UP. The server
does not bind to a LAN interface or configurable application port.

## Isolated cloudflared installation

The implementation detected no cloudflared on PATH. It downloaded official
Windows x64 cloudflared **2026.10.4** into `.runtime/cloudflared.exe` and verified
its SHA-256 against the official GitHub release API asset digest:

```text
411b16d40c8d872243efeb52c550053d00af0a8a97fbb32e730fb8aba429eaa8
```

This binary is ignored, reusable locally and not a committed dependency. No PATH,
service, system DNS, Cloudflare account or global configuration was changed.
For a fresh checkout, first use an existing installation if available:

```powershell
Get-Command cloudflared -ErrorAction SilentlyContinue
```

Otherwise install the same verified release locally:

```powershell
$demoRuntime = Join-Path (Get-Location).Path 'tools\alert-e2e-demo\.runtime'
[System.IO.Directory]::CreateDirectory($demoRuntime) | Out-Null
$demoBinary = Join-Path $demoRuntime 'cloudflared.exe'
Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 `
  -Uri 'https://github.com/cloudflare/cloudflared/releases/download/2026.10.4/cloudflared-windows-amd64.exe' `
  -OutFile $demoBinary
$demoHash = (Get-FileHash -LiteralPath $demoBinary -Algorithm SHA256).Hash.ToLowerInvariant()
if ($demoHash -ne '411b16d40c8d872243efeb52c550053d00af0a8a97fbb32e730fb8aba429eaa8') {
    throw 'cloudflared checksum mismatch; do not execute this file'
}
& $demoBinary --version
```

Official references: [Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)
and [the verified release](https://github.com/cloudflare/cloudflared/releases/tag/2026.10.4).
A Quick Tunnel hostname is temporary and changes for each run. It stops serving
the demo when its cloudflared process stops. Anyone with the URL can request
this demo; it contains no business data or secrets.

## Repeatable HTTP verification with automatic cleanup

Ensure no other process owns port 8765. Then run:

```powershell
node .\tools\alert-e2e-demo\verify-public.mjs
```

The verifier starts only its own demo and Quick Tunnel, using the project-local
binary if present or cloudflared on PATH. It uses its own empty runtime config
and loopback-only metrics listener. It never points a tunnel at OpsPilot,
Keycloak, PostgreSQL, Jenkins or another local server.

It tests local UP -> DOWN -> UP and route/method restrictions, then waits for
the allocated public HTTPS URL and tests public UP -> DOWN -> UP. Network/DNS
errors are recorded instead of treated as successful health responses. Its
finally block stops its child processes and restores the original demo state.
It refuses an occupied port and never stops an unrelated existing process.
Ctrl+C requests the same cleanup. A forced OS termination cannot run finally.

Evidence is written to `.runtime/verification-result.json`; tunnel/server output
goes to `.runtime/verification.log`. A failed public verification exits nonzero.
This verifies HTTP behavior only, not the OpsPilot scheduler or alert lifecycle.

## Manual full OpsPilot lifecycle test

An authenticated OpsPilot user with write permission is required. Before starting
this section, the chosen network must resolve/reach the generated Quick Tunnel
hostname normally. Do not bypass DNS, disable TLS verification, weaken SSRF
checks or add loopback/private addresses to OpsPilot's outbound allowlist.

1. Start the demo in terminal 1. Set its state to UP.
2. In terminal 2, create a Quick Tunnel and leave it running:

   ```powershell
   $demoRuntime = Join-Path (Get-Location).Path 'tools\alert-e2e-demo\.runtime'
   [System.IO.Directory]::CreateDirectory($demoRuntime) | Out-Null
   [System.IO.File]::WriteAllText((Join-Path $demoRuntime 'tunnel-config.yml'), "{}`n")
   & (Join-Path $demoRuntime 'cloudflared.exe') tunnel `
     --config (Join-Path $demoRuntime 'tunnel-config.yml') `
     --no-autoupdate --url http://127.0.0.1:8765 --metrics 127.0.0.1:0
   ```

   If using a pre-existing PATH installation instead, replace the executable
   path with `cloudflared`. Only use the newly printed HTTPS hostname. Previously
   recorded URLs from verification attempts are stopped and must not be reused.
3. Confirm `<new-public-url>/health` returns HTTP 200/UP. Start the existing
   frontend if needed (`npm.cmd run dev` from frontend) and sign in through
   Keycloak. Select an organisation in which you can register services.
4. Create **one dedicated disposable** service, e.g. `Alert E2E demo 2026-10-27`:
   serviceType HTTP, environment TEST, base URL the new HTTPS origin (without
   `/health`), health endpoint `/health`, owner `Local E2E test`, enabled true.
   Do not edit any production service or the portfolio website.
5. Verify the effective rule is enabled, three failures, two recoveries, HIGH.
   New services already receive these effective defaults without a persisted
   rule. Wait for an actual scheduler result showing UP in this service's health
   history. Manual calls to `/health` alone do not create OpsPilot observations.
6. Run Set-Health.ps1 DOWN. Confirm public HTTP 503/DOWN. Wait for **three distinct
   persisted DOWN observations** for this service. The scheduler interval is
   60 seconds by default, but environment overrides and round duration can vary.
   Inspect recorded timestamps rather than assuming three external HTTP calls
   are three scheduler observations.
7. Refresh this service's alert history or the filtered Alerts page. Verify one
   ACTIVE HIGH alert, occurrenceCount at least 3, and a triggering evidence ID
   belonging to the qualifying DOWN observation. Keep its alert ID.
8. Run Set-Health.ps1 UP. Confirm public HTTP 200/UP. After two distinct scheduled
   UP observations, refresh alerts. The **same alert ID** must now be RESOLVED,
   with resolvedAt and resolutionHealthCheckResultId matching the recovery.
   The alert remains in history. Record the health/alert API JSON or screenshots
   for this dedicated fixture only; never copy access tokens into reports.
9. Disable **only this TEST service** after verification, before stopping the
   tunnel, so the expired hostname cannot generate a new artificial outage.
   Preserve its history if needed as evidence. Stop terminal 2's cloudflared and
   terminal 1's Node server with Ctrl+C. Do not stop the existing backend.

Relevant authenticated read endpoints:

```text
GET /api/organisations/{org}/monitored-services/{service}/health/latest
GET /api/organisations/{org}/monitored-services/{service}/health/history
GET /api/organisations/{org}/monitored-services/{service}/alerts
GET /api/organisations/{org}/alerts/{alertId}
```

OpsPilot should use the public HTTPS URL on port 443. Its existing DNS/IP
validation, TLS/SNI and redirect restrictions stay enabled. Port 8765 is only
the tunnel's local origin, never a private-network exception in OpsPilot.

## Actual execution results - 2026-10-27 UTC

- Node v24.19.0 and PowerShell were available; cloudflared was not on PATH.
- The official isolated binary version and SHA-256 were verified successfully.
- Node syntax checks passed for both .mjs files. The PowerShell script was
  executed successfully for repeated UP/DOWN transitions after fixing an initial
  File.Replace null-backup-path compatibility error.
- Local default UP returned 200; DOWN returned 503; restored UP returned 200,
  with the expected JSON. Root/file paths returned 404 and POST /health returned
  405. A listener inspection confirmed `127.0.0.1:8765`, not a wildcard bind.
- Two temporary hostnames were allocated and their tunnels registered QUIC
  connections with Cloudflare. Public HTTP verification **did not pass**:
  Windows/Node's normal resolver returned NXDOMAIN/ENOTFOUND for the generated
  trycloudflare.com hosts, including a longer second readiness window. No public
  HTTP 200 or 503 is claimed. A diagnostic public DNS-over-HTTPS lookup returned
  public A records for the first hostname, showing a difference from the local
  resolver. No resolver setting or application security policy was changed.
- The existing backend at 127.0.0.1:8080 returned actuator health HTTP 200.
  Unauthenticated organisation and alert requests returned 401 as expected.
  No frontend listener was present at 5173, and the browser tool reported
  `No browser is available`. No authenticated OpsPilot session was accessible.
- No TEST service, membership, organisation or application database record was
  created or changed. ACTIVE/RESOLVED lifecycle assertions remain **unverified**.
- The verifier reported both owned processes stopped after each attempt and
  restored the absent state file. The local binary/logs remain ignored for reuse.
  Production services, the portfolio, backend security and Git history were
  untouched. Nothing was committed or pushed.

Remaining actions at that point: use a network/resolver that can reach the generated Quick
Tunnel host normally; start a fresh demo/tunnel; sign in and perform steps 4-9
above. This requires actual scheduled observations and authenticated alert
responses before an end-to-end pass can be claimed.

## localhost.run continuation - 2026-10-27 UTC

The existing demo was reused without changing its server or state-switch script.
Windows OpenSSH 9.5p2 was already installed; nothing was installed globally.

**Working temporary origin:** `https://d991feded1488c.lhr.life`

**Health URL:** `https://d991feded1488c.lhr.life/health`

This URL is temporary and only valid while this SSH session remains connected.
Earlier assigned hostnames returned 502 `no tunnel`; do not reuse them.

### Verified results

- The requested `80:localhost:8765` SSH forwarding command connected and assigned
  hostnames, but public requests returned 502 `no tunnel`, including after a
  reconnect and a foreground-session check. Normal DNS resolution succeeded.
- The final reconnect used `80:127.0.0.1:8765`, matching the existing demo's
  IPv4-only listener. This worked. The observations identify a working forwarding
  configuration; they do not conclusively establish the provider's internal
  cause of the earlier 502 responses.
- Six HTTP assertions passed: local and public UP -> DOWN -> UP returned
  200/503/200 with the exact expected JSON. Public observations were recorded
  at 13:07:53Z, 13:07:55Z and 13:07:56Z. State was restored to UP in finally.
- Node's ordinary resolver returned public IPv4 addresses. Java 21.0.12 on the
  same Windows host also resolved the final hostname and received HTTPS 200
  with `{"status":"UP"}`, using normal TLS validation and no redirect following.
  This is a Java network smoke test, not an authenticated OpsPilot probe result.
- SSH debug output confirmed a matching pinned ED25519 host key, successful
  remote forwarding and an incoming forwarded TCP channel. No private key or
  SSH agent was used. Initial trust used accept-new (trust on first use) in
  `.runtime/localhost-run-known_hosts`; subsequent connections required
  `StrictHostKeyChecking=yes`. The pinned fingerprint is
  `SHA256:pG6qrBxubYfWa1Zadu/V0NUgjEDiBds/7e2xzte/QNM`.
  This fingerprint was not independently authenticated out of band.
- Existing frontend started successfully at `http://localhost:5173` (HTTP 200).
  Backend organisation requests without authentication returned HTTP 401.
- Browser tooling reported `No browser is available`. No authenticated session
  could be used. No TEST service or application database records were created
  or modified; actual scheduled checks and ACTIVE -> RESOLVED transitions remain
  unverified. The application test suites were not rerun: application code was
  unchanged. `git diff --check` passed.

Ignored evidence: `.runtime/localhost-run-verification.json`,
`.runtime/localhost-run-processes.json`, `.runtime/localhost-run-tunnel-error.log`
and `.runtime/probe-public.jsh`. Ephemeral launch/probe scripts in `.runtime/`
are local execution aids, not new application dependencies.

### Minimum remaining browser actions

1. Open `http://localhost:5173`, sign in, select an organisation where you may
   register services, and open `/services/new`. Create one dedicated service:
   name `Alert E2E localhost.run 2026-10-27`, owner `Local E2E test`, service type
   HTTP, environment TEST, base URL `https://d991feded1488c.lhr.life`, health
   endpoint `/health`. Leave monitoring enabled. Do not edit an existing service.
2. In that service's details, verify alerting is enabled with failure threshold
   3, recovery threshold 2 and severity HIGH. Wait for a persisted UP result.
3. From the repository root, run:

   ```powershell
   .\tools\alert-e2e-demo\Set-Health.ps1 -State DOWN
   ```

   Wait for three distinct scheduled DOWN observations (default interval 60s;
   actual environment settings can differ). Refresh the service's alert history
   and verify exactly one ACTIVE alert. Record its ID. Keep DOWN for another
   scheduled result and verify that the same alert updates without duplication.
4. Run:

   ```powershell
   .\tools\alert-e2e-demo\Set-Health.ps1 -State UP
   ```

   After two distinct scheduled UP observations, verify the same alert ID is
   RESOLVED with a resolution timestamp and remains in history. Manual HTTP
   requests alone do not advance the evaluator: scheduled persisted observations
   are required. Preserve the dedicated fixture's health/alert JSON as evidence,
   without access tokens.
5. Disable only this dedicated TEST service before stopping the tunnel.

### Processes intentionally left running

At handoff: demo Node PID **43372**, SSH tunnel PID **43424**, frontend Vite PID
**27476**. The demo is UP. Old diagnostic tunnels were stopped. These processes
were left running for manual testing as requested; no existing backend process
was restarted or stopped.

After disabling the dedicated TEST service, inspect these PIDs before stopping
them (Windows can reuse PIDs after a process exits):

**Mandatory safety gate:** for the validated service `opspilot-alert-e2e`, first
verify its TEST environment and confirm `enabled: false` with an authenticated
service GET after disabling it. If authentication or that confirmation is
unavailable, stop cleanup and leave the demo, SSH tunnel and temporary frontend
running. Never bypass authentication or use SQL to disable it. See
[Final Cleanup Verification](../../docs/alerting-e2e-validation.md#final-cleanup-verification)
for the latest recorded attempt. The command below is not a substitute for that
API confirmation. Once confirmed, attempt graceful shutdown before forceful
termination, and recheck demo-owned descendants and infrastructure afterwards.

```powershell
Get-CimInstance Win32_Process -Filter 'ProcessId = 43372 OR ProcessId = 43424 OR ProcessId = 27476' |
  Select-Object ProcessId, Name, CommandLine
# Only if they still identify the demo, nokey@localhost.run tunnel and Vite:
Stop-Process -Id 43424,43372,27476
```

For a later manual reconnect, use the existing pinned host file and start the
unchanged demo separately:

```powershell
ssh.exe -F NUL -T `
  -o StrictHostKeyChecking=yes `
  -o UserKnownHostsFile=C:/projects/OpsPilot/tools/alert-e2e-demo/.runtime/localhost-run-known_hosts `
  -o IdentityFile=none -o IdentityAgent=none -o IdentitiesOnly=yes `
  -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=15 `
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 `
  -R 80:127.0.0.1:8765 nokey@localhost.run
```

Use the newly assigned HTTPS URL and verify its public behavior before registering
another fixture. A changed host key must be investigated, not bypassed. No DNS,
OpsPilot SSRF policy, credentials, production services, alerts or Git history
were changed during this continuation. Nothing was committed or pushed.
