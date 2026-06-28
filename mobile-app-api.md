# Hermes WebUI Mobile App API Guide

This note is for building a native or WebView mobile client that talks to the
same local HTTP API used by the Hermes WebUI.

Important status: this is the current WebUI-internal API, not a stable public
mobile SDK contract. The source of truth is `api/routes.py`, `api/streaming.py`,
and the vanilla JS clients in `static/*.js`. Expect to version your mobile app
against the WebUI build you deploy.

## Mental Model

Hermes WebUI is a local server in front of Hermes Agent. A mobile app should not
call model providers directly if it wants parity. It should call the WebUI
server, which owns:

- session storage and sidebar metadata
- profile-scoped Hermes homes
- model/provider resolution
- chat turn execution
- SSE token/tool/reasoning streaming
- approvals and clarification prompts
- workspace file access
- cron jobs, skills, memory, notes, Kanban, and settings

The normal transport is plain HTTP JSON plus Server-Sent Events. There is no
WebSocket requirement for core chat.

## Connection

Default local WebUI origin:

```text
http://127.0.0.1:8787
```

For phone access, use the same deployment choices as the browser UI: SSH tunnel,
Tailscale/VPN, reverse proxy, or LAN bind. Do not expose an unauthenticated
WebUI directly to the public internet.

All relative paths below are same-origin. Keep cookies enabled.

## Authentication And CSRF

Authentication is optional. Check it first:

```http
GET /api/auth/status
```

If password auth is enabled:

```http
POST /api/auth/login
Content-Type: application/json

{"password":"..."}
```

The server sets an HTTP-only cookie named `hermes_session` by default. The name
can be changed via `HERMES_WEBUI_COOKIE_NAME`, so a robust native client should
use the cookie returned by the server rather than hard-coding only the default.

Browser/WebView clients with `Origin` or `Referer` on unsafe requests must also
send CSRF once authenticated:

```http
X-Hermes-CSRF-Token: <token>
```

The WebUI shell injects the token into:

```js
window.__HERMES_CONFIG__.csrfToken
```

Native HTTP clients that do not send browser `Origin`/`Referer` headers are not
treated as browser unsafe requests by the current server code, but sending the
CSRF token when available is still the safest compatibility path.

Passkeys exist (`/api/auth/passkey/*`) but are best treated as an advanced
browser/WebAuthn flow. Password login is the simpler mobile bootstrap path.

## Minimum Chat Flow

1. Read health and setup state.

```http
GET /health
GET /api/onboarding/status
GET /api/settings
GET /api/models
GET /api/workspaces
GET /api/profiles
```

1. Create or select a session.

```http
GET /api/sessions
POST /api/session/new

{"workspace":"/path/to/workspace","model":"...","model_provider":"...","profile":"default"}
```

The response is:

```json
{
  "session": {
    "session_id": "abc123def456",
    "title": "Untitled",
    "workspace": "/path/to/workspace",
    "model": "...",
    "messages": []
  }
}
```

1. Send a message.

```http
POST /api/chat/start
Content-Type: application/json

{
  "session_id": "abc123def456",
  "message": "Say hello",
  "model": "anthropic/claude-sonnet-4.6",
  "model_provider": "anthropic",
  "workspace": "/path/to/workspace",
  "profile": "default",
  "attachments": [],
  "explicit_model_pick": true
}
```

Success returns a stream handle:

```json
{
  "stream_id": "stream-...",
  "session_id": "abc123def456",
  "pending_started_at": 1782450000.0
}
```

1. Attach an SSE listener.

```http
GET /api/chat/stream?stream_id=<stream_id>
Accept: text/event-stream
```

Use the stream until `event: stream_end`, `event: cancel`, or `event: error`.
The final session payload arrives on `event: done`, but `stream_end` is the
canonical close signal for the current frontend.

1. Recover after app backgrounding.

```http
GET /api/chat/stream/status?stream_id=<stream_id>
GET /api/session?session_id=<session_id>
```

If `replay_available` is true, reconnect with journal replay parameters when
possible. At minimum, reload the session detail after reconnect.

## Chat SSE Events To Implement

Core events:

