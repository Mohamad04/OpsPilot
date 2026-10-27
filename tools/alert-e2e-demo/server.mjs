import http from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const stateFile = fileURLToPath(new URL('./.runtime/health-state.txt', import.meta.url));

function readState() {
  let state;
  try {
    state = readFileSync(stateFile, 'utf8').trim();
  } catch (error) {
    if (error.code === 'ENOENT') return 'UP';
    throw error;
  }
  if (state !== 'UP' && state !== 'DOWN') throw new Error('Invalid demo state');
  return state;
}

const server = http.createServer((request, response) => {
  response.setHeader('Content-Type', 'application/json; charset=utf-8');
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('X-Content-Type-Options', 'nosniff');

  // This is not a file server or proxy. No API can change the local state file.
  if (request.url.split('?')[0] !== '/health') {
    response.writeHead(404).end(JSON.stringify({ error: 'Not found' }));
    return;
  }
  if (request.method !== 'GET') {
    response.setHeader('Allow', 'GET');
    response.writeHead(405).end(JSON.stringify({ error: 'Method not allowed' }));
    return;
  }
  try {
    const status = readState();
    const code = status === 'DOWN' ? 503 : 200;
    response.writeHead(code).end(JSON.stringify({ status }));
    console.log(`${new Date().toISOString()} health=${status} http=${code}`);
  } catch {
    // A broken fixture is not a successful UP observation. Do not expose filesystem details.
    response.writeHead(500).end(JSON.stringify({ status: 'UNKNOWN' }));
    console.error('Unable to read a valid demo state. Use Set-Health.ps1 to reset it.');
  }
});

server.headersTimeout = 5_000;
server.requestTimeout = 10_000;
server.keepAliveTimeout = 5_000;
server.maxHeadersCount = 32;
server.on('error', (error) => {
  console.error(`Demo server could not start: ${error.code ?? 'unknown error'}`);
  process.exitCode = 1;
});
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close();
    server.closeAllConnections();
  });
}
server.listen(8765, '127.0.0.1', () => {
  console.log('Alert E2E demo listening at http://127.0.0.1:8765/health');
});
