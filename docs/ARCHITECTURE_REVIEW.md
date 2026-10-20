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
