# Architecture

Malbotdari (말벗다리) is an on-device chat assistant. It holds the conversations, the scheduled tasks and the action cards; the AI model runs in **OndolSaem**, the files and their index live in **Nunsori**, the notes in **Jogakbo**. They are separate apps on the same phone and talk over Binder (bound services); this app has no `INTERNET` permission and no file permissions.

```
Malbotdari ──(OndolSaem SDK)──> OndolSaem : chat model (LiteRT-LM), tool calls come back here
Malbotdari ──(Nunsori SDK, INunsori)──> Nunsori : search_index, read_file, move/delete (confirm + commit), new files
Nunsori ──(explicit broadcast, no data)──> Malbotdari : INDEX_CHANGED → IndexChangedWorker → changesSince
Malbotdari ──(Jogakbo SDK, INotes)──> Jogakbo : search_notes, read_note
```

## Package map

| Package | What |
|---|---|
| `chat/` | `ChatController` (turns, context, tools), `ChatTools` (`ChatToolNames`, `NunsoriChatTool`), `ContextBudget`, `ChatPreviews`, `ScheduleTools`, `Photos` |
| `chat/actions/` | `ActionManager` (cards: propose, approve, dismiss; file cards are committed by Nunsori), `ActionPayloads`, calendar/contact tools |
| `chat/schedule/` | `TaskManager` (propose, confirm, alarms, `onIndexChanged`, run), `Schedule`, `TaskAlarms` + receivers + `TaskRunWorker` |
| `files/` | `FileIndex` (the files as the chat sees them), `NunsoriBridge` (Nunsori SDK client + connection state), `IndexChangedWorker` |
| `notes/` | `NotesSource` (the notes as the chat sees them), `JogakboBridge` (Jogakbo SDK client + connection state) |
| `ml/` | `ModelHost` (OndolSaem client: chat model leases, host state), `ml/llm/ChatModel` |
| `data/db` | Room: `chat_sessions`, `chat_messages`, `scheduled_tasks`, `pending_actions` (`AppDatabase.VERSION` 1) |
| `data/prefs` | `SettingsStore` (chat model id mirror, custom prompt, judge-first, file match method and unsure action, onboarding, Nunsori change cursor) |
| `ui/` | `AppScaffold` (chats → chat, settings; onboarding gate), `ui/chat`, `ui/settings` (connections, background access), `ui/onboarding` |

## Data model

Messages and tasks refer to files by **Nunsori's item ids** only (no foreign keys): a file deleted in Nunsori simply stops resolving (no name, no thumbnail). `scheduled_tasks.fileKinds` uses Nunsori's kind names (`document`, `image`, `audio`, `video`); `pendingItemIds` are Nunsori ids. `pending_actions.payload` of a file card holds Nunsori's confirmation (`tool`, `files`, `destination`/`permanent`, `commit`).

## Connections

- **OndolSaem** (`ModelHost`): same as in Nunsori before 0.10.0 (chat part only). Apps signed with OndolSaem's key connect directly; others need approval in OndolSaem.
- **Nunsori** (`NunsoriBridge`): `refresh()` connects and records `IndexState` (CONNECTED, NOT_INSTALLED, NOT_APPROVED, TOO_OLD, UNREACHABLE); every call updates it. Debug and release builds of the three apps should be signed with the same key so they connect without approval prompts.
- **Jogakbo** (`JogakboBridge`): like Nunsori's bridge, with `NotesState`; its tools (`search_notes`, `read_note`) are wrapped as `JogakboChatTool` and switched on with the *Search notes* checkbox (`ChatToolNames.NOTES`). Jogakbo embeds and searches its own notes.
- All are checked at app start and whenever the activity comes to the front (back from OndolSaem or Nunsori).

## Scheduled tasks

Time tasks: `TaskAlarms` (exact alarm per task) → `TaskAlarmReceiver` → unique `TaskRunWorker` → `TaskManager.run` → `ChatController.runScheduled`. File tasks: Nunsori's `INDEX_CHANGED` hint (SDK `IndexChangedReceiver`) → `IndexChangedWorker` (unique, also at app start and daily) → `TaskManager.onIndexChanged` → matches queued on the task → `TaskRunWorker`. Details: [chat.md](chat.md#scheduled-tasks).

## Design decisions

- **The chat left Nunsori** (Nunsori 0.10.0) so other apps (a notes app) can join the same assistant, and Nunsori stays a search app. Nunsori exposes its index as tools; this app does not know how search works.
- **Nunsori does the file work.** Moving or deleting needs All files access and keeps the index in step, so Nunsori prepares (confirmation) and commits after the user approved the card here.
- **Hints, not data, over broadcasts.** Anyone can send a broadcast, so `INDEX_CHANGED` carries nothing; the cursor-based `changesSince` is authenticated by Nunsori.

`ViewImageTool` queues JPEG previews within one chat turn. `ChatController` follows the text tool loop with a fresh-key image-bearing generation when images were requested. `PendingAttachment.Note` uses Jogakbo note IDs; metadata comes from `NotesSource.info` and the note chips open Jogakbo. This uses existing SDK methods, without an AIDL change.

Android shares create a new chat. Incoming files are copied into private per-chat storage and tracked in Room, while shared text drafts are kept in private preferences.

Room version 2 adds private chat attachments and text chunks, linked to their chat with cascading foreign keys. Processing states and errors are persisted per attachment; the version 1 to 2 migration keeps existing chats.

Shared or picked documents, images, and audio are copied under the chat's private directory and tracked in Room. A serialized processor extracts text or speech, saves bounded chunks and optional OndolSaem embeddings, and resumes interrupted work on startup. Local tools are scoped to the chat ID and use keyword search when embeddings are unavailable. PDFBox reads PDF text; OCR is requested for scanned pages and images, while audio is decoded to 16 kHz PCM for OndolSaem transcription.
