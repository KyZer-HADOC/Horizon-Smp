require('dotenv').config();
const express = require('express');
const cors = require('cors');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const http = require('http');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const path = require('path');

const PORT = process.env.PORT || 8080;
const PLUGIN_TOKEN = process.env.PLUGIN_TOKEN;
const JWT_SECRET = process.env.JWT_SECRET;
const PANEL_PASSWORD_HASH = process.env.PANEL_PASSWORD_HASH;
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);

if (!PLUGIN_TOKEN || !JWT_SECRET || !PANEL_PASSWORD_HASH) {
  console.error('Missing required env vars. Copy .env.example to .env and fill it in.');
  process.exit(1);
}

const app = express();
app.use(express.json());
app.use(cors({ origin: ALLOWED_ORIGINS.length ? ALLOWED_ORIGINS : true }));

// ---- In-memory state -------------------------------------------------
// This is a single-process bridge. For production scale you'd back this
// with Redis, but for a single Paper server + a handful of admins this is fine.

const pluginConnections = new Map(); // serverId -> ws
const dashboardClients = new Set();  // ws set
const playerCache = new Map();       // uuid -> last known detail/summary JSON
const pendingRequests = new Map();   // requestId -> { resolve, reject, timer }

function broadcastToDashboards(payload) {
  const msg = JSON.stringify(payload);
  for (const ws of dashboardClients) {
    if (ws.readyState === ws.OPEN) ws.send(msg);
  }
}

function sendCommandToPlugin(serverId, message, timeoutMs = 8000) {
  const ws = pluginConnections.get(serverId);
  if (!ws || ws.readyState !== ws.OPEN) {
    return Promise.reject(new Error('Plugin is not connected for server: ' + serverId));
  }
  const requestId = crypto.randomUUID();
  message.requestId = requestId;

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      reject(new Error('Plugin did not respond in time'));
    }, timeoutMs);
    pendingRequests.set(requestId, { resolve, reject, timer });
    ws.send(JSON.stringify(message));
  });
}

// ---- Auth --------------------------------------------------------------

// Simple brute-force slowdown: a short delay + basic attempt counter per IP.
const loginAttempts = new Map(); // ip -> { count, resetAt }

app.post('/api/login', async (req, res) => {
  const ip = req.ip;
  const now = Date.now();
  const entry = loginAttempts.get(ip);
  if (entry && entry.count >= 8 && now < entry.resetAt) {
    return res.status(429).json({ error: 'Too many attempts. Try again in a minute.' });
  }

  const { password } = req.body || {};
  const valid = await bcrypt.compare(password || '', PANEL_PASSWORD_HASH);
  if (!valid) {
    loginAttempts.set(ip, {
      count: (entry?.count || 0) + 1,
      resetAt: entry && now < entry.resetAt ? entry.resetAt : now + 60_000,
    });
    return res.status(401).json({ error: 'Incorrect password' });
  }
  loginAttempts.delete(ip);
  const token = jwt.sign({ role: 'admin' }, JWT_SECRET, { expiresIn: '12h' });
  res.json({ token });
});

