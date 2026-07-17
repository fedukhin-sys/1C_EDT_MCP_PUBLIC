# Smoke harness — минимальный MCP-клиент для проверок

Когда нужно прогнать серию tool-вызовов без Claude-клиента: подключение по SSE,
`initialize` → `notifications/initialized` → серия `tools/call`.

## Endpoints

- SSE: `http://127.0.0.1:<port>/mcp/sse` (порт по умолчанию `3001`)
- messages: `http://127.0.0.1:<port>/mcp/messages?sessionId=…`

`sessionId` возвращает SSE-handshake в первом `event: endpoint`.
Авторизация — `Authorization: Bearer <token>` в **каждом** запросе, включая SSE.
Токен — `Window → Preferences → EDT MCP` (прод-сервер держит его в Equinox secure prefs,
забирать только через preference page).

## Клиент

Сохрани локально, например как `mcp-smoke.js`.

```js
'use strict';
// usage: node mcp-smoke.js <token> <steps.json>
const http = require('http');
const TOKEN = process.argv[2];
const STEPS = require(process.argv[3]);
const BASE = 'http://127.0.0.1:3001';
let endpoint = null, nextId = 1, buffer = '';
const pending = new Map();

const sse = http.get(BASE + '/mcp/sse', {
  headers: { 'Authorization': 'Bearer ' + TOKEN, 'Accept': 'text/event-stream' }
}, (res) => {
  if (res.statusCode !== 200) { console.error('HTTP ' + res.statusCode); process.exit(1); }
  res.setEncoding('utf8');
  res.on('data', (chunk) => {
    buffer += chunk.replace(/\r\n/g, '\n');
    let idx;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx); buffer = buffer.slice(idx + 2);
      let event = 'message', data = '';
      for (const ln of raw.split('\n')) {
        if (ln.startsWith('event:')) event = ln.slice(6).trim();
        else if (ln.startsWith('data:')) data += ln.slice(5).trim();
      }
      if (event === 'endpoint') { endpoint = data.startsWith('http') ? data : BASE + data; run(); }
      else if (event === 'message' && data) {
        try { const m = JSON.parse(data); if (pending.has(m.id)) { pending.get(m.id).resolve(m); pending.delete(m.id); } } catch(e){}
      }
    }
  });
});

function post(p) {
  const body = JSON.stringify(p), u = new URL(endpoint);
  return new Promise((res, rej) => {
    const r = http.request({ hostname: u.hostname, port: u.port, path: u.pathname + u.search, method: 'POST',
      headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, (rs) => { rs.resume(); rs.on('end', res); });
    r.on('error', rej); r.write(body); r.end();
  });
}

async function send(method, params) {
  const id = nextId++;
  const p = new Promise((res, rej) => {
    pending.set(id, { resolve: res, reject: rej });
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout: ' + method)); } }, 600000);
  });
  await post({ jsonrpc: '2.0', id, method, params });
  return p;
}

async function run() {
  await send('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'smoke', version: '1.0' } });
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' });
  for (const step of STEPS) {
    console.log('## ' + (step.label || step.tool));
    const res = await send('tools/call', { name: step.tool, arguments: step.args || {} });
    const ts = (res.result && res.result.content || []).map(c => c.text || JSON.stringify(c)).join('\n');
    console.log('isError=' + !!(res.result && res.result.isError) + '\n' + ts);
  }
  process.exit(0);
}
```

## Использование

```powershell
$tok = (Get-Content "C:\path\to\MCP_token.txt" -Raw).Trim()
node "C:\path\to\mcp-smoke.js" $tok "C:\path\to\steps.json"
```

`steps.json` — массив `{ label, tool, args }`.

## Грабли

- **PowerShell ест внутренние кавычки в JSON-литералах** — **всегда** используй внешний
  `.json` файл, не передавай JSON через CLI.
- **node v24 + `127.0.0.1`**: нужен `family: 4` в http options, иначе `ETIMEDOUT`
  (резолвер уходит в IPv6). Если харнес таймаутит на живом сервере — это первое, что проверить.
- **Сервер может отвалиться**: порт перестаёт слушаться при работающем EDT. Лечится
  перезапуском EDT / MCP-сервера. После рестарта первые секунды соединение таймаутит —
  опрашивай с паузой.
- **Проверить, что сервер жив**: `Get-NetTCPConnection -State Listen -LocalPort 3001`.
- **Ротация токена** (`Preferences → Regenerate token`) немедленно инвалидирует старый —
  обнови файл с токеном.
