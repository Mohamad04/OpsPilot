import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const directory = fileURLToPath(new URL('./', import.meta.url));
const runtime = fileURLToPath(new URL('./.runtime/', import.meta.url));
const stateFile = `${runtime}health-state.txt`;
const children = [];
const evidence = { startedAt: new Date().toISOString(), publicUrl: null, checks: [], cleanup: [] };
const cancelled = new AbortController();
process.on('SIGINT', () => cancelled.abort());
process.on('SIGTERM', () => cancelled.abort());
mkdirSync(runtime, { recursive: true });
const originalState = existsSync(stateFile) ? readFileSync(stateFile) : null;
const log = createWriteStream(`${runtime}verification.log`);

async function occupied() {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: '127.0.0.1', port: 8765 });
    socket.setTimeout(1500);
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', (error) => { socket.destroy(); error.code === 'ECONNREFUSED' ? resolve(false) : reject(error); });
    socket.once('timeout', () => { socket.destroy(); reject(new Error('Cannot verify that port 8765 is free')); });
  });
}
function start(command, args) {
  const child = spawn(command, args, { cwd: directory, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  children.push(child);
  child.on('error', (error) => { child.startFailure = error; });
  for (const stream of [child.stdout, child.stderr]) stream.on('data', (chunk) => {
    child.output = `${child.output ?? ''}${chunk.toString()}`.slice(-65536); log.write(chunk);
  });
  return child;
}
async function waitFor(test, message, attempts = 45) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    cancelled.signal.throwIfAborted();
    for (const child of children) {
      if (child.startFailure) throw child.startFailure;
      if (child.exitCode !== null) throw new Error(`Temporary process exited (${child.exitCode}); see .runtime/verification.log`);
    }
    const value = await test();
    if (value) return value;
    await delay(1000);
  }
  throw new Error(message);
}
async function check(url, expectedCode, expectedStatus) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.any([cancelled.signal, AbortSignal.timeout(8000)]) });
  let body;
  try { body = await response.json(); } catch { throw new Error(`HTTP ${response.status}: response was not health JSON`); }
  if (response.status !== expectedCode || body.status !== expectedStatus) throw new Error(`Unexpected demo response: HTTP ${response.status}`);
  const record = { at: new Date().toISOString(), url, statusCode: response.status, body };
  evidence.checks.push(record); console.log(JSON.stringify(record)); return true;
}
function setState(state) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-File', `${directory}Set-Health.ps1`, '-State', state],
    { cwd: directory, windowsHide: true, encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Set-Health.ps1 failed: ${result.stderr}`);
  console.log(result.stdout.trim());
}

try {
  if (await occupied()) throw new Error('Port 8765 is already occupied; existing processes will not be modified');
  const server = start(process.execPath, [`${directory}server.mjs`]);
  await waitFor(() => server.output?.includes('listening at http://127.0.0.1:8765/health'), 'Demo server did not start', 10);
  if (originalState === null) await check('http://127.0.0.1:8765/health', 200, 'UP');
  setState('UP');
  await check('http://127.0.0.1:8765/health', 200, 'UP');
  setState('DOWN');
  await check('http://127.0.0.1:8765/health', 503, 'DOWN');
  setState('UP');
  await check('http://127.0.0.1:8765/health', 200, 'UP');
  for (const [path, method, expected] of [['/', 'GET', 404], ['/.runtime/health-state.txt', 'GET', 404], ['/health', 'POST', 405]]) {
    const response = await fetch(`http://127.0.0.1:8765${path}`, { method, signal: AbortSignal.timeout(3000) });
    if (response.status !== expected) throw new Error('Unexpected route or mutation exposed');
    evidence.checks.push({ at: new Date().toISOString(), localPath: path, method, statusCode: response.status });
  }
  writeFileSync(`${runtime}tunnel-config.yml`, '{}\n');
  const localCloudflared = `${runtime}cloudflared.exe`;
  const tunnel = start(existsSync(localCloudflared) ? localCloudflared : 'cloudflared', [
    'tunnel', '--config', `${runtime}tunnel-config.yml`, '--no-autoupdate', '--url', 'http://127.0.0.1:8765', '--metrics', '127.0.0.1:0',
  ]);
  const publicUrl = await waitFor(() => tunnel.output?.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/)?.[0], 'Quick Tunnel URL was not allocated');
  evidence.publicUrl = publicUrl; console.log(`Temporary demo URL: ${publicUrl}`);
  evidence.publicReadinessErrors = [];
  await waitFor(async () => {
    try { return await check(`${publicUrl}/health`, 200, 'UP'); }
    catch (error) {
      const cause = error.cause?.code ?? error.message;
      evidence.publicReadinessErrors.push({ at: new Date().toISOString(), cause });
      if (evidence.publicReadinessErrors.length === 1) console.error(`Waiting for public HTTPS readiness: ${cause}`);
      return false;
    }
  }, 'Public HTTPS endpoint never became ready; see recorded DNS/network errors', 90);
  setState('DOWN');
  await check('http://127.0.0.1:8765/health', 503, 'DOWN');
  await check(`${publicUrl}/health`, 503, 'DOWN');
  setState('UP');
  await check('http://127.0.0.1:8765/health', 200, 'UP');
  await check(`${publicUrl}/health`, 200, 'UP');
  evidence.httpVerification = 'passed';
} catch (error) {
  evidence.httpVerification = 'failed'; evidence.error = error.message;
  console.error(error.message); process.exitCode = 1;
} finally {
  for (const child of children.reverse()) {
    if (child.pid && child.exitCode === null && !child.signalCode) {
      child.kill();
      for (let attempt = 0; attempt < 30 && child.exitCode === null && !child.signalCode; attempt++) await delay(100);
    }
    evidence.cleanup.push({ pid: child.pid ?? null, stopped: child.exitCode !== null || child.signalCode !== null || Boolean(child.startFailure) });
  }
  // Restore only this demo's state; do not touch application services or other processes.
  if (originalState === null) { if (existsSync(stateFile)) unlinkSync(stateFile); }
  else writeFileSync(stateFile, originalState);
  evidence.finishedAt = new Date().toISOString();
  writeFileSync(`${runtime}verification-result.json`, `${JSON.stringify(evidence, null, 2)}\n`);
  log.end();
  console.log(JSON.stringify({ result: evidence.httpVerification, cleanup: evidence.cleanup }));
  if (evidence.cleanup.some((child) => !child.stopped)) process.exitCode = 1;
}
