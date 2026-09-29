// Split Free server on Cloudflare Workers (free plan).
// Sends push notifications through Firebase Cloud Messaging and performs the
// few writes that clients are not allowed to do themselves: joining, leaving
// and removing members, and deleting an account. Every call is authenticated
// with the caller's Firebase ID token.

let accessToken = null; // { token, exp }
let jwks = null; // { keys, exp }

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    try {
      if (req.method === "GET" && url.pathname === "/.well-known/assetlinks.json") return assetLinks(env);
      if (req.method === "GET" && url.pathname.startsWith("/j/")) return joinPage(url);
      if (req.method === "GET" && url.pathname === "/") return new Response("Split Free server is running.");
      if (req.method !== "POST" || !url.pathname.startsWith("/api/")) return json({ error: "Not found" }, 404);

      const user = await verifyUser(req, env);
      const body = await req.json().catch(() => ({}));
      const routes = {
        "/api/notify": notify,
        "/api/invite/respond": respondInvite,
        "/api/group/join": joinGroup,
        "/api/group/peek": peekGroup,
        "/api/group/leave": leaveGroup,
        "/api/group/remove": removeMember,
        "/api/account/delete": deleteAccount,
      };
      const fn = routes[url.pathname];
      if (!fn) return json({ error: "Not found" }, 404);
      return json(await fn(env, user, body, ctx));
    } catch (e) {
      const status = e.status || 500;
      if (status >= 500) console.error(e.stack || e);
      return json({ error: e.expose ? e.message : status >= 500 ? "Server error" : e.message }, status);
    }
  },
};

// ---------------------------------------------------------------- endpoints

async function notify(env, user, b) {
  if (b.kind === "invite") {
    const inv = await getDoc(env, `invites/${b.inviteId}`);
    if (!inv || inv.fromUid !== user.uid) fail(403, "Not your invite");
    if (inv.status !== "pending") return { sent: 0 };
    const target = await findUserByEmail(env, inv.toEmail);
    if (!target) return { sent: 0 }; // They haven't signed in yet; the invite waits in the app.
    const sent = await pushTo(env, target, {
      title: "Group invitation",
      body: `${inv.fromName} invited you to join “${inv.groupName}”`,
      data: { kind: "invite" },
    });
    return { sent };
  }

  const g = await getGroup(env, b.groupId);
  if (!g.members.includes(user.uid)) fail(403, "Not a member of this group");
  const to = [...new Set(b.to || [])].filter((u) => u !== user.uid && g.members.includes(u));
  let sent = 0;
  for (const uid of to) {
    const u = await getDoc(env, `users/${uid}`);
    if (!u) continue;
    if (!b.force && (u.muted || []).includes(b.groupId)) continue;
    sent += await pushTo(env, { id: uid, ...u }, {
      title: String(b.title || "Split Free").slice(0, 120),
      body: String(b.body || "").slice(0, 400),
      data: { groupId: b.groupId, expenseId: b.expenseId || "", screen: b.screen || "" },
    });
  }
  return { sent };
}

async function respondInvite(env, user, b, ctx) {
  const inv = await getDoc(env, `invites/${b.inviteId}`);
  if (!inv || inv.toEmail !== user.email) fail(403, "This invite isn't for you");
  if (inv.status !== "pending") fail(409, "This invite was already answered");
  const g = await getGroup(env, inv.groupId);
  const accept = !!b.accept;

  const writes = [
    patch(`invites/${b.inviteId}`, { status: accept ? "accepted" : "rejected", answeredAt: Date.now() }),
    {
      transform: {
        document: docName(env, `groups/${inv.groupId}`),
        fieldTransforms: [{ fieldPath: "invited", removeAllFromArray: { values: [enc(user.email)] } }],
      },
    },
  ];
  if (accept) writes.push(...addMemberWrites(env, inv.groupId, user));
  writes.push(activity(env, inv.groupId, user.uid, `${user.name} ${accept ? "joined the group" : "declined the invite"}`, [inv.fromUid].filter((u) => u && u !== user.uid)));
  await commit(env, writes);

  // Tell whoever invited them, and the group's creator.
  const notifyIds = [...new Set([inv.fromUid, g.createdBy])].filter((u) => u && u !== user.uid);
  for (const uid of notifyIds) {
    const u = await getDoc(env, `users/${uid}`);
    if (u) await pushTo(env, { id: uid, ...u }, {
      title: accept ? "Invite accepted" : "Invite declined",
      body: `${user.name} ${accept ? "accepted" : "declined"} the invite to “${g.name}”`,
      data: { groupId: inv.groupId },
    });
  }
  return { ok: true, name: g.name, groupId: inv.groupId };
}

