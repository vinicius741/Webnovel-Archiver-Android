# MSC For Me Onboard Chat — Protocol Research

**Date:** 2026-11-10
**Target:** `com.msccruises.mscforme` (MSC for Me, Play Store), versionCode `50600120` (≈ 5.6.x), split-APK bundle obtained from the user's Google Drive copy.
**Method:** Static analysis — asset inspection + jadx decompile of `base.apk`. No runtime traffic was captured (no ship network available), so everything below comes from code and bundled configuration; items that need onboard verification are listed in [§9](#9-unknowns--things-to-verify-onboard).
**Goal:** Determine whether a custom UI can reuse MSC's free ship-Wi-Fi messaging backend, and document exactly how that backend works.

---

## TL;DR — verdict: feasible

The onboard chat is **standard XMPP** (MUC group chat + 1:1 chat) running on a server inside the ship's LAN, plus a small **JSON-RPC 2.0 HTTP API** on the same ship server for account provisioning and group administration. The app itself embeds a lightly-customized fork of the open-source **ChatSDK** framework (`co.chatsdk`, sdk.chat) with its XMPP provider (Smack). Almost everything a custom client needs is discoverable/configurable at runtime; nothing in the protocol is cryptographically locked to the official app:

- XMPP credentials are minted by the ship server itself (`chat-login`) and handed to the client as a Base64 blob — no per-app signing or certificate pinning protects them.
- TLS on the XMPP socket is **disabled** by the client (`SecurityMode.disabled`), so any XMPP library can talk to the server.
- Group messaging uses bog-standard XEP-0045 Multi-User Chat rooms, created through an HTTP call that returns the room JID.

A custom Android client can reuse the same backend by (1) getting a guest account token from the ship's `msc.auth` flow, (2) calling `chat-login` to obtain XMPP credentials, (3) connecting with any XMPP library (Smack, or even a completely custom minimal client) and joining one MUC room for the travel group. See [§8](#8-integration-recipe-for-a-custom-ui) for the concrete recipe.

---

## 1. What the app is made of

| Layer | Technology | Evidence |
|---|---|---|
| App code | `com.accenture.msc.*` (built by Accenture), plus ~12k obfuscated classes in the root package (`defpackage/…` after R8) | `base.apk` dex |
| Chat engine | Forked **ChatSDK** (`co.chatsdk.core|ui|xmpp`) with MSC-specific classes (`MscXMPPTCPConnection`, `MscCustomExtensionElement`, …) | `jadx-out/sources/co/chatsdk/` |
| XMPP client | **Smack** (`org.jivesoftware.smack`, `smackx` extensions incl. MUC, carbons, chat markers, blocking commands) | bundled packages |
| HTTP stack | OkHttp + custom request wrapper (`fu5`/`tj5`), Gson with per-type serializers | `com/accenture/base/connectivity/` |
| Local cache | GreenDAO (`co.chatsdk.core.dao`) for chat threads + Room for `VCardLight`, `ChatUserStatus` | dao classes |

Because ChatSDK is open source (Apache-2.0, sdk.chat), its GitHub source is an excellent companion reference when reading the decompiled, field-obfuscated fork.

## 2. Network architecture: ashore vs onboard

The app ships a complete endpoint catalog in `assets/environments/urls.properties` (~25 KB). Every service exists in two variants resolved through placeholders:

```
onboard_xxx=${onboardFinalBaseUrl}/api/jsonws/<service>/<method>   (ship LAN)
ashore_xxx=${ashoreFinalBaseUrl}/api/jsonws/<service>/<method>     (internet)
```

- **Ashore base URL:** `https://msc4me-api.msccruises.com` (test: `preprod-*.mscsmartship.com`)
- **Onboard base URL:** `${shipBaseUrl}` — resolved per ship. `assets/environments/ship/meraviglia.properties` sets `shipBaseUrl=https://api.mscsmartship.com`, i.e. the ship's internal DNS resolves the smartship domain to the onboard server; a lab/test environment (`soc_ip_onboard.properties`) uses a raw LAN address `http://172.16.80.75:8080`. The onboard path (`/api/jsonws/…`) is a **Liferay JSON web service** front.
- Ship TLS is handled with bundled certificates (`assets/certificate/certificate_ship_01.crt`, `cms_mscsmartship_com.crt`) plus an `AllTrustManager` fallback — the ship presents a self-signed cert.
- The bootstrap response tells the app the ship's Wi-Fi SSID (`wifiHotspotName`) and captive-portal URL (`captivePortalUrl`), so onboard detection is possible without internet.

Key chat-adjacent HTTP endpoints from `urls.properties`:

| Key | URL (onboard) | Purpose |
|---|---|---|
| `onboard_chat` | `…/api/jsonws/msc.chat` | JSON-RPC chat service (see §4) |
| `onboard_login_chat` | `…/api/jsonws/msc.messaging/login` | chat account login (legacy path; the app actually posts to `msc.chat`, see §4) |
| `onboard_get_chat_find_me` | `…/api/jsonws/msc.chat/get-find-me` | "find me" privacy flag status |
| `onboard_inbox_list` | `…/api/jsonws/msc.messaging/get-inbox` | app notification inbox (not chat) |
| `onboard_bootstrap` | `…/api/jsonws/msc.bootstrap/get-bootstrap` | per-ship config incl. chat server details |
| `onboard_authentication_request` | `…/api/jsonws/msc.auth` | guest authentication (Bearer token source) |

## 3. Bootstrap: how the client learns the chat server

On (re)connect the app fetches `msc.bootstrap/get-bootstrap` and deserializes a `chat` object (`BootstrapConfigDeserializer` → `ChatConfig`):

```json
"chat": {
  "server":   "<host or IP of the XMPP server>",
  "port":     <int, e.g. 5222>,
  "domain":   "<XMPP domain, becomes the JID realm>",
  "maxTextLength": <int>,
  "enableFileSharing": <bool>, "enableAudioRecording": <bool>,
  "enableGroups": <bool>, "allowMuteGroups": <bool>,
  "allowDeleteGroups": <bool>, "allowRemoveUserFromGroup": <bool>,
  "showProfilePicture": <bool>, "allowBlockContacts": <bool>,
  "maxUploadFileSize": <long>, "maxUploadVideoSize": <long>,
  "maxUploadAudioSize": <long>, "maxUploadProfilePhoto": <long>,
  "enableStickers": <bool>, "bannerDuration": <int>,
  "userColors": ["#…"]
}
```

`u11.j()` (the MSC chat manager bootstrap) feeds exactly these three values into ChatSDK's `Configuration`: domain (`c`), host (`d`), port (`e`) — see `defpackage/u11.java` lines ~355–385 (`ChatSDK.i(configuration, new XMPPInterfaceAdapter(...), new XMPPNetworkAdapter())`).

## 4. HTTP chat API — JSON-RPC 2.0 over `msc.chat`

**Transport:** `POST ${shipBaseUrl}/api/jsonws/msc.chat` with `Authorization: Bearer <token>` (the MSC guest token from `msc.auth`; the account object `cl3` injects the header, with an app-token fallback).

**Body format** (`BaseWizardSerializer.serialize`):

```json
{ "method": "<kebab-case-method>", "jsonrpc": "2.0", "params": { … } }
```

**Methods found in the APK** (serializer classes in `com/accenture/msc/connectivity/parse/`):

| Method | Serializer class | Params | Response (`data` object) |
|---|---|---|---|
| `chat-login` | `ChatLoginRequestSerializer` | `{}` | see below |
| `chat-register` | `ChatLoginRequestSerializer` | `{}` | same shape as login |
| `find-me` | `ChatLoginRequestSerializer` | `{"enabled": <bool>}` | — |
| `chatbot-login` | `ChatLoginRequestSerializer` | `{}` | chatbot (concierge) token |
| `set-room-affiliation` | `RoomAffiliationRequestSerializer` | `{roomName, service, userJid, affiliation:"none"}` | kicks/removes a user from a group |
| `create-group` | `CreateGroupInfoSerializer` | `{groupName, membersList:[User], photoBase64}` | `{groupId}` (the MUC room JID) |
| `set-room-image` | `UpdateGroupImageInfoSerializer` | `{groupId, photoBase64}` | — |
| `get-disembarked-users` | `ChatUsersStatusRequestSerializer` | `{usersList:[user wrappers]}` | per-user `{passengerChatId, passengerId, isDisembarked}` |

**`chat-login` response** (`ChatLoginResponseDeserializer` → `LoginChatResponse`):

```json
{ "data": {
    "accessToken":  "<base64 of  username@<domain>:<password> >",
    "status":       "exists | notexists | locked | notallowed",
    "terms":        "<HTML terms & conditions>",
    "qrCodeUrl":    "<URL of this user's QR code (SVG; app swaps ext to png)>",
    "minAgeForFullFeatures": <int>,
    "canAddPassengers": <bool>,
    "findMe": <bool>
} }
```

`LoginChatResponse.populateCredential` decodes `accessToken`: `Base64 → "user@domain:password"`, splits on `:`, strips the `@domain` from the username. **Those are the live XMPP credentials.**

Registration policy: the app calls `chat-login` first; when status ≠ `exists` it can call `chat-register` (see `defpackage/j21.java` ~line 469 and `defpackage/jz0.java`). `minAgeForFullFeatures` gates minors (registered but restricted?). `locked` = blocked, `notallowed` = feature denied.

## 5. XMPP layer

All connection handling is in `co/chatsdk/xmpp/XMPPManager.java` (`b()` builds the config; class `MscXMPPTCPConnection` is a thin subclass of Smack's `XMPPTCPConnection`).

**Connection parameters:**

| Parameter | Value |
|---|---|
| Host / Port / Domain | from bootstrap `chat.server` / `chat.port` / `chat.domain` |
| Overriding | a JID-style string `user@host:port` in the account overrides host/port |
| TLS | **`SecurityMode.disabled`** — plaintext XMPP on the ship LAN |
| Compression | disabled |
| SASL | plain username + password from `chat-login` |
| Resource | `Build.MODEL + ".Android" + SDK_INT` (e.g. `Pixel7.Android34`), persisted in prefs `co.chatsdk.resource` |
| Keepalive | `<presence type=available>` every **30 s** (`XMPPManager.e()`, log tag "Keepalive inviato") |
| Reconnection | Smack `ReconnectionManager`, policy `FIXED_DELAY`, delay **5 s** |

**Enabled XEPs** (managers attached in `XMPPManager` constructor / auth handler):

- XEP-0045 Multi-User Chat (groups; `XMPPMUCManager`)
- XEP-0184 Delivery Receipts (`DeliveryReceiptManager`)
- XEP-0280 Message Carbons (enabled after auth when supported — multi-device sync)
- XEP-0085 Chat States (typing indicator; `XMPPTypingIndicatorManager`)
- XEP-0333 Chat Markers (delivered/read; `chat_markers` package present)
- XEP-0191 Blocking Command (blocklist checked when adding contacts)
- XEP-0203 / push routing hint (see custom extension below)
- vCard-lite (name/avatar) cached locally in Room (`VCardLight{entityId,name,imageUrl}`)

**Sending a message** (`defpackage/h01.java`):

1. Build Smack `Message`; `stanzaId` = app-local message UUID.
2. `body` = text, or the literal `"Photo"` / `"Video"` for media messages.
3. Attach `<extras xmlns="co:chatsdk:message">` carrying `<message-type>` plus every metadata key/value of the message object (custom fields ride here).
4. For media attach `<x xmlns="p1:s3filetransfer" name="<file name>" fileid="<id>"/>`.
5. Always attach a push hint: `<x xmlns="p1:push:custom" key="aps.alert.title" value="<thread display name>"/>` — the server uses this to fan out push notifications to offline recipients.
6. Route: 1:1 thread (type 2) → Smack chat2 `ChatManager.chatWith(bareJid).send(msg)`; group thread (type 1) → `MultiUserChat.sendMessage(msg)`.

Resulting stanza (1:1, text):

```xml
<message id="<uuid>" to="<peer-bare-jid>" type="chat">
  <body>hello</body>
  <extras xmlns="co:chatsdk:message">
    <message-type>Text</message-type>
    <!-- …other message meta key/values… -->
  </extras>
  <x xmlns="p1:push:custom" key="aps.alert.title" value="John"/>
</message>
```

**Groups (XEP-0045):**

- Created via HTTP `create-group` → returns the room JID (`groupId`); the client then joins through `MultiUserChatManager.getMultiUserChat(roomJid)`.
- Join config (`defpackage/e19.java`): nickname = the user's display name; history = last **20 stanzas**, or `since` = last-seen date corrected by server time (XEP-0202 EntityTime).
- Invitations arrive as normal MUC mediated-invite stanzas and surface through `XMPPMUCManager`'s invitation listener.
- Removing a member = HTTP `set-room-affiliation` with `affiliation:"none"`.
- Local thread model: `Thread.type` 1 = group, 2 = 1:1 (ChatSDK GreenDAO entities).

**Contacts & presence:**

- Adding a contact = ChatSDK `addContact(user, ConnectionType.Contact)` (roster subscribe + accept); the blocklist (XEP-0191) is consulted first (`QrCodeChatScan.lambda$handleQrCode$2`).
- The in-app QR encodes the peer's chat identity (scanned → `MscChatUserWrapper.builder(user)`); each user's QR is generated server-side and its URL returned by `chat-login` (`qrCodeUrl`).
- Presence broadcast after auth: `<presence type="available" status="<user status>" priority="1"/>` with a custom mode from `PresenceHelper`.
- "Find me" privacy flag (`find-me` JSON-RPC / `msc.chat/get-find-me`) controls discoverability by other guests.
- Disembark status is polled via `get-disembarked-users` so the UI can gray out people who left the ship.

## 6. File transfer (photos/videos)

Flow (`defpackage/l01.java`, `defpackage/k01.java`, `co/chatsdk/xmpp/utils/CompressorWrapper.java`):

1. Client computes the file's **MD5**, compresses/resizes media first.
2. Sends an IQ **get** to the server: `<query xmlns="p1:s3filetransfer" md5="<md5>"/>` (`defpackage/yz6.java`).
3. Server answers with an **upload URL** and a **fileid** (parsed by `FileSharingUrlDeserializer` from JSON embedded in the IQ result: keys `upload`, `fileid`).
4. Client **HTTP PUT**s the file to the upload URL with header `Content-MD5: <md5>` (`k01` → `im5` request).
5. Client sends the chat message with `<x xmlns="p1:s3filetransfer" name="…" fileid="…"/>` (body = "Photo"/"Video").
6. Receivers resolve `fileid` → download URL (`FileDownloadUrl{download, fileid}`) and fetch over HTTP.

Upload size limits come from the bootstrap (`maxUploadFileSize/Video/Audio/ProfilePhoto`).

## 7. The inbox / notification side-channel

`msc.messaging/get-inbox` and `get-message/{id}` are the app's *system* inbox (notifications from the ship), distinct from guest-to-guest chat. Push when offline is via FCM plus **Pushy** (MQTT-based) with its endpoint configured in the generic bootstrap (`PushyConfig.apiEndpoint`). Not needed for a chat-only client.

## 8. Integration recipe for a custom UI

Minimal steps for an Android client reusing MSC's backend (all onboard, on ship Wi-Fi):

1. **Discover the base URL.** Either hardcode/capture the ship's bootstrap host (ship DNS resolves `api.mscsmartship.com` onboard; the QR flow below avoids guessing) or, more robustly, capture it once from the official app on the first day (any HTTP logger) and ship it as a constant per ship. `GET/POST …/api/jsonws/msc.bootstrap/get-bootstrap` → read `chat.{server,port,domain}`.
2. **Authenticate** with the guest's cruise credentials through `msc.auth` (the same login the app performs at embarkation) to obtain the Bearer token. *(Exact `msc.auth` payload was not fully traced — see §9.)*
3. **Provision the chat account:** POST `msc.chat` with `{"method":"chat-login","jsonrpc":"2.0","params":{}}` and the Bearer header; decode `data.accessToken` (Base64 → `user@domain:password`).
4. **Connect XMPP** with any library (on Android, Smack is a natural fit since the protocol extensions above are all standard Smack features): host/port/domain from step 1, credentials from step 3, no TLS, resource `<model>.Android<sdk>`, send presence every 30 s.
5. **Group per travel-party:** one member calls `create-group` (name + initial members) → everyone joins the returned room (XEP-0045, history 20). Membership thereafter behaves like any MUC room; `set-room-affiliation` removes people.
6. **Extras namespace:** mirror `<extras xmlns="co:chatsdk:message">` only if you want the official app to render your messages correctly (include at least `<message-type>`); otherwise your client can define its own payload, but messages sent *without* the push extension simply won't trigger push notifications for offline recipients.
7. Contacts/QR and typing/receipts are optional niceties; a group-only client can ignore the roster entirely.

Interoperability shortcuts worth knowing:

- A regular off-the-shelf XMPP client (e.g. Conversations) will likely connect with just steps 1–4 and could join the MUC room — useful as a quick onboard sanity test before building anything.
- Because TLS is disabled, an onboard packet capture of the official app (tcpdump on a rooted device, or Wireshark on the LAN) will show every stanza in clear — the fastest way to fill the §9 gaps on embarkation day.

## 9. Unknowns — things to verify onboard

1. **Actual `chat.server` value** (hostname vs IP) and **port** per ship — only readable from a live bootstrap response.
2. **`msc.auth` request shape** (exact credential payload / token exchange) — traced only to the service name; capture on day one or decompile further (`defpackage` auth flow classes).
3. **XMPP server software** (ejabberd/MongooseIM/Openfire…) — determines tolerance for edge-case stanzas; the `p1:s3filetransfer` IQ suggests a custom module, `p1:` prefixes are vendor-style.
4. **QR code content format** (JID vs deep link vs booking ref) — generated server-side; scan one with any QR reader.
5. Whether `chat-register` is ever required in practice or accounts are pre-provisioned at check-in (`status: "exists"` on first login suggests pre-provisioning).
6. Whether the server enforces the `extras`/`p1:push:custom` extensions for delivery (probably not) and how it routes push for app users vs non-app users.
7. Rate limits, max group size, and whether MUC rooms persist for the whole cruise.

## 10. Reproducing this analysis

```bash
# APK bundle → split APKs
unzip mscforme.apk -d apks/
# Assets (config, certs, URL catalog)
unzip apks/base.apk 'classes*.dex' 'assets/*' -d extracted/
# Decompile (limit memory: this host OOMs with default settings)
jadx --no-debug-info --no-res --threads-count 4 -d jadx-out apks/base.apk   # JAVA_OPTS=-Xmx3g
```

Evidence map (paths under the jadx output):

| Finding | File |
|---|---|
| XMPP connection config, TLS disabled, keepalive | `sources/co/chatsdk/xmpp/XMPPManager.java` |
| ChatSDK bootstrap wiring (domain/host/port) | `sources/defpackage/u11.java` (~line 355) |
| JSON-RPC body format | `sources/com/accenture/base/connectivity/parse/BaseWizardSerializer.java` |
| Method-name mapping | `sources/com/accenture/msc/connectivity/parse/ChatLoginRequestSerializer.java` etc. |
| Login response + credential decode | `sources/com/accenture/msc/model/instantMessaging/LoginChatResponse.java` |
| Bootstrap `chat` JSON keys | `sources/com/accenture/msc/connectivity/parse/BootstrapConfigDeserializer.java` (~line 240) |
| Message send flow + stanza extensions | `sources/defpackage/h01.java` |
| File upload IQ + PUT | `sources/defpackage/l01.java`, `sources/defpackage/yz6.java`, `sources/defpackage/k01.java` |
| MUC join/history config | `sources/defpackage/e19.java` |
| Endpoint catalog | `extracted/assets/environments/urls.properties` |
| Ship base-URL chain | `extracted/assets/environments/{state,ship,env}/*.properties` |
| Bearer header injection | `sources/defpackage/cl3.java` |

## 11. Responsible-use notes

- This is interoperability research for personal use by paying guests on their own cruise; the backend is a free service MSC provides to all guests. Still, MSC's app terms likely prohibit unofficial clients — treat this as "use at your own risk", don't hammer the ship server, and don't circumvent the `find-me`/blocking privacy controls for other guests.
- **Privacy caveat for the group's own messages:** the XMPP stream is unencrypted on the ship Wi-Fi (`SecurityMode.disabled`). Anyone on the guest LAN with a sniffer can potentially read stanzas. Assume the ship's chat is not private from the ship's IT staff, and possibly from other technically-inclined guests. For sensitive coordination, treat it accordingly.
- The age-gating (`minAgeForFullFeatures`) and blocking features exist for passenger-safety reasons; a custom client should preserve blocking behavior if it grows beyond a private group tool.
