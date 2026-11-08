# User guide

## First run

Three steps: what the app does; the companion apps (OndolSaem, needed, for the AI model; Nunsori, optional, for your files; Jogakbo, optional, for your notes) with their status and buttons to get or open them; and background access for scheduled tasks (notifications, exact alarms, battery). You can change all of it later in Settings.

## Chats

The start screen lists your chats (pinned first). **New chat** starts one; tap a chat to open it; its ⋮ menu pins, renames or deletes it. In a chat:

- Type a question and send. With Nunsori connected, the assistant can search your indexed files, read them, and propose moving or deleting them. Previews of the files an answer is about appear under it; tap one to open it through Nunsori.
- **Tools** (the slider button next to the message box) switches tools for this chat: Search files, Read files, Search notes (Jogakbo), Schedule, Calendar & contacts, Move & delete files, and *Auto search* (adds search results to every question, for models that call tools poorly).
- **Attach** adds a photo from the picker (vision models only).
- **Cards**: calendar events, contacts, file moves and deletions are proposals. Nothing happens until you press the button on the card. Moves and deletions are done by Nunsori (it needs "All files access").
- From Nunsori's Search, **Ask in chat** opens a new chat here with the selected files attached.

If file search is unavailable, a hint above the conversation says why (Nunsori missing, not yet allowed, or too old).

## Scheduled tasks

Ask for something later or repeatedly ("every day at 9, summarize yesterday's photos", "when a receipt is added, read the total"). A card asks you to confirm; the clock button in a chat lists its tasks (pause, resume, delete). File tasks need Nunsori and run after Nunsori indexed the file. Details: [chat.md](chat.md#scheduled-tasks).

## Settings

- **Connected apps:** OndolSaem (chat model), Nunsori (files) and Jogakbo (notes), with **Check again** and buttons to open or get them.
- **Your instructions** for every conversation (up to 2,000 characters).
- **Background tasks:** notifications, exact alarms, battery exemption; *Let the assistant judge new files*; *How new files are matched* (similarity, or OndolSaem's decision model for files with text) and *When the decision model is unsure* (ask the assistant, skip, or count as a match).
- **Language**, and **About** (version, GitHub, licenses).

Use **Ask in chat** in Nunsori or Jogakbo to attach files or notes. The assistant searches within long attachments rather than reading just their beginning. With a vision model it can request up to two image previews per question; for a video this shows a representative frame. You can ask a follow-up about the same attachment.

To ask about selected text, choose Malbotdari from Android's text actions; to share text or images, choose Malbotdari in the Share menu. Selected text appears as a quoted attachment. Review the draft before sending. Up to two images (20 MiB each) and 30,000 characters are accepted. Documents and audio cannot yet be shared into chat.
