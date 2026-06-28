Complete Analysis: How /api/session Handles Messages
1. The /api/session Endpoint Handler
File: C:\Users\max\Downloads\hermes-webui-master\api\routes.py, line 9494-9821
The handler at line 9494 processes GET /api/session?session_id=... and returns {"session": <session_data>}. Here is the message flow:
1. Loads the session (line 9533): s = get_session(sid, metadata_only=(not load_messages))
2. Fetches state.db messages if needed (line 9547): state_db_messages = get_state_db_session_messages(sid, ...)
3. Merges sidecar + state.db messages (lines 9580-9586): Uses merge_session_messages_append_only() to reconcile the JSON sidecar (session file on disk) with the agent's state.db rows
4. Applies pagination via _message_window_for_display() (line 9617) when msg_limit is set
5. Hydrates anchor activity scenes (line 9625) -- injects persisted worklog/tool-card scenes onto assistant messages
6. Builds the response dict (lines 9728-9740):
raw = compact_session | {
    "messages": _truncated_msgs,
    "message_count": _merged_message_count,
    "tool_calls": _session_tool_calls,
    ...
}
7. Redacts credentials (line 9809): redact = redact_session_data(raw) -- only redacts text strings, does NOT remove message structure or block types
8. Returns JSON (line 9811): j(handler, {"session": redact})
2. How Messages Are Stored -- Message Schema
Messages are stored in two places:
A. Session JSON sidecar files (.json on disk)
File: C:\Users\max\Downloads\hermes-webui-master\api\models.py, line 688-793 (Session class)
Each session is a JSON file containing messages: [...]. Messages are dicts with these typical fields:
Field
role
content
timestamp
tool_calls
tool_call_id
tool_use_id
name / tool_name
reasoning
reasoning_content
_partial
_partial_tool_calls
_error
_turnDuration
_turnTps
_gatewayRouting
_anchor_activity_scene
_anchor_stream_id
attachments
B. Agent state.db (SQLite)
File: C:\Users\max\Downloads\hermes-webui-master\api\models.py, lines 5026-5144
State.db messages have columns: role, content, timestamp, plus optional tool_call_id, tool_calls, tool_name, reasoning, reasoning_details, codex_reasoning_items, reasoning_content, codex_message_items.
3. Content Block Format -- Where thinking/tool_use/tool_result Live
This is the critical point for the user's issue. Messages store content in two different formats depending on the provider:
OpenAI Format (most common in Hermes)
{
  "role": "assistant",
  "content": "The answer text here",
  "tool_calls": [{"id": "call_123", "function": {"name": "tool_name", "arguments": "{}"}}],
  "reasoning": "The thinking text...",
  "timestamp": 1234567890
}
Tool results are separate messages:
{
  "role": "tool",
  "content": "The tool result",
  "tool_call_id": "call_123",
  "name": "tool_name",
  "timestamp": 1234567891
}
Anthropic Format (content blocks array)
Some providers (Anthropic native, some others) store content as an array:
{
  "role": "assistant",
  "content": [
    {"type": "thinking", "thinking": "reasoning text..."},
    {"type": "text", "text": "The answer"},
    {"type": "tool_use", "id": "toolu_123", "name": "tool_name", "input": {...}}
  ],
  "timestamp": 1234567890
}
Tool results in Anthropic format appear as:
{
  "role": "user",
  "content": [
    {"type": "tool_result", "tool_use_id": "toolu_123", "content": [{"type": "text", "text": "result"}]}
  ]
}
However, the Hermes agent processes and normalizes these. After streaming completes (line 8236-8268 in streaming.py), the server:
- Extracts inline <think>...</think> tags from content into the reasoning field
- Splits thinking blocks out of content arrays into the reasoning field
- The content field is cleaned to contain only the visible text
4. Transformations/Filtering Before Sending to Client
There are several layers of transformation between storage and API response:
Layer 1: Merge sidecar + state.db (merge_session_messages_append_only)
File: C:\Users\max\Downloads\hermes-webui-master\api\models.py, line 5687
This deduplicates messages from the JSON sidecar and state.db, applying truncation watermarks. Messages are NOT structurally altered -- their role, content, tool_calls, reasoning fields are preserved as-is.
Layer 2: Paginated window (_message_window_for_display)
File: C:\Users\max\Downloads\hermes-webui-master\api\routes.py, line 5955
When msg_limit is set, only the tail N visible messages are returned. The function _message_counts_as_renderable_for_window (line 5904) counts messages where:
- role is not "tool" (tool-result rows are hidden/folded into tool cards)
- The message is not an empty partial activity message
Layer 3: Tool payload bounding (_messages_for_limited_payload)
File: C:\Users\max\Downloads\hermes-webui-master\api\routes.py, line 6056
For paginated loads, tool-result content larger than 4096 chars is truncated.
Layer 4: Anchor scene hydration (_hydrate_anchor_activity_scenes)
File: C:\Users\max\Downloads\hermes-webui-master\api\routes.py, line 3822
Injects _anchor_activity_scene onto assistant messages. This adds the persisted worklog scene (thinking rows, tool cards) but does not remove existing fields.
Layer 5: Credential redaction (redact_session_data)
File: C:\Users\max\Downloads\hermes-webui-master\api\helpers.py, line 502
Walks messages[], tool_calls[], todo_state, and runtime_journal_snapshot recursively and redacts API keys/secrets from string values only. Does NOT remove or filter out message blocks by type. Preserves dict and list structure.
5. Are thinking/tool_use/tool_result Blocks Stored or Returned?
Yes, they are stored AND returned, but in different forms depending on the provider and processing stage:
Block Type	How Stored
thinking	In m.reasoning field (string) or inline <think> in content
tool_use	In m.tool_calls[] array (OpenAI format) OR in m.content[] array as {"type":"tool_use"} (Anthropic format)
tool_result	As separate role:"tool" messages with tool_call_id
The key issue for the mobile app: The Kotlin code at C:\Users\max\Downloads\hermes-webui-master\my_mobile_app\app\src\main\java\com\example\MainActivity.kt, lines 355-409, processes messages as follows:
// Line 367: Only processes role=="user" and role=="assistant"
if (role != "user" && role != "assistant") continue
// Lines 370-401: Iterates content array looking for specific block types
when (block.optString("type")) {
    "text" -> ...        // extracts text
    "thinking" -> ...    // extracts thinking
    "tool_use" -> ...    // extracts tool_use
    "tool_result" -> ... // extracts tool_result
}
There are several reasons why thinking/tool_use/tool_result blocks may NOT appear:
1. Tool-result messages (role:"tool") are SKIPPED by the mobile app (line 367). The mobile app only processes user and assistant roles. Tool results stored as separate role:"tool" messages will never appear.
2. Most Hermes sessions use OpenAI format, where:
   - tool_calls is a top-level field on assistant messages (not inside content[])
   - thinking/reasoning is in the m.reasoning field (not inside content[])
   - The mobile app only checks content[] for these block types, missing the top-level fields
