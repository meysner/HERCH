# Hermes WebUI Mobile Endpoint Catalog

This catalog groups the routes currently exposed by `api/routes.py` for a
mobile client. It intentionally favors practical client usage over duplicating
every handler implementation detail.

## Core

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/health` | Basic server health. |
| GET | `/api/health/agent` | Hermes Agent/provider health. |
| GET | `/api/system/health` | WebUI system health. |
| POST | `/api/health/restart` | Restart health/service flow. |
| GET/POST | `/api/settings` | Read/write WebUI settings. |
| POST | `/api/client-events/log` | Client-side diagnostics log. |

## Auth

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/auth/status` | Auth/passkey/password state. |
| POST | `/api/auth/login` | Password login, sets auth cookie. |
| POST | `/api/auth/logout` | Invalidate cookie. |
| POST | `/api/auth/passkey/options` | WebAuthn login options. |
| POST | `/api/auth/passkey/login` | WebAuthn login finish. |
| POST | `/api/auth/passkey/register/options` | WebAuthn registration options. |
| POST | `/api/auth/passkey/register` | WebAuthn registration finish. |
| POST | `/api/auth/passkeys` | List passkeys. |
| POST | `/api/auth/passkey/delete` | Remove passkey. |

## Onboarding

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/onboarding/status` | First-run/setup readiness. |
| POST | `/api/onboarding/setup` | Persist provider/workspace setup. |
| POST | `/api/onboarding/complete` | Mark onboarding complete. |
| POST | `/api/onboarding/probe` | Probe provider/base URL/API key. |
| POST | `/api/onboarding/oauth/start` | Start provider OAuth device flow. |
| GET | `/api/onboarding/oauth/poll` | Poll OAuth device flow. |
| POST | `/api/onboarding/oauth/cancel` | Cancel OAuth flow. |

## Sessions

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/sessions` | Sidebar/session list. |
| GET | `/api/sessions/search` | Search sessions. |
| GET | `/api/sessions/events` | SSE invalidation events for session list. |
| GET | `/api/sessions/gateway/stream` | SSE updates for gateway/CLI sessions. |
| POST | `/api/sessions/cleanup` | Maintenance cleanup. |
| POST | `/api/sessions/cleanup_zero_message` | Maintenance cleanup for empty rows. |
| GET | `/api/session` | Session detail and message pagination. |
| POST | `/api/session/new` | Create session. |
| POST | `/api/session/update` | Update workspace/model/profile-adjacent state. |
| POST | `/api/session/delete` | Delete session. |
| POST | `/api/session/rename` | Rename session. |
| POST | `/api/session/title/regenerate` | Regenerate title. |
| POST | `/api/session/duplicate` | Duplicate session. |
| POST | `/api/session/pin` | Pin/unpin session. |
| POST | `/api/session/archive` | Archive/restore session. |
| POST | `/api/session/move` | Move session to project. |
| POST | `/api/session/clear` | Clear messages and reset title. |
| POST | `/api/session/truncate` | Keep first N visible messages. |
| POST | `/api/session/branch` | Fork from a message point. |
| POST | `/api/session/retry` | Retry last assistant response. |
| POST | `/api/session/undo` | Undo last exchange. |
| POST | `/api/session/import` | Import JSON session. |
| GET | `/api/session/export` | Export JSON session. |
| POST | `/api/session/import_cli` | Import/continue CLI session in WebUI. |
| GET/POST | `/api/session/yolo` | Read/toggle session approval skipping. |
| GET | `/api/session/status` | Runtime/session status. |
| GET | `/api/session/usage` | Token/cost usage for a session. |
| GET | `/api/session/stream` | Per-session SSE live-view channel. |
| POST | `/api/session/draft` | Persist composer draft. |
| POST | `/api/session/toolsets` | Update enabled toolsets. |
| POST | `/api/session/anchor-scene` | Persist activity anchor scene. |
| GET | `/api/session/worktree/status` | Worktree status. |
| POST | `/api/session/worktree/remove` | Remove worktree. |
| GET | `/api/session/lineage/report` | Lineage diagnostics. |
| GET | `/api/session/recovery/audit` | Read-only recovery audit. |
| POST | `/api/session/recovery/repair-safe` | Safe recovery repair. |

## Chat And Streams

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/chat/start` | Start async agent turn; returns `stream_id`. |
| GET | `/api/chat/stream` | Main SSE token/tool/reasoning stream. |
| GET | `/api/chat/stream/status` | Check active/replay state for stream. |
| GET | `/api/chat/cancel` | Cancel stream by `stream_id`. |
| POST | `/api/chat` | Synchronous fallback chat endpoint. |
| POST | `/api/chat/steer` | Send steering text to active run. |
| POST | `/api/btw` | Side-question command. |
| POST | `/api/background` | Start background task. |
| GET | `/api/background/status` | Poll background results. |
| POST | `/api/bg-task-complete-ack` | Acknowledge background completion SSE. |
| POST | `/api/goal` | Goal mode controls and kickoff. |

## Approval And Clarify

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/approval/pending` | Current approval request. |
| GET | `/api/approval/stream` | Approval SSE. |
| POST | `/api/approval/respond` | Answer approval. |
| GET | `/api/clarify/pending` | Current clarification request. |
| GET | `/api/clarify/stream` | Clarification SSE. |
| POST | `/api/clarify/respond` | Answer clarification. |

