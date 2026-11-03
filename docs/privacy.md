# Privacy

Malbotdari runs entirely on the phone. It has **no `INTERNET` permission**, no account and no analytics.

## What is stored, and where

In the app's private storage: the chats (messages, tool calls and results), photos you attached (`files/chat/<id>/`, deleted with the chat), scheduled tasks, action cards, and settings (your instructions, the chat model's name, the position in Nunsori's list of new files). Backups are off (`allowBackup=false`). Uninstalling deletes everything.

## The companion apps

- **OndolSaem** runs the chat model. This app sends it the conversation (system prompt, history, your message, attached photos) and tool results over Binder; OndolSaem processes them in memory and does not store them.
- **Nunsori** holds your files. When the chat uses its tools, this app sends Nunsori the search words or file ids the model chose and receives search results, file text, thumbnails and the names you gave to people. Files are never copied here; opening one goes through Nunsori to the viewer you pick. Moves and deletions happen in Nunsori, and only after you approve a card.
- Nunsori sends this app a "the index changed" hint without data; this app then asks Nunsori which files are new.

## Permissions (`AndroidManifest.xml`)

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Results of scheduled tasks |
| `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` | Scheduled tasks on time, also after a reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets you exempt the app so tasks are not delayed for hours |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | A task run in progress |

Calendar and contacts need no permission: the system app opens with the fields filled in and you press Save there.