3. The _message_text() function (streaming.py line 2332) extracts only text, input_text, and output_text typed parts from content arrays, deliberately ignoring thinking, tool_use, and tool_result parts.
4. _is_reasoning_only_assistant_message() (streaming.py line 3555) identifies reasoning-only messages, which are then skipped by _sanitize_messages_for_api() -- but this function is for sending messages TO the LLM provider, not for the API response.
5. Paginated loads (msg_limit) may exclude tool-result rows since they are not "renderable" (line 5918: role != "tool" returns false for renderability).
6. Summary of the Root Cause
The mobile app's loadMessages() function has two structural mismatches with how Hermes stores messages:
Mismatch A -- Missing top-level fields: The mobile app only inspects content[] arrays for thinking, tool_use, and tool_result blocks. But Hermes stores:
- Thinking/reasoning in m.reasoning (a top-level string field)
- Tool calls in m.tool_calls[] (a top-level array field)
- Tool results as separate role:"tool" messages (which the mobile app skips entirely)
Mismatch B -- Content array is often a plain string: For most providers (OpenAI-compatible), content is a plain string, not an array of typed blocks. The mobile app's content-array iteration (line 371: msg.optJSONArray("content")) returns null for string content, falling through to the else branch (line 402-404) which only extracts plain text.
To fix this, the mobile app would need to:
1. Check m.reasoning / m.reasoning_content for thinking text (in addition to content-array blocks)
2. Parse m.tool_calls[] for tool-use information (in addition to content-array tool_use blocks)
3. Either process role:"tool" messages (matched by tool_call_id) or rely on the session-level tool_calls[] array that the API also returns
4. Handle both string and array content formats