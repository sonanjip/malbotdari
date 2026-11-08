# Privacy

Malbotdari runs entirely on the phone. It has **no `INTERNET` permission**, no account and no analytics.

## What is stored, and where

In the app's private storage: the chats (messages, tool calls and results), photos you attached (`files/chat/<id>/`, deleted with the chat), scheduled tasks, action cards, and settings (your instructions, the chat model's name, the file match method, the position in Nunsori's list of new files). Backups are off (`allowBackup=false`). Uninstalling deletes everything.

## The companion apps

- **OndolSaem** runs the chat model. This app sends it the conversation (system prompt, history, your message, attached photos) and tool results over Binder; OndolSaem processes them in memory and does not store them. With the decision model chosen for file tasks, the name and the first 1,200 characters of each new file's text are sent to OndolSaem the same way.
- **Nunsori** holds your files. When the chat uses its tools, this app sends Nunsori the search words or file ids the model chose and receives search results, file text, thumbnails and the names you gave to people. Files are never copied here; opening one goes through Nunsori to the viewer you pick. Moves and deletions happen in Nunsori, and only after you approve a card.
- **Jogakbo** holds your notes. When the chat uses its tools, this app sends Jogakbo the search words or note ids the model chose and receives note titles, snippets and text. Notes are not stored here except as tool results in the chat.
- Nunsori sends this app a "the index changed" hint without data; this app then asks Nunsori which files are new.

## Permissions (`AndroidManifest.xml`)

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Results of scheduled tasks |
| `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` | Scheduled tasks on time, also after a reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets you exempt the app so tasks are not delayed for hours |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | A task run in progress |

Calendar and contacts need no permission: the system app opens with the fields filled in and you press Save there.

Images requested through `view_image` are read from Nunsori previews into memory and sent only to OndolSaem on this phone. File and note attachment references are stored with the chat; retrieved previews are not saved as separate chat photos.

Text shared to Malbotdari is stored in a private draft until sent or the chat is deleted. Shared images are copied into private per-chat storage; these files are removed when the chat is deleted. The app does not require broad storage access for these shares.
