/**
 * Faznet upload API — Cloudflare Worker + KV.
 *
 * Devices register a random MAC-style id + 4-digit PIN. The website (GitHub Pages) lets the owner add
 * playlists to that device with MAC + PIN; the app pulls them with the same MAC + PIN.
 *
 * KV layout (binding FAZNET):
 *   dev:<MAC>   {pin: hmac, model, version, created, seen}
 *   pl:<MAC>    [{id, name, type, url?, server?, username?, password?, file?, created}]
 *   file:<tok>  raw M3U text
 *   fail:<MAC>  wrong-PIN counter (expires after 15 min)
 *
 * Writes are kept rare on purpose (KV free tier: 1000 writes/day).
 */
const MAC_RE = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/;
const PIN_RE = /^\d{4}$/;
const MAX_PLAYLISTS = 20;
const MAX_FAILS = 10;
const FAIL_TTL = 15 * 60;
const MAX_FILE = 20 * 1024 * 1024;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-Faznet-Mac, X-Faznet-Pin",
  "Access-Control-Max-Age": "86400",
};

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });

function normMac(mac) {
  let m = String(mac || "").trim().toUpperCase().replace(/-/g, ":");
  if (/^[0-9A-F]{12}$/.test(m)) m = m.match(/.{2}/g).join(":");
  return m;
}