/** The group's name for the "Join group?" dialog, once the code checks out. */
async function peekGroup(env, user, b) {
  const g = await getGroup(env, b.groupId);
  if (!b.code || g.joinCode !== b.code) fail(403, "This invite is no longer valid");
  return { name: g.name, member: g.members.includes(user.uid) };
}

async function joinGroup(env, user, b, ctx) {
  const g = await getGroup(env, b.groupId);
  if (!b.code || g.joinCode !== b.code) fail(403, "This invite link is no longer valid");
  if (g.members.includes(user.uid)) return { name: g.name };
  await commit(env, [...addMemberWrites(env, b.groupId, user), activity(env, b.groupId, user.uid, `${user.name} joined with the invite link`)]);
  const c = g.createdBy && (await getDoc(env, `users/${g.createdBy}`));
  if (c) await pushTo(env, { id: g.createdBy, ...c }, {
    title: "New member", body: `${user.name} joined “${g.name}” with the invite link`, data: { groupId: b.groupId },
  });
  return { name: g.name };
}

async function leaveGroup(env, user, b) {
  const g = await getGroup(env, b.groupId);
  if (!g.members.includes(user.uid)) return { ok: true };
  await commit(env, [removeWrite(env, b.groupId, user.uid), activity(env, b.groupId, user.uid, `${user.name} left the group`)]);
  return { ok: true };
}

async function removeMember(env, user, b) {
  const g = await getGroup(env, b.groupId);
  if (g.createdBy !== user.uid) fail(403, "Only the group's creator can remove people");
  if (!g.members.includes(b.uid)) return { ok: true };
  const name = g.memberInfo?.[b.uid]?.name || "A member";
  await commit(env, [removeWrite(env, b.groupId, b.uid), activity(env, b.groupId, user.uid, `${user.name} removed ${name}`, [b.uid])]);
  return { ok: true };
}

async function deleteAccount(env, user) {
  const groups = await runQuery(env, "groups", [fieldFilter("members", "ARRAY_CONTAINS", user.uid)]);
  const writes = groups.map((g) => removeWrite(env, g.id, user.uid));
  const invites = await runQuery(env, "invites", [fieldFilter("toEmail", "EQUAL", user.email)]);
  for (const i of invites) writes.push({ delete: docName(env, `invites/${i.id}`) });
  writes.push({ delete: docName(env, `users/${user.uid}`) });
  for (let i = 0; i < writes.length; i += 400) await commit(env, writes.slice(i, i + 400));
  await google(env, "POST", `https://identitytoolkit.googleapis.com/v1/projects/${env.PROJECT_ID}/accounts:delete`, { localId: user.uid });
  return { ok: true };
}

// ---------------------------------------------------------------- helpers

function addMemberWrites(env, gid, user) {
  return [{
    update: { name: docName(env, `groups/${gid}`), fields: { memberInfo: enc({ [user.uid]: { name: user.name, email: user.email } }) } },
    updateMask: { fieldPaths: [`memberInfo.\`${user.uid}\``] },
    updateTransforms: [{ fieldPath: "members", appendMissingElements: { values: [enc(user.uid)] } }],
    currentDocument: { exists: true },
  }];
}

function removeWrite(env, gid, uid) {
  return { transform: { document: docName(env, `groups/${gid}`), fieldTransforms: [{ fieldPath: "members", removeAllFromArray: { values: [enc(uid)] } }] } };
}

function activity(env, gid, actor, text, people = []) {
  const id = crypto.randomUUID().replace(/-/g, "").slice(0, 20);
  return { update: { name: docName(env, `groups/${gid}/activity/${id}`), fields: encFields({ actor, text, at: Date.now(), expenseId: null, people }) } };
}

