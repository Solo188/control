#!/usr/bin/env python3
"""
RemoteControl Server — запускать в Termux:
    python server.py

Затем открыть туннель:
    bore local 8080 --to bore.pub

Открыть в браузере: http://bore.pub:ТВОЙ_ПОРТ/
"""

import threading
import time
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

# ────────────────────────────────────────────────────────────
#  Общее состояние (thread-safe через Lock)
# ────────────────────────────────────────────────────────────

lock            = threading.Lock()
latest_screen   = None          # bytes — последний скриншот JPEG
pending_command = None          # dict — команда ожидающая выполнения телефоном
command_ready   = threading.Event()  # сигнал что команда поставлена

# ────────────────────────────────────────────────────────────
#  HTML интерфейс (встроен прямо в сервер)
# ────────────────────────────────────────────────────────────

HTML = r"""<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>RemoteControl</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700&display=swap');
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  :root {
    --bg: #0a0c10; --surface: #12151c; --border: #1e2433;
    --accent: #00e5ff; --red: #ff4757; --green: #2ed573;
    --text: #e2e8f0; --muted: #4a5568;
  }

  body {
    background: var(--bg); color: var(--text);
    font-family: 'JetBrains Mono', monospace;
    display: flex; flex-direction: column;
    height: 100dvh; overflow: hidden;
  }

  /* ── Header ── */
  header {
    display: flex; align-items: center; gap: 12px;
    padding: 10px 14px; border-bottom: 1px solid var(--border);
    background: var(--surface); flex-shrink: 0;
  }
  header h1 { font-size: 14px; color: var(--accent); letter-spacing: 2px; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--muted); margin-left: auto; }
  .dot.live { background: var(--green); box-shadow: 0 0 8px var(--green); animation: blink 1.5s infinite; }
  @keyframes blink { 50% { opacity: 0.3; } }
  #fps { font-size: 11px; color: var(--muted); }

  /* ── Main layout ── */
  main {
    display: flex; flex: 1; overflow: hidden; gap: 0;
  }

  /* ── Screen area ── */
  #screen-wrap {
    flex: 1; position: relative; overflow: hidden;
    background: #050507; display: flex; align-items: center; justify-content: center;
    cursor: crosshair;
  }
  #screen {
    max-width: 100%; max-height: 100%;
    object-fit: contain; display: block;
    pointer-events: none; /* события перехватывает overlay */
    user-select: none;
  }
  #overlay {
    position: absolute; inset: 0;
    touch-action: none;
  }

  /* Анимация нажатия */
  .ripple {
    position: absolute; border-radius: 50%;
    width: 40px; height: 40px; margin: -20px 0 0 -20px;
    background: rgba(0,229,255,0.4);
    animation: ripple-out 0.5s ease-out forwards;
    pointer-events: none;
  }
  @keyframes ripple-out {
    to { transform: scale(3); opacity: 0; }
  }
  .swipe-line {
    position: absolute; pointer-events: none;
    background: rgba(0,229,255,0.6);
    height: 3px; transform-origin: left center;
    border-radius: 2px;
  }

  /* ── Sidebar ── */
  aside {
    width: 160px; flex-shrink: 0;
    background: var(--surface); border-left: 1px solid var(--border);
    display: flex; flex-direction: column; gap: 6px; padding: 10px 8px;
    overflow-y: auto;
  }

  .section-title {
    font-size: 9px; color: var(--muted); letter-spacing: 2px;
    text-transform: uppercase; padding: 4px 0 2px; border-top: 1px solid var(--border);
    margin-top: 4px;
  }
  .section-title:first-child { border-top: none; margin-top: 0; }

  /* Mode buttons */
  .mode-btn {
    padding: 9px 6px; border: 1px solid var(--border);
    background: var(--bg); color: var(--muted);
    border-radius: 8px; font-family: inherit; font-size: 12px;
    cursor: pointer; text-align: center; transition: all 0.15s;
    -webkit-tap-highlight-color: transparent;
  }
  .mode-btn.active {
    border-color: var(--accent); color: var(--accent);
    background: rgba(0,229,255,0.07);
  }

  /* Action buttons */
  .action-btn {
    padding: 9px 6px; border: 1px solid var(--border);
    background: var(--bg); color: var(--text);
    border-radius: 8px; font-family: inherit; font-size: 12px;
    cursor: pointer; text-align: center; transition: all 0.15s;
    -webkit-tap-highlight-color: transparent;
  }
  .action-btn:active { transform: scale(0.96); }

  /* Log */
  #log {
    flex: 1; overflow-y: auto; font-size: 10px; color: var(--muted);
    line-height: 1.8; word-break: break-all;
  }
  .log-ok   { color: var(--green); }
  .log-err  { color: var(--red); }
  .log-info { color: var(--accent); }

  /* ── Status bar ── */
  footer {
    padding: 4px 10px; background: var(--surface);
    border-top: 1px solid var(--border); flex-shrink: 0;
    font-size: 10px; color: var(--muted);
    display: flex; gap: 16px;
  }
  #coords { color: var(--accent); }
</style>
</head>
<body>

<header>
  <h1>⚡ REMOTE CONTROL</h1>
  <span id="fps">--</span>
  <div class="dot" id="statusDot"></div>
</header>

<main>
  <div id="screen-wrap">
    <img id="screen" src="" alt="Ожидание скриншота...">
    <div id="overlay"></div>
  </div>

  <aside>
    <div class="section-title">Режим</div>
    <button class="mode-btn active" onclick="setMode('tap')"      id="m-tap">👆 Tap</button>
    <button class="mode-btn"        onclick="setMode('swipe')"    id="m-swipe">👉 Swipe</button>
    <button class="mode-btn"        onclick="setMode('longpress')" id="m-longpress">✋ Hold</button>

    <div class="section-title">Система</div>
    <button class="action-btn" onclick="sendCmd({a:'home'})">🏠 Home</button>
    <button class="action-btn" onclick="sendCmd({a:'back'})">◀ Back</button>
    <button class="action-btn" onclick="sendCmd({a:'recents'})">📋 Recent</button>
    <button class="action-btn" onclick="sendCmd({a:'notifications'})">🔔 Shade</button>

    <div class="section-title">Экран</div>
    <button class="action-btn" onclick="requestScreenshot()">📸 Shot</button>
    <button class="action-btn" onclick="toggleAuto()" id="autoBtn">▶ Auto</button>

    <div class="section-title">Лог</div>
    <div id="log"></div>
  </aside>
</main>

<footer>
  <span id="coords">x: — y: —</span>
  <span id="mode-label">Mode: tap</span>
  <span id="delay-label">delay: --ms</span>
</footer>

<script>
// ── State ──────────────────────────────────────────────────
const SERVER = ''; // пустая строка = текущий хост (сервер сам отдаёт HTML)
let mode       = 'tap';
let swipeStart = null;
let autoUpdate = false;
let autoTimer  = null;
let lastTs     = 0;

// ── Screen polling ─────────────────────────────────────────
const img      = document.getElementById('screen');
const overlay  = document.getElementById('overlay');
const dot      = document.getElementById('statusDot');

function fetchScreen() {
  const t0 = Date.now();
  // Добавляем timestamp чтобы браузер не кэшировал
  fetch(SERVER + '/screen?t=' + t0)
    .then(r => {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    })
    .then(blob => {
      const url = URL.createObjectURL(blob);
      const old = img.src;
      img.src = url;
      if (old.startsWith('blob:')) URL.revokeObjectURL(old);

      const ms = Date.now() - t0;
      document.getElementById('fps').textContent = ms + 'ms';
      document.getElementById('delay-label').textContent = 'delay: ' + ms + 'ms';
      dot.className = 'dot live';
    })
    .catch(e => {
      dot.className = 'dot';
      log('❌ ' + e.message, 'log-err');
    });
}

function requestScreenshot() {
  sendCmd({a: 'screenshot'});
  // Подождём скриншота и обновим
  setTimeout(fetchScreen, 2000);
}

function toggleAuto() {
  autoUpdate = !autoUpdate;
  document.getElementById('autoBtn').textContent = autoUpdate ? '⏸ Auto' : '▶ Auto';
  if (autoUpdate) {
    autoLoop();
  } else {
    clearTimeout(autoTimer);
  }
}

function autoLoop() {
  if (!autoUpdate) return;
  sendCmd({a: 'screenshot'});
  autoTimer = setTimeout(() => {
    fetchScreen();
    autoTimer = setTimeout(autoLoop, 1500);
  }, 1500);
}

// ── Mode ───────────────────────────────────────────────────
function setMode(m) {
  mode = m;
  document.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('m-' + m).classList.add('active');
  document.getElementById('mode-label').textContent = 'Mode: ' + m;
  swipeStart = null;
}

// ── Coordinate mapping ─────────────────────────────────────
function getRelCoords(e) {
  const rect = img.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return null;

  const clientX = e.touches ? e.touches[0].clientX : e.clientX;
  const clientY = e.touches ? e.touches[0].clientY : e.clientY;

  // Картинка может иметь letterbox (object-fit: contain)
  const imgNatW = img.naturalWidth  || rect.width;
  const imgNatH = img.naturalHeight || rect.height;
  const scale   = Math.min(rect.width / imgNatW, rect.height / imgNatH);
  const drawW   = imgNatW * scale;
  const drawH   = imgNatH * scale;
  const offX    = (rect.width  - drawW) / 2;
  const offY    = (rect.height - drawH) / 2;

  const relX = (clientX - rect.left - offX) / drawW;
  const relY = (clientY - rect.top  - offY) / drawH;

  // Выходим за пределы картинки — игнорируем
  if (relX < 0 || relX > 1 || relY < 0 || relY > 1) return null;
  return { x: relX, y: relY };
}

// ── Touch/Mouse events ─────────────────────────────────────
overlay.addEventListener('mousemove', e => {
  const pos = getRelCoords(e);
  if (pos) document.getElementById('coords').textContent =
    'x: ' + pos.x.toFixed(3) + '  y: ' + pos.y.toFixed(3);
});

overlay.addEventListener('mousedown',  onDown);
overlay.addEventListener('touchstart', e => { e.preventDefault(); onDown(e); }, {passive: false});
overlay.addEventListener('mouseup',    onUp);
overlay.addEventListener('touchend',   e => { e.preventDefault(); onUp(e); },   {passive: false});

function onDown(e) {
  const pos = getRelCoords(e);
  if (!pos) return;
  if (mode === 'swipe' || mode === 'longpress') {
    swipeStart = pos;
  }
}

function onUp(e) {
  const changedTouch = e.changedTouches ? e.changedTouches[0] : e;
  const fakeE = { clientX: changedTouch.clientX, clientY: changedTouch.clientY };
  const pos = getRelCoords(fakeE);
  if (!pos) return;

  if (mode === 'tap') {
    showRipple(fakeE.clientX, fakeE.clientY);
    sendCmd({ a: 'tap', x: r(pos.x), y: r(pos.y) });
    // Автообновление скриншота после команды
    if (!autoUpdate) setTimeout(fetchScreen, 800);

  } else if (mode === 'swipe' && swipeStart) {
    showSwipeLine(swipeStart, pos);
    sendCmd({ a: 'swipe', x1: r(swipeStart.x), y1: r(swipeStart.y),
              x2: r(pos.x), y2: r(pos.y), dur: 300 });
    swipeStart = null;
    if (!autoUpdate) setTimeout(fetchScreen, 800);

  } else if (mode === 'longpress') {
    showRipple(fakeE.clientX, fakeE.clientY);
    sendCmd({ a: 'longpress', x: r(pos.x), y: r(pos.y) });
    swipeStart = null;
    if (!autoUpdate) setTimeout(fetchScreen, 1000);
  }
}

// ── Visual feedback ────────────────────────────────────────
function showRipple(cx, cy) {
  const el = document.createElement('div');
  el.className = 'ripple';
  el.style.left = cx + 'px';
  el.style.top  = cy + 'px';
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 600);
}

function showSwipeLine(from, to) {
  const rect  = img.getBoundingClientRect();
  const imgNatW = img.naturalWidth  || rect.width;
  const imgNatH = img.naturalHeight || rect.height;
  const scale   = Math.min(rect.width / imgNatW, rect.height / imgNatH);
  const drawW   = imgNatW * scale;
  const drawH   = imgNatH * scale;
  const offX    = rect.left + (rect.width  - drawW) / 2;
  const offY    = rect.top  + (rect.height - drawH) / 2;

  const x1 = offX + from.x * drawW;
  const y1 = offY + from.y * drawH;
  const x2 = offX + to.x   * drawW;
  const y2 = offY + to.y   * drawH;

  const dx  = x2 - x1, dy = y2 - y1;
  const len = Math.sqrt(dx*dx + dy*dy);
  const ang = Math.atan2(dy, dx) * 180 / Math.PI;

  const el = document.createElement('div');
  el.className = 'swipe-line';
  el.style.cssText = `left:${x1}px;top:${y1}px;width:${len}px;transform:rotate(${ang}deg)`;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 600);
}

// ── Send command ───────────────────────────────────────────
function sendCmd(cmd) {
  log('→ ' + JSON.stringify(cmd), 'log-info');
  fetch(SERVER + '/command', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(cmd)
  })
  .then(r => r.text())
  .then(t => log('✓ ' + t, 'log-ok'))
  .catch(e => log('✗ ' + e.message, 'log-err'));
}

// ── Log ────────────────────────────────────────────────────
function log(msg, cls = '') {
  const box = document.getElementById('log');
  const el  = document.createElement('div');
  el.className = cls;
  el.textContent = msg;
  box.insertBefore(el, box.firstChild);
  // Держим не более 30 строк
  while (box.children.length > 30) box.removeChild(box.lastChild);
}

// ── Утилиты ────────────────────────────────────────────────
function r(n) { return Math.round(n * 1000) / 1000; }

// ── Инициализация ──────────────────────────────────────────
fetchScreen(); // Загрузить сразу при открытии
log('RemoteControl ready', 'log-ok');
</script>
</body>
</html>
"""