`/api/approval/inject_test` and `/api/clarify/inject_test` are loopback-only test
helpers and should not be used by a mobile app.

## Workspace And Files

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/workspaces` | Registered workspaces and last workspace. |
| POST | `/api/workspaces/add` | Add workspace. |
| POST | `/api/workspaces/remove` | Remove workspace. |
| POST | `/api/workspaces/rename` | Rename workspace display name. |
| POST | `/api/workspaces/reorder` | Reorder workspace list. |
| GET | `/api/workspaces/suggest` | Path suggestions. |
| GET | `/api/list` | List session workspace directory. |
| GET | `/api/file` | Read text file. |
| GET | `/api/file/raw` | Read/download raw bytes, supports ranges/media. |
| GET | `/api/folder/download` | Download directory archive. |
| POST | `/api/file/save` | Save text file. |
| POST | `/api/file/create` | Create file. |
| POST | `/api/file/create-dir` | Create directory. |
| POST | `/api/file/rename` | Rename file or folder. |
| POST | `/api/file/move` | Move file or folder. |
| POST | `/api/file/delete` | Delete file or folder. |
| POST | `/api/file/path` | Resolve display/native path. |
| POST | `/api/file/reveal` | Reveal in OS file manager. |
| POST | `/api/file/open-vscode` | Open in VS Code. |
| POST | `/api/upload` | Session upload multipart. |
| POST | `/api/upload/extract` | Extract upload content. |
| POST | `/api/workspace/upload` | Rich workspace upload. |
| GET | `/api/media` | Serve assistant/tool media path. |

Escape routes (`/api/escape/*`) are separate authorized file-browse flows and
are not part of the ordinary session workspace browser.

## Models, Providers, Profiles

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/models` | Picker model catalog. |
| GET | `/api/models/live` | Live provider model fetch. |
| POST | `/api/models/refresh` | Invalidate provider model cache. |
| POST | `/api/default-model` | Set default model. |
| GET | `/api/model/auxiliary` | Auxiliary model slots. |
| POST | `/api/model/set` | Set main or auxiliary model. |
| GET | `/api/providers` | Provider/key status. |
| POST | `/api/providers` | Set provider API key. |
| POST | `/api/providers/delete` | Delete provider API key. |
| GET | `/api/provider/quota` | Provider quota. |
| GET | `/api/provider/cost-history` | Provider usage/cost history. |
| GET | `/api/profiles` | List profiles. |
| GET | `/api/profile/active` | Active profile. |
| POST | `/api/profile/switch` | Switch profile. |
| POST | `/api/profile/create` | Create profile. |
| POST | `/api/profile/delete` | Delete profile. |
| GET/POST | `/api/reasoning` | Reasoning display/effort config. |
| GET | `/api/personalities` | Configured personalities. |
| POST | `/api/personality/set` | Set session personality. |

## Commands And Prompts

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/commands` | Command registry. |
| GET | `/api/commands/bundles` | Command bundles. |
| POST | `/api/commands/bundles/resolve` | Resolve bundle command. |
| POST | `/api/commands/exec` | Execute command. |
| GET/POST/DELETE | `/api/prompts` | Saved prompt list/create/delete. |

## Crons, Skills, Memory, Notes

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/crons` | List cron jobs. |
| POST | `/api/crons/create` | Create cron job. |
| POST | `/api/crons/update` | Update cron job. |
| POST | `/api/crons/delete` | Delete cron job. |
| POST | `/api/crons/run` | Run now. |
| POST | `/api/crons/pause` | Pause job. |
| POST | `/api/crons/resume` | Resume job. |
| GET | `/api/crons/output` | Job output. |
| GET | `/api/crons/history` | Job run history. |
| GET | `/api/crons/recent` | Recent completions. |
| GET | `/api/crons/status` | Cron subsystem status. |
| GET | `/api/crons/delivery-options` | Delivery targets/options. |
| GET | `/api/skills` | List skills. |
| GET | `/api/skills/content` | Skill content and linked files. |
| POST | `/api/skills/save` | Create/update skill. |
| POST | `/api/skills/delete` | Delete skill. |
| POST | `/api/skills/toggle` | Enable/disable skill. |
| GET | `/api/skills/usage` | Skill usage stats. |
| GET | `/api/memory` | MEMORY/USER/SOUL content. |
| POST | `/api/memory/write` | Write memory section. |
| GET | `/api/notes/sources` | Notes source list. |
| GET | `/api/notes/search` | Search notes. |
| GET | `/api/notes/item` | Read note item. |

## Kanban

Kanban routes are handled by `api/kanban_bridge.py`.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/kanban/boards` | List boards and active board. |
| POST | `/api/kanban/boards` | Create board. |
| PATCH | `/api/kanban/boards/<slug>` | Update board metadata. |
| DELETE | `/api/kanban/boards/<slug>` | Archive or hard-delete board. |
| POST | `/api/kanban/boards/<slug>/switch` | Set active board. |
| GET | `/api/kanban/events/stream` | SSE task event stream. |
| GET | `/api/kanban/tasks` | List tasks. |
| POST | `/api/kanban/tasks` | Create task. |
| GET | `/api/kanban/tasks/<id>` | Task detail. |
| PATCH | `/api/kanban/tasks/<id>` | Update task. |
| DELETE | `/api/kanban/tasks/<id>` | Delete/archive task. |
| POST | `/api/kanban/tasks/bulk` | Bulk task status update. |
| POST | `/api/kanban/tasks/<id>/block` | Block task. |
| POST | `/api/kanban/tasks/<id>/unblock` | Unblock task. |
| GET | `/api/kanban/tasks/<id>/log` | Task log. |
| POST | `/api/kanban/tasks/<id>/comments` | Add comment. |
| POST | `/api/kanban/links` | Create task link. |
| POST | `/api/kanban/links/delete` | Delete task link. |

Most Kanban endpoints accept `?board=<slug>`.

## Git And Terminal

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/git-info` | Compact Git badge for a session workspace. |
| GET | `/api/git/status` | Full status. |
| GET | `/api/git/branches` | Branch list. |
| GET | `/api/git/diff` | Diff. |
| POST | `/api/git/stage` | Stage file(s). |
| POST | `/api/git/unstage` | Unstage file(s). |
| POST | `/api/git/discard` | Discard changes. |
| POST | `/api/git/commit-message` | Generate commit message. |
| POST | `/api/git/commit-message-selected` | Generate selected-files message. |
| POST | `/api/git/commit` | Commit staged changes. |
| POST | `/api/git/commit-selected` | Commit selected files. |
| POST | `/api/git/fetch` | Fetch. |
| POST | `/api/git/pull` | Pull. |
| POST | `/api/git/push` | Push. |
| POST | `/api/git/checkout` | Checkout branch. |
| POST | `/api/git/stash-checkout` | Stash and checkout. |
| POST | `/api/terminal/start` | Start embedded terminal. |
| GET | `/api/terminal/output` | Terminal SSE output. |
| POST | `/api/terminal/input` | Send terminal input. |
| POST | `/api/terminal/resize` | Resize terminal. |
| POST | `/api/terminal/close` | Close terminal. |

## Insights, Logs, Wiki, Extensions, Updates

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/insights` | Token/cost insights. |
| GET | `/api/project-os/dashboard` | Project OS dashboard data. |
| GET | `/api/wiki/status` | LLM wiki status. |
| GET | `/api/wiki/browse` | Wiki page list. |
| GET | `/api/wiki/page` | Wiki page content. |
| GET | `/api/logs` | Allowlisted logs. |
| GET | `/api/plugins` | Plugin visibility. |
| GET | `/api/extensions/status` | Extension state. |
| GET | `/api/extensions/registry` | Extension registry. |
| POST | `/api/extensions/toggle` | Enable/disable extension. |
| POST | `/api/extensions/install` | Install extension. |
| POST | `/api/extensions/uninstall` | Uninstall extension. |
| GET/POST | `/api/updates/check` | Cached or forced update check. |
| POST | `/api/updates/apply` | Apply update. |
| POST | `/api/updates/force` | Force update. |
| POST | `/api/updates/summary` | Summarize update payload. |
| GET | `/api/dashboard/status` | Hermes dashboard probe. |
| GET/POST | `/api/dashboard/config` | Dashboard link config. |
| GET | `/api/rollback/list` | Checkpoint list. |
| GET | `/api/rollback/diff` | Checkpoint diff. |
| POST | `/api/rollback/restore` | Restore checkpoint. |

## MCP

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/mcp/servers` | MCP server list. |
| GET | `/api/mcp/tools` | MCP tool inventory. |
| PUT | `/api/mcp/servers/<name>` | Update server config where supported. |
| PATCH | `/api/mcp/servers/<name>` | Toggle server where supported. |
| DELETE | `/api/mcp/servers/<name>` | Delete server where supported. |

## Media And Voice

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/transcribe/capability` | Transcription availability. |
| POST | `/api/transcribe` | Speech-to-text. |
| POST | `/api/tts` | Text-to-speech audio. |
| GET | `/api/media` | Serve generated/local media path. |

## Routes To Avoid In A Mobile App

Avoid these unless you are building an admin/dev client:

- `/api/admin/reload`
- `/api/shutdown`
- `/api/csp-report`
- `/api/approval/inject_test`
- `/api/clarify/inject_test`
- `/static/*`, `/plugins/*`, `/extensions/*`, `/dashboard-plugins/*`