function patch(path, data) {
  return { update: { name: null, fields: encFields(data) }, updateMask: { fieldPaths: Object.keys(data) }, _path: path };
}

async function getGroup(env, gid) {
  const g = gid && (await getDoc(env, `groups/${gid}`));
  if (!g || g.deleted) fail(404, "This group doesn't exist any more");
  g.members = g.members || [];
  return g;
}

async function findUserByEmail(env, email) {
  const r = await runQuery(env, "users", [fieldFilter("email", "EQUAL", email)], 1);
  return r[0] || null;
}

async function pushTo(env, u, { title, body, data }) {
  let sent = 0;
  for (const token of u.tokens || []) {
    const r = await google(env, "POST", `https://fcm.googleapis.com/v1/projects/${env.PROJECT_ID}/messages:send`, {
      message: {
        token,
        notification: { title, body },
        data: Object.fromEntries(Object.entries(data || {}).map(([k, v]) => [k, String(v ?? "")])),
        android: { priority: "HIGH", notification: { channel_id: "alerts", icon: "ic_stat_split", color: "#C15F3C" } },
      },
    }, true);
    if (r.ok) sent++;
    else if (r.status === 404 || r.status === 400) {
      // Token is dead (app uninstalled or data cleared): forget it.
      await commit(env, [{ transform: { document: docName(env, `users/${u.id}`), fieldTransforms: [{ fieldPath: "tokens", removeAllFromArray: { values: [enc(token)] } }] } }]).catch(() => {});
    }
  }
  return sent;
}

function fail(status, message) {
  const e = new Error(message);
  e.status = status;
  e.expose = true;
  throw e;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
}

// ---------------------------------------------------------------- pages

function assetLinks(env) {
  return json([{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: env.PACKAGE, sha256_cert_fingerprints: [env.SHA256] },
  }]);
}

function joinPage(url) {
  const [, , gid, code] = url.pathname.split("/");
  const safe = (s) => String(s || "").replace(/[^A-Za-z0-9_-]/g, "");
  const intent = `intent://${url.host}/j/${safe(gid)}/${safe(code)}#Intent;scheme=https;package=com.splitfree;end`;
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Join on Split Free</title><style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#FAF9F5;color:#1F1E1D;font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box}
.card{max-width:380px;width:100%;background:#fff;border:1px solid #DFDACD;border-radius:16px;padding:28px;text-align:center}
h1{font-family:Georgia,serif;font-weight:400;font-size:28px;margin:8px 0}
p{color:#5C5A54;line-height:1.5}
a.btn{display:block;background:#C15F3C;color:#fff;text-decoration:none;padding:15px;border-radius:14px;margin-top:20px;font-weight:600}
@media (prefers-color-scheme:dark){body{background:#1F1E1D;color:#F5F4EF}.card{background:#262624;border-color:#413F3B}p{color:#B4B0A6}a.btn{background:#D97757;color:#2B1710}}
</style></head><body><div class="card"><div style="font-size:44px">₹</div><h1>Join the group</h1>
<p>You've been invited to a group on Split Free. Open it in the app to join.</p>
<a class="btn" href="${intent}">Open in Split Free</a>
<p style="font-size:13px;margin-top:18px">Don't have the app yet? Ask whoever sent this link for the Split Free APK, install it, then tap the link again.</p>
</div></body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } });
}

// ---------------------------------------------------------------- auth

async function verifyUser(req, env) {
  const h = req.headers.get("authorization") || "";
  const token = h.startsWith("Bearer ") ? h.slice(7) : null;
  if (!token) fail(401, "Not signed in");
  const [h64, p64, s64] = token.split(".");
  if (!s64) fail(401, "Bad token");
  const header = JSON.parse(b64urlText(h64));
  const p = JSON.parse(b64urlText(p64));
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== "RS256" || p.aud !== env.PROJECT_ID || p.iss !== `https://securetoken.google.com/${env.PROJECT_ID}` ||
      !p.sub || p.exp < now || p.iat > now + 300) fail(401, "Session expired, please sign in again");

  if (!jwks || jwks.exp < Date.now()) {
    const r = await fetch("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com");
    const maxAge = Number((r.headers.get("cache-control") || "").match(/max-age=(\d+)/)?.[1] || 3600);
    jwks = { keys: (await r.json()).keys, exp: Date.now() + maxAge * 1000 };
  }
  const jwk = jwks.keys.find((k) => k.kid === header.kid);
  if (!jwk) fail(401, "Session expired, please sign in again");
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlBytes(s64), new TextEncoder().encode(`${h64}.${p64}`));
  if (!ok) fail(401, "Bad token");
  const email = String(p.email || "").toLowerCase();
  return { uid: p.sub, email: p.email_verified ? email : "", name: p.name || email.split("@")[0] || "Someone" };
}

