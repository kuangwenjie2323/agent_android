# Architecture review, 2026-10-20

Reviewed by Claude (lead) from the code at 742dc72, the phone logs uploaded 2026-10-20 (builds 420452, 421141,
421268) and measurements on the phone. Work is executed by DeepSeek flash (max effort) from the briefs in
`worker-briefs/` (`tools/deepseek-worker.py`), one task at a time; Claude reviews and commits each.

## What the evidence says

| Finding | Evidence | Effect on the user |
|---|---|---|
| Chat contents live only in memory | `AgentViewModel.chatCache` is a map; only the chat list is saved (`saveConversations`) | After any process end (crash, swipe from recents, update, Android reclaiming it) every chat is fetched again, ~1-1.5 s each from China |
| A stale cached tail is replaced visibly | Phone, 421268: the heavy chat shows its old tail, then the fresh page ~3 s later | The screen jumps after opening |
| The background follower replays whole turns | `AgentWorkService.followTurn` follows with no previous state; log: streams from sequence 12607 while another was at 26257 | Thousands of events over a slow link on every app switch; a reopened running chat rebuilds its turn from the first event ("renders from the start") |
| Quick app switches churn streams | 6 foreground/background switches in one minute; foreground and "following in the background" in the same second (15:41:44); two streams for one chat | Double traffic, reconnect storms, battery |
| Turn replays are as long as the turn | A 33,000-event turn; the client requires seq == last + 1, so the server cannot compress history today | Any late joiner (new process, second device, web) downloads and re-renders every token |
| Main-thread I/O reaches users first | 420452 crashed on every return (`evictAll` on the main thread, NetworkOnMainThreadException); unit tests and release checks did not see it | Crashes with no warning |
| One view model does almost everything | `AgentViewModel.kt` 1,400 lines, ~96 functions, list + chat + streaming + queue + forks + archive + search + memory | Every change touches the same file; races between features (the undo/archive race, the swipe state) are easy to make |
| Heavy tool chains | Chat 7c95273c: 38 messages, 1.77 MB of tool blocks; opening it: 3 frames, 2 janky (p90 89 ms) | Slow first frame on long agent chats (to be measured properly) |

Not app problems, for the record: 2026-10-20 21:01-22:05 the server was unreachable because the computer was
restarted from the desktop at 20:59 and only came back at 22:05; the turn running then ended with "the server
restarted mid-reply".

## Plan (in order)

1. **T1 Chat pages on disk, no jump on refresh** (`worker-briefs/t1-chat-disk-cache.md`): the newest page of up to
   40 chats in app-private storage per server+credential, removed on sign-out; open from memory, then disk, then
   network; an identical fresh page changes nothing; an `opened` diagnostics line per open (source, first content ms,
   first visible index).
2. **T2 One stream per running turn** (`t2-one-stream-per-turn.md`): the hand-over carries the position, a 5 s
   grace before handing over, never two followers, resume from the furthest position, `follow started` diagnostics.
3. **T3 Main-thread safety** (`t3-main-thread-safety.md`): repository methods safe by construction, StrictMode
   violations logged (not fatal) in the installed builds, release checks fail on them and cover the 420452 path.
4. **T4 Compressed history replay** (server + app protocol): with a client capability header, the server merges
   consecutive text/thinking events of history into one event carrying the range it covers (`seq` .. `through`);
   the reducer accepts it; old clients unchanged. A reopened running turn then catches up in one screenful.
5. **T5 Split AgentViewModel** (design first, then in steps behind the existing tests): conversation list, open chat
   session (load, follow, send, steer, queue, forks), archive and search, memory; one owner per concern.
6. **T6 Heavy chat rendering**: measure first (frames and first-content time on chat 7c95273c with T1's
   diagnostics), then move block parsing and step summaries off the main thread and memoize them if it is the cost.

Release checks gain a case for each fixed bug (the 420452 return path, a reopened running turn, a chat opened after
the process was killed).

## Status, 2026-10-21 02:00

| Task | Commits | Measured |
|---|---|---|
| Crash of 420452 | 742dc72 (from the AgentWeb DeepSeek chat), a7f588c | The return path (Home 20 s, back) no longer crashes |
| T1 chat pages on disk | fe1893a | Cold open of a long chat: content after 65-70 ms from disk instead of 6.2 s; the fresh page no longer moves the screen |
| T2 one stream per turn | 16a8aaa | Hand-over after 5 s: the follower resumed at event 57, not 0; one stream at a time; the answer was complete on return |
| T3 main-thread safety | a7f588c, 6600891 | StrictMode logs only 8 known start-up disk reads (sign-in key, settings) |
| T4 merged history replay | bb9f425 (server, live), 756d658 | Synthetic 30,000-delta turn: 34x fewer events, 14x fewer bytes; a resumed answer is no longer typed out again |
| T5 outline pages | c4521e1 (server, live), a7118a8 | Real chats 2.0-2.9x fewer bytes; cold open fresh page 0.65 s (outline, night-time network) |
| Send never resent after a broken handshake | 6600891 | A message whose TLS handshake broke is sent again instead of waiting 32 s and going back to the box |

Still open: T5 split of AgentViewModel (design first); heavy chats as turn summaries (the heaviest outline page is
still 123 KB gzip: 482 steps); settings and the sign-in key loading asynchronously; a direct LAN connection at
home (the user's decision: it opens a listener on the home network).

## Design: splitting AgentViewModel (T6)

`AgentViewModel.kt` is 1,597 lines with 64 public functions and 43 private fields after the night's work. Every
feature lands in it, so unrelated changes collide (the undo/archive race, the stale-pager and hand-over fixes all
touched the same follow code). The split keeps one public face so no screen changes.

**Shape.** `AgentViewModel` stays the class the UI holds and keeps `state: StateFlow<ClientState>`; it becomes a
facade that forwards each call to one owner. Owners are plain classes (not ViewModels) built by it with what they
need: the repository, the `viewModelScope`, the generation/epoch guards, and a small `StateHost` that reads
`ClientState` and applies an update to the slice the owner is responsible for.

| Owner | Takes over |
|---|---|
| `MemoryController` | memory, memoryFile, consolidateMemory |
| `ClaudeSessionsController` | showClaudeSessions, refreshClaudeSessions, openClaudeSession, refreshClaudePreview, olderClaudeHistory, continueClaudeSession, its history cache |
| `ArchiveController` | openArchive, closeArchive, setPinned, setArchived, setArchiveDays, deleteConversation, the pending-change ledger and recentlyArchived |
| `ConversationListController` | refresh, refreshList, search (with the content search), showEmpty, loadPreviews, listShown and prefetch, adoptSeen, projects |
| `ChatComposer` | draft, attachments (read/add/remove/owner/loading/failure), the saved drafts per chat |
| `ChatStream` | open, openById, back, reload, loadAndFollow, follow, yieldStream, onBackground/onForeground, the memory and disk caches, open diagnostics, loadOlder, loadMessageBlocks |
| `ChatControls` | send, steer, queue, resumeQueue, stop, retry, edit, forks/openParent, answer, side questions, suggestions, taskOutput/stopTask, readFile |

**Steps** (each one DeepSeek task, behaviour unchanged, the existing 440+ tests green and moved next to their owner
where they test only it; no screen file changes; one task at a time, reviewed and phone-checked before the next):
1. MemoryController and ClaudeSessionsController (least coupled).
2. ArchiveController.
3. ConversationListController.
4. ChatComposer.
5. ChatStream and ChatControls together (they share the open chat's lifecycle; the riskiest step, last).

Not to do in the split: changing behaviour, renaming state fields the UI reads, or touching the repository.