- `token`: `{ "text": "..." }`
- `reasoning`: `{ "text": "..." }`
- `interim_assistant`: partial assistant message payload
- `tool`: tool start/progress, usually includes `name`, `preview`, ids/args when available
- `tool_complete`: tool result/snippet payload
- `approval`: command approval request
- `clarify`: clarification question request
- `metering`: live token/TPS/usage data
- `context_status`: context/compression status
- `compressing`: context compression started
- `compressed`: compression finished, may point to a continuation session
- `state_saved`: persistent memory/skill save notification
- `todo_state`: live todos snapshot
- `goal`: `/goal` state update
- `goal_continue`: continuation prompt for goal mode
- `pending_steer_leftover`: steer text not consumed this turn
- `warning`: non-fatal warnings such as fallback/rate-limit notices
- `apperror`: application/provider error payload
- `done`: final session and usage payload
- `stream_end`: close signal for the stream
- `cancel`: cancellation result

Recommended mobile behavior:

- Render `token` incrementally into the current assistant bubble.
- Render `reasoning` separately and collapsibly.
- Render `tool`/`tool_complete` as activity rows, not as ordinary assistant text.
- On `approval`, block the session composer until answered.
- On `clarify`, show a choice/input card and block ordinary sends until answered.
- On `done`, replace local session state with `data.session`.
- On `stream_end`, close the SSE connection and refresh session metadata if needed.
- Treat `apperror` as a terminal user-visible error, but still wait for
  `stream_end`/`cancel` if the connection remains open.

## Approval Flow

Approval requests are emitted in-stream and can also be polled or streamed
separately.

```http
GET /api/approval/pending?session_id=<sid>
GET /api/approval/stream?session_id=<sid>
POST /api/approval/respond
```

Response body:

```json
{
  "session_id": "abc123def456",
  "choice": "once"
}
```

Valid choices:

- `once`
- `session`
- `always`
- `deny`

Some clients also expose a session-scoped YOLO toggle:

```http
GET  /api/session/yolo?session_id=<sid>
POST /api/session/yolo

{"session_id":"<sid>","enabled":true}
```

## Clarification Flow

Clarification requests are similar to approvals:

```http
GET /api/clarify/pending?session_id=<sid>
GET /api/clarify/stream?session_id=<sid>
POST /api/clarify/respond
```

Typical response body:

```json
{
  "session_id": "abc123def456",
  "response": "Use option A"
}
```

The pending payload may include `question`, `choices_offered`, `requested_at`,
and `timeout_seconds`.

## Session Detail And Pagination

Full session:

```http
GET /api/session?session_id=<sid>
```

Metadata-only:

```http
GET /api/session?session_id=<sid>&messages=0
```

Tail window:

```http
GET /api/session?session_id=<sid>&msg_limit=80
```

Older messages:

```http
GET /api/session?session_id=<sid>&msg_limit=80&msg_before=<offset>
```

The response is always:

```json
{"session": {...}}
```

Useful fields include `messages`, `message_count`, `_messages_truncated`,
`_messages_offset`, `tool_calls`, `active_stream_id`, `pending_user_message`,
`pending_attachments`, `context_length`, `threshold_tokens`, and
`last_prompt_tokens`.

## Session List And Live Sidebar Updates

Session list:

```http
GET /api/sessions
GET /api/sessions?include_archived=1
GET /api/sessions?exclude_hidden=1
GET /api/sessions?all_profiles=1
GET /api/sessions?sidebar_source=webui
GET /api/sessions?sidebar_source=cli
```

Live invalidation stream:

```http
GET /api/sessions/events
```

Gateway/CLI session stream:

```http
GET /api/sessions/gateway/stream
GET /api/sessions/gateway/stream?probe=1
```

The mobile client can keep the implementation simple: subscribe to
`/api/sessions/events`, then refetch `/api/sessions` when a `sessions_changed`
style event arrives.

## Workspace And Files

Workspaces:

```http
GET  /api/workspaces
POST /api/workspaces/add       {"path":"/path","name":"optional"}
POST /api/workspaces/remove    {"path":"/path"}
POST /api/workspaces/rename    {"path":"/path","name":"New name"}
POST /api/workspaces/reorder   {"paths":["/a","/b"]}
GET  /api/workspaces/suggest?prefix=/Users/me
```

File browsing uses a session workspace:

