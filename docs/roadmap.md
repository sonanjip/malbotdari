# Roadmap

## Planned

1. **More apps as tool sources.** Nunsori is the first app whose tools the chat uses (`FileIndex`). A notes app (see Nunsori's roadmap) should offer its own tools through a similar contract; the chat would then list tools per connected app.
2. **Web tools** (search, fetch a page), behind an explicit switch and in a separate app with network access, since this app has none.
3. **Restore from trash** for documents Nunsori moved to `.LSA_Trash`.

## Limits

- Small models can pick the wrong tool or arguments (see [chat.md](chat.md#models-and-limits)).
- File tasks run after Nunsori indexed a file, not when it appears; a missed hint waits for the next app start or the daily check.
- Re-indexed files (after a model change in OndolSaem) count as new for file tasks.
