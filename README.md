# AdminPanel — Minecraft Server Admin Dashboard

Three pieces, matching the architecture:

```
frontend/   -> index.html, self-contained dashboard (host anywhere: static hosting, or just open the file)
backend/    -> Node.js bridge/API server (the only public-facing piece)
plugin/     -> Paper plugin (Java), connects OUT to backend, no inbound ports needed
```

## 1. Build & install the plugin

```bash
cd plugin
mvn clean package
# -> target/AdminPanel.jar
```

Upload `AdminPanel.jar` to your host's `plugins/` folder (works with Minestator-style
hosts — it's a normal Paper plugin, no special server access needed beyond the
plugins folder). Start the server once so it generates `plugins/AdminPanel/config.yml`,
then edit it:

```yaml
backend-url: "wss://your-backend-domain.example.com/plugin-ws"
auth-token: "<same long random string as PLUGIN_TOKEN in backend/.env>"
server-id: "main-survival"
```

**Important — check your exact Paper API version.** `plugin/pom.xml` is pinned to
`1.21.4-R0.1-SNAPSHOT`. If your host runs 1.21.11 specifically, check
https://repo.papermc.io/service/rest/repository/browse/maven-public/io/papermc/paper/paper-api/
for the closest matching artifact and update the version in `pom.xml` — the API
surface used here (ItemMeta, PlayerInventory, GameMode, etc.) hasn't changed
across 1.21.x, so a nearby version will compile and run fine against your server.

## 2. Run the backend

```bash
cd backend
npm install
cp .env.example .env
```

Fill in `.env`:
- `PLUGIN_TOKEN` — long random string, must match the plugin's `auth-token` exactly
- `JWT_SECRET` — `openssl rand -hex 32`
- `PANEL_PASSWORD_HASH` — one shared password for the dashboard (no username).
  Generate the hash with:
  `node -e "console.log(require('bcryptjs').hashSync('your-password', 10))"`
- `ALLOWED_ORIGINS` — wherever you end up hosting `frontend/index.html`

```bash
npm start
```

This backend needs to be reachable from your Minecraft host over the internet
(that's the "hosted separately" requirement). Put it behind a reverse proxy
(Caddy or nginx) for free TLS so you can use `wss://` — plain `ws://` is fine for
local testing only. Caddy example:

```
your-backend-domain.example.com {
    reverse_proxy localhost:8080
}
```

## 3. Open the dashboard

`frontend/index.html` is a single self-contained file — open it directly, or
host it on any static host (Netlify, GitHub Pages, nginx, whatever). On first
load it asks for your backend URL + admin login, then remembers them.

---

## What's real vs. what's a known limitation

Per your requirement #9 — everything below actually talks to the live server.
Nothing here fakes a button. The exceptions, named honestly:

- **Item icons** load from a public GitHub-hosted texture mirror
  (`misode/mcmeta`, pinned to the `1.21` asset branch). It's a third-party
  dependency — if it's ever down or missing an item, the slot falls back to a
  plain gray square (still fully functional, just no icon). For guaranteed
  uptime, download that repo's item textures once and serve them from your own
  backend instead — I can wire that up if you want it.
- **Item catalog is curated (195 items)**, not the full ~1400-item registry —
  covers all the common categories (tools, combat, food, redstone, potions,
  brewing, blocks, spawn eggs). Extending `backend/items.json` to the full
  registry is mechanical; say the word and I'll generate it.
- **Moving an item between two occupied slots** isn't true drag-and-drop yet —
  you click a slot to open the item browser/editor and place an item there;
  to *move* an existing item you currently remove it from one slot (shift-click)
  and re-pick it into another. True slot-to-slot dragging is a real upgrade I
  can add next — it just needs a bit more client-side drag state.
- **Ban/unban** uses Paper's name-based ban list (not IP bans). That's
  deliberate — IP bans are a bigger footgun to expose over a web panel.
- **Multi-server support**: the backend is wired for it (`serverId` is already
  in the protocol), but `DEFAULT_SERVER_ID` in `server.js` currently hardcodes
  routing to one server. Fine for your single Paper server now; trivial to
  extend to a server-picker later.
- **State is in-memory** on the backend (player cache, pending requests). If
  the backend restarts, it repopulates from the plugin's heartbeat within ~5
  seconds — no persistent database is wired up yet (not needed for this scope,
  but worth knowing if you want historical logs of admin actions later).

## Priority delivered, as requested

The live inventory editor + Creative-style item browser were built first and
are the deepest part of the system: real Minecraft slot layout (hotbar, main,
armor, offhand, ender chest), full item data round-trip (name, lore,
enchantments, custom model data, durability/damage, potion type), and every
change goes over the WebSocket bridge to the actual `ItemStack` in the actual
player's actual inventory — confirmed via the `set_slot` / `give_item` command
path in `CommandDispatcher.java`.