function requireAuth(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Missing token' });
  try {
    req.admin = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}

// Which Paper server we're driving. Extend this to a real registry if you run more than one.
const DEFAULT_SERVER_ID = 'main-survival';

// ---- Player data ---------------------------------------------------------

app.get('/api/players', requireAuth, (req, res) => {
  res.json({ players: Array.from(playerCache.values()) });
});

app.get('/api/players/:uuid', requireAuth, async (req, res) => {
  try {
    const result = await sendCommandToPlugin(DEFAULT_SERVER_ID, {
      type: 'get_player_detail',
      player: req.params.uuid,
    });
    res.json(result);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

// ---- Inventory actions ---------------------------------------------------

app.post('/api/players/:uuid/give-item', requireAuth, async (req, res) => {
  await forward(res, { type: 'give_item', player: req.params.uuid, item: req.body.item, slot: req.body.slot });
});

app.post('/api/players/:uuid/set-slot', requireAuth, async (req, res) => {
  await forward(res, {
    type: 'set_slot',
    player: req.params.uuid,
    slot: req.body.slot,
    inventoryType: req.body.inventoryType || 'main',
    item: req.body.item,
  });
});

app.post('/api/players/:uuid/remove-slot', requireAuth, async (req, res) => {
  await forward(res, {
    type: 'remove_slot',
    player: req.params.uuid,
    slot: req.body.slot,
    inventoryType: req.body.inventoryType || 'main',
  });
});

app.post('/api/players/:uuid/clear-inventory', requireAuth, async (req, res) => {
  await forward(res, { type: 'clear_inventory', player: req.params.uuid });
});

// ---- Player admin actions -------------------------------------------------

app.post('/api/players/:uuid/kick', requireAuth, async (req, res) => {
  await forward(res, { type: 'kick', player: req.params.uuid, reason: req.body.reason });
});

app.post('/api/players/:uuid/ban', requireAuth, async (req, res) => {
  await forward(res, { type: 'ban', player: req.params.uuid, reason: req.body.reason });
});

app.post('/api/players/:uuid/unban', requireAuth, async (req, res) => {
  await forward(res, { type: 'unban', player: req.params.uuid });
});

app.post('/api/players/:uuid/teleport', requireAuth, async (req, res) => {
  await forward(res, {
    type: 'teleport',
    player: req.params.uuid,
    x: req.body.x, y: req.body.y, z: req.body.z,
    targetPlayer: req.body.targetPlayer,
  });
});

app.post('/api/players/:uuid/gamemode', requireAuth, async (req, res) => {
  await forward(res, { type: 'set_gamemode', player: req.params.uuid, gamemode: req.body.gamemode });
});

app.post('/api/players/:uuid/op', requireAuth, async (req, res) => {
  await forward(res, { type: 'set_op', player: req.params.uuid, op: !!req.body.op });
});

app.post('/api/players/:uuid/freeze', requireAuth, async (req, res) => {
  await forward(res, { type: req.body.frozen ? 'freeze' : 'unfreeze', player: req.params.uuid });
});

app.post('/api/console/execute', requireAuth, async (req, res) => {
  await forward(res, { type: 'execute_command', command: req.body.command });
});

async function forward(res, message) {
  try {
    const result = await sendCommandToPlugin(DEFAULT_SERVER_ID, message);
    res.json(result);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
}

// ---- Item catalog for the Creative-style browser --------------------------
app.get('/api/items', requireAuth, (req, res) => {
  res.sendFile(path.join(__dirname, 'items.json'));
});

app.get('/api/status', requireAuth, (req, res) => {
  res.json({
    pluginConnected: pluginConnections.has(DEFAULT_SERVER_ID),
    onlinePlayers: playerCache.size,
  });
});

// ---- WebSocket wiring ------------------------------------------------------

const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/plugin-ws') {
    wss.handleUpgrade(req, socket, head, (ws) => handlePluginSocket(ws));
  } else if (url.pathname === '/dashboard-ws') {
    const token = url.searchParams.get('token');
    try {
      jwt.verify(token, JWT_SECRET);
    } catch {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => handleDashboardSocket(ws));
  } else {
    socket.destroy();
  }
});

function handlePluginSocket(ws) {
  let authenticated = false;
  let serverId = null;

  const authTimeout = setTimeout(() => {
    if (!authenticated) ws.close(4001, 'Auth timeout');
  }, 10000);

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (!authenticated) {
      if (msg.type === 'auth' && msg.token === PLUGIN_TOKEN) {
        authenticated = true;
        serverId = msg.serverId || 'default';
        pluginConnections.set(serverId, ws);
        clearTimeout(authTimeout);
        console.log(`Plugin authenticated for server: ${serverId}`);
        broadcastToDashboards({ type: 'server_status', serverId, connected: true });
      } else {
        ws.close(4003, 'Bad auth token');
      }
      return;
    }

    // Resolve any pending REST->plugin request waiting on this requestId
    if (msg.requestId && pendingRequests.has(msg.requestId)) {
      const { resolve, timer } = pendingRequests.get(msg.requestId);
      clearTimeout(timer);
      pendingRequests.delete(msg.requestId);
      resolve(msg);
    }

    // Update cache + fan out live state to connected dashboards
    if (msg.type === 'heartbeat' && Array.isArray(msg.players)) {
      for (const p of msg.players) playerCache.set(p.uuid, { ...playerCache.get(p.uuid), ...p });
      broadcastToDashboards({ type: 'player_list', players: Array.from(playerCache.values()) });
    } else if (msg.type === 'player_update' && msg.player) {
      playerCache.set(msg.player.uuid, msg.player);
      broadcastToDashboards({ type: 'player_update', player: msg.player });
    } else if (msg.type === 'player_left' && msg.uuid) {
      playerCache.delete(msg.uuid);
      broadcastToDashboards({ type: 'player_left', uuid: msg.uuid });
    }
  });

  ws.on('close', () => {
    if (serverId) {
      pluginConnections.delete(serverId);
      broadcastToDashboards({ type: 'server_status', serverId, connected: false });
      console.log(`Plugin disconnected: ${serverId}`);
    }
  });
}

function handleDashboardSocket(ws) {
  dashboardClients.add(ws);
  ws.send(JSON.stringify({ type: 'player_list', players: Array.from(playerCache.values()) }));
  ws.on('close', () => dashboardClients.delete(ws));
}

server.listen(PORT, () => {
  console.log(`AdminPanel backend listening on :${PORT}`);
});