async function pinHash(env, mac, pin) {
  const secret = env.SECRET || "faznet-default-secret-change-me";
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${mac}|${pin}`));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

function token(bytes = 24) {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Returns [device, null] or [null, errorCode]. */
async function checkDevice(env, macRaw, pin) {
  const mac = normMac(macRaw);
  if (!MAC_RE.test(mac) || !PIN_RE.test(String(pin || ""))) return [null, "invalid"];
  const fails = parseInt((await env.FAZNET.get(`fail:${mac}`)) || "0", 10);
  if (fails >= MAX_FAILS) return [null, "locked"];
  const dev = await env.FAZNET.get(`dev:${mac}`, "json");
  const ok = dev && safeEqual(dev.pin, await pinHash(env, mac, pin));
  if (!ok) {
    await env.FAZNET.put(`fail:${mac}`, String(fails + 1), { expirationTtl: FAIL_TTL });
    return [null, dev ? "wrong" : "unknown"];
  }
  return [{ ...dev, mac }, null];
}

const errStatus = (e) => (e === "locked" ? 429 : e === "invalid" ? 400 : 403);

async function playlists(env, mac) {
  return (await env.FAZNET.get(`pl:${mac}`, "json")) || [];
}

function forDevice(p, origin) {
  const d = { id: p.id, name: p.name, type: p.type, updated: p.created };
  if (p.type === "xtream") Object.assign(d, { server: p.server, username: p.username, password: p.password });
  else if (p.type === "file") Object.assign(d, { type: "url", url: `${origin}/p/${p.file}.m3u` });
  else d.url = p.url;
  return d;
}

function forWeb(p) {
  const detail = p.type === "xtream" ? "Xtream" : p.type === "file" ? "File" : "URL";
  return { id: p.id, name: p.name, type: p.type, detail };
}

async function body(request) {
  try { return await request.json(); } catch { return {}; }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    // ---------- device (app) ----------
    if (path === "/api/device/register" && request.method === "POST") {
      const b = await body(request);
      const mac = normMac(b.mac), pin = String(b.pin || "");
      if (!MAC_RE.test(mac) || !PIN_RE.test(pin)) return json({ error: "invalid" }, 400);
      const h = await pinHash(env, mac, pin);
      const dev = await env.FAZNET.get(`dev:${mac}`, "json");
      const now = Date.now();
      const model = String(b.model || "").slice(0, 80), version = String(b.version || "").slice(0, 20);
      if (!dev) {
        await env.FAZNET.put(`dev:${mac}`, JSON.stringify({ pin: h, model, version, created: now, seen: now }));
        return json({ ok: true, created: true });
      }
      if (!safeEqual(dev.pin, h)) return json({ error: "mac_taken" }, 409);
      // Only write when something changed or once a day — keeps KV writes low.
      if (dev.model !== model || dev.version !== version || now - (dev.seen || 0) > 86400000) {
        await env.FAZNET.put(`dev:${mac}`, JSON.stringify({ ...dev, model, version, seen: now }));
      }
      return json({ ok: true, created: false });
    }

    if (path === "/api/device/playlists" && request.method === "GET") {
      const [dev, err] = await checkDevice(env, request.headers.get("X-Faznet-Mac"), request.headers.get("X-Faznet-Pin"));
      if (err) return json({ error: err }, errStatus(err));
      const list = await playlists(env, dev.mac);
      return json({ playlists: list.map((p) => forDevice(p, url.origin)) });
    }

    const file = path.match(/^\/p\/([A-Za-z0-9_-]{20,64})\.m3u$/);
    if (file && request.method === "GET") {
      const text = await env.FAZNET.get(`file:${file[1]}`);
      if (text == null) return new Response("Not found", { status: 404, headers: CORS });
      return new Response(text, { headers: { "Content-Type": "audio/x-mpegurl; charset=utf-8", ...CORS } });
    }

    // ---------- website ----------
    if (path === "/api/web/login" && request.method === "POST") {
      const b = await body(request);
      const [dev, err] = await checkDevice(env, b.mac, b.pin);
      if (err) return json({ error: err }, errStatus(err));
      const list = await playlists(env, dev.mac);
      return json({ ok: true, mac: dev.mac, model: dev.model || "", max: MAX_PLAYLISTS, playlists: list.map(forWeb) });
    }

    if (path === "/api/web/add" && request.method === "POST") {
      const b = await body(request);
      const [dev, err] = await checkDevice(env, b.mac, b.pin);
      if (err) return json({ error: err }, errStatus(err));
      const list = await playlists(env, dev.mac);
      if (list.length >= MAX_PLAYLISTS) return json({ error: "limit" }, 400);
      const type = String(b.type || "url");
      const p = { id: Date.now() * 1000 + Math.floor(Math.random() * 1000), name: String(b.name || "").trim().slice(0, 60), type, created: Date.now() };
      const host = (u) => u.replace(/^https?:\/\//i, "").split("/")[0];
      if (type === "url") {
        const u = String(b.url || "").trim();
        if (!/^https?:\/\//i.test(u)) return json({ error: "badurl" }, 400);
        p.url = u; p.name ||= host(u);
      } else if (type === "xtream") {
        let s = String(b.server || "").trim().replace(/\/+$/, "");
        const user = String(b.username || "").trim(), pass = String(b.password || "");
        if (!s || !user || !pass) return json({ error: "fields" }, 400);
        if (!/^https?:\/\//i.test(s)) s = "http://" + s;
        Object.assign(p, { server: s, username: user, password: pass });
        p.name ||= host(s);
      } else if (type === "file") {
        const content = String(b.content || "");
        if (!content || content.length > MAX_FILE) return json({ error: "fields" }, 400);
        const head = content.slice(0, 2048);
        if (!head.includes("#EXT") && !head.includes("://")) return json({ error: "badfile" }, 400);
        p.file = token();
        await env.FAZNET.put(`file:${p.file}`, content);
        p.name ||= "Playlist";
      } else {
        return json({ error: "fields" }, 400);
      }
      list.push(p);
      await env.FAZNET.put(`pl:${dev.mac}`, JSON.stringify(list));
      return json({ ok: true, playlists: list.map(forWeb) });
    }

    if (path === "/api/web/delete" && request.method === "POST") {
      const b = await body(request);
      const [dev, err] = await checkDevice(env, b.mac, b.pin);
      if (err) return json({ error: err }, errStatus(err));
      const list = await playlists(env, dev.mac);
      const gone = list.find((p) => p.id === Number(b.id));
      const rest = list.filter((p) => p.id !== Number(b.id));
      if (gone?.file) await env.FAZNET.delete(`file:${gone.file}`);
      await env.FAZNET.put(`pl:${dev.mac}`, JSON.stringify(rest));
      return json({ ok: true, playlists: rest.map(forWeb) });
    }

    if (path === "/" || path === "/health") return json({ ok: true, service: "faznet" });
    return json({ error: "not_found" }, 404);
  },
};