async function googleToken(env) {
  if (accessToken && accessToken.exp > Date.now() + 60_000) return accessToken.token;
  const sa = JSON.parse(env.SA_KEY);
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: sa.client_email, aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/cloud-platform",
  };
  const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(JSON.stringify(claims))}`;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${unsigned}.${b64url(sig)}`,
  });
  const t = await r.json();
  if (!t.access_token) throw new Error("Google token error: " + JSON.stringify(t));
  accessToken = { token: t.access_token, exp: Date.now() + t.expires_in * 1000 };
  return accessToken.token;
}

async function google(env, method, url, body, raw = false) {
  const r = await fetch(url, {
    method,
    headers: { authorization: `Bearer ${await googleToken(env)}`, "content-type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (raw) return r;
  if (!r.ok) throw new Error(`${method} ${url} → ${r.status} ${await r.text()}`);
  return r.json();
}

// ---------------------------------------------------------------- firestore

const base = (env) => `https://firestore.googleapis.com/v1/projects/${env.PROJECT_ID}/databases/(default)/documents`;
const docName = (env, path) => `projects/${env.PROJECT_ID}/databases/(default)/documents/${path}`;

async function getDoc(env, path) {
  const r = await google(env, "GET", `${base(env)}/${path}`, null, true);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`get ${path} → ${r.status} ${await r.text()}`);
  const d = await r.json();
  return { id: path.split("/").pop(), ...dec({ mapValue: { fields: d.fields || {} } }) };
}

function fieldFilter(field, op, value) {
  return { fieldFilter: { field: { fieldPath: field }, op, value: enc(value) } };
}

async function runQuery(env, collection, filters, limit) {
  const where = filters.length === 1 ? filters[0] : { compositeFilter: { op: "AND", filters } };
  const q = { structuredQuery: { from: [{ collectionId: collection }], where, ...(limit ? { limit } : {}) } };
  const rows = await google(env, "POST", `${base(env)}:runQuery`, q);
  return rows.filter((r) => r.document).map((r) => ({ id: r.document.name.split("/").pop(), ...dec({ mapValue: { fields: r.document.fields || {} } }) }));
}

async function commit(env, writes) {
  for (const w of writes) if (w._path) { w.update.name = docName(env, w._path); delete w._path; }
  return google(env, "POST", `${base(env)}:commit`, { writes });
}

function enc(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === "boolean") return { booleanValue: v };
  if (typeof v === "number") return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (typeof v === "string") return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(enc) } };
  return { mapValue: { fields: encFields(v) } };
}

function encFields(o) {
  return Object.fromEntries(Object.entries(o).map(([k, v]) => [k, enc(v)]));
}

function dec(v) {
  if ("nullValue" in v) return null;
  if ("booleanValue" in v) return v.booleanValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return v.doubleValue;
  if ("stringValue" in v) return v.stringValue;
  if ("timestampValue" in v) return v.timestampValue;
  if ("arrayValue" in v) return (v.arrayValue.values || []).map(dec);
  if ("mapValue" in v) return Object.fromEntries(Object.entries(v.mapValue.fields || {}).map(([k, x]) => [k, dec(x)]));
  return null;
}

// ---------------------------------------------------------------- base64url

function b64url(input) {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlBytes(s) {
  const t = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(t, (c) => c.charCodeAt(0));
}

function b64urlText(s) {
  return new TextDecoder().decode(b64urlBytes(s));
}