# ────────────────────────────────────────────────────────────
#  HTTP Request Handler
# ────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        # Подавляем стандартный лог чтобы не засорять консоль
        pass

    def do_GET(self):
        path = urlparse(self.path).path

        # ── Главная страница ──────────────────────────────
        if path == '/' or path == '/index.html':
            body = HTML.encode('utf-8')
            self._respond(200, 'text/html; charset=utf-8', body)

        # ── Последний скриншот ────────────────────────────
        elif path == '/screen':
            with lock:
                data = latest_screen
            if data:
                self._respond(200, 'image/jpeg', data)
            else:
                self._respond(404, 'text/plain', b'No screenshot yet')

        # ── Очередь команд (Android polling) ─────────────
        elif path == '/get_command':
            with lock:
                cmd = pending_command
            if cmd:
                import json
                body = json.dumps(cmd).encode()
                self._respond(200, 'application/json', body)
            else:
                self._respond(200, 'text/plain', b'wait')

        else:
            self._respond(404, 'text/plain', b'Not found')

    def do_POST(self):
        import json

        path    = urlparse(self.path).path
        length  = int(self.headers.get('Content-Length', 0))
        body    = self.rfile.read(length) if length else b''

        # ── Команда из браузера → очередь ────────────────
        if path == '/command':
            global pending_command
            try:
                cmd = json.loads(body)
                with lock:
                    pending_command = cmd
                print(f'[CMD]  {cmd}')
                self._respond(200, 'text/plain', b'ok')
            except Exception as e:
                self._respond(400, 'text/plain', str(e).encode())

        # ── Скриншот от Android ───────────────────────────
        elif path == '/upload':
            global latest_screen

            # Multipart parsing — ищем JPEG данные
            content_type = self.headers.get('Content-Type', '')
            if 'multipart/form-data' in content_type:
                data = self._extract_multipart_file(body, content_type)
            else:
                data = body  # Прямой POST с JPEG байтами

            if data:
                with lock:
                    latest_screen = data
                sz = len(data) // 1024
                print(f'[SHOT] {sz} KB')
                self._respond(200, 'text/plain', b'ok')
            else:
                self._respond(400, 'text/plain', b'no data')

        # ── Android забирает команду и сообщает об этом ──
        elif path == '/ack':
            global pending_command
            with lock:
                pending_command = None
            self._respond(200, 'text/plain', b'ok')

        else:
            self._respond(404, 'text/plain', b'Not found')

    # ── Helpers ───────────────────────────────────────────

    def _respond(self, code, ctype, body):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def _extract_multipart_file(self, body, content_type):
        """Простой парсер multipart/form-data — извлекает первый файл."""
        try:
            boundary = None
            for part in content_type.split(';'):
                part = part.strip()
                if part.startswith('boundary='):
                    boundary = part[9:].strip('"').encode()
                    break
            if not boundary:
                return body

            delimiter = b'--' + boundary
            parts = body.split(delimiter)
            for part in parts:
                if b'Content-Disposition' in part and b'filename' in part:
                    # Заголовки и тело разделены \r\n\r\n
                    sep = part.find(b'\r\n\r\n')
                    if sep != -1:
                        return part[sep + 4:].rstrip(b'\r\n--')
        except Exception as e:
            print(f'[WARN] multipart parse error: {e}')
        return body

# ────────────────────────────────────────────────────────────
#  Entry point
# ────────────────────────────────────────────────────────────

def run():
    port = int(os.environ.get('PORT', 8080))
    server = HTTPServer(('0.0.0.0', port), Handler)
    print(f'╔══════════════════════════════════╗')
    print(f'║  RemoteControl Server v1.0       ║')
    print(f'╠══════════════════════════════════╣')
    print(f'║  Local:  http://localhost:{port}   ║')
    print(f'║                                  ║')
    print(f'║  Tunnel: bore local {port} --to bore.pub ║')
    print(f'╚══════════════════════════════════╝')
    print()
    print('Ожидание подключений...')
    server.serve_forever()

if __name__ == '__main__':
    run()