```http
GET /api/list?session_id=<sid>&path=.
GET /api/file?session_id=<sid>&path=README.md
GET /api/file/raw?session_id=<sid>&path=image.png
GET /api/folder/download?session_id=<sid>&path=docs
```

File mutation:

```http
POST /api/file/save       {"session_id":"<sid>","path":"a.txt","content":"..."}
POST /api/file/create     {"session_id":"<sid>","path":"a.txt","content":""}
POST /api/file/create-dir {"session_id":"<sid>","path":"new-dir"}
POST /api/file/rename     {"session_id":"<sid>","path":"a.txt","new_name":"b.txt"}
POST /api/file/move       {"session_id":"<sid>","path":"a.txt","dest":"dir/a.txt"}
POST /api/file/delete     {"session_id":"<sid>","path":"a.txt","recursive":false}
```

Uploads:

```http
POST /api/upload
Content-Type: multipart/form-data

fields: session_id, file
```

For richer attachment chips, the frontend also uses:

```http
POST /api/workspace/upload
```

Then pass returned attachment objects into `/api/chat/start` as `attachments`.

## Models, Providers, Profiles

Models:

```http
GET  /api/models
GET  /api/models?freshness=session_visit
GET  /api/models/live?provider=openrouter
POST /api/models/refresh {"provider":"openrouter"}
```

Default and auxiliary model settings:

```http
POST /api/default-model {"model":"...","provider":"...","advanced":{}}
GET  /api/model/auxiliary
POST /api/model/set {"scope":"main","provider":"...","model":"...","advanced":{}}
POST /api/model/set {"scope":"auxiliary","task":"compression","provider":"...","model":"..."}
```

Providers:

```http
GET  /api/providers
POST /api/providers        {"provider":"openrouter","api_key":"..."}
POST /api/providers/delete {"provider":"openrouter"}
GET  /api/provider/quota?provider=openrouter&refresh=1
GET  /api/provider/cost-history?provider=openrouter&days=7
```

Profiles:

```http
GET  /api/profiles
GET  /api/profile/active
POST /api/profile/switch {"name":"default"}
POST /api/profile/create {"name":"work","clone_from":"default","base_url":"...","api_key":"..."}
POST /api/profile/delete {"name":"work"}
```

Profile is important: new sessions and chat starts should include the active
profile to avoid cross-tab/profile confusion.

## Feature Parity Checklist

For a mobile app that "can do everything" at practical parity, implement these
layers in order:

1. Auth, settings, onboarding status, profiles, models, workspaces.
2. Sessions: list, create, load, rename, pin, archive, duplicate, delete, import/export.
3. Chat: `/api/chat/start`, `/api/chat/stream`, cancel, retry, undo, truncate/edit.
4. Live interaction cards: approvals, clarifications, tools, reasoning, metering.
5. Attachments and workspace browser/editor.
6. Commands and slash-command UI from `/api/commands` and `/api/commands/exec`.
7. Crons/tasks, skills, memory, notes, Kanban, logs, insights.
8. Optional advanced surfaces: terminal, Git controls, gateway management,
   updates, extensions, passkeys.

## Mobile Client Implementation Notes

- Use an HTTP client with a cookie jar.
- Use an SSE client that supports named events and `id:` cursors.
- Keep one active chat stream per session.
- On app resume, call `/api/session` for the active session and
  `/api/chat/stream/status` for any remembered `stream_id`.
- Treat `/api/session` as authoritative after any reconnect.
- Keep a local draft per session if you want WebUI-like composer persistence;
  the server also has `/api/session/draft`.
- Do not assume file paths are portable across phone and server. Workspace paths
  are server-side paths.
- Do not print or persist provider keys in app logs.
- If you wrap WebUI in a WebView, make sure cookies, EventSource, multipart
  upload, and custom CSRF headers work in that WebView.

## Source Files Used

- `api/routes.py`: route dispatch and request/response shapes
- `api/streaming.py`: chat stream event names and payload lifecycle
- `api/route_approvals.py`: approval pending/respond/SSE state
- `api/clarify.py`: clarification pending/respond/SSE state
- `static/messages.js`: browser chat client behavior
- `static/workspace.js`: shared `api()` helper and workspace calls
- `static/panels.js`: settings, providers, profiles, crons, skills, memory, Kanban
- `ARCHITECTURE.md`: high-level server and state model
