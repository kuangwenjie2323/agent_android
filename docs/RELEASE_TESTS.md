# Release checks for the AgentWeb Android app

Every build that goes to the phone through the in-app updater passes these checks first. Claude (the lead)
writes and maintains the cases; DeepSeek flash runs them with `tools/release-check/run_with_deepseek.sh`; Claude
reviews the report before publishing. The phone commands are in `tools/release-check/phone.py` (each prints one
JSON line; run it without arguments for the list).

## Rules for whoever runs the checks

- The phone is on USB (`adb devices` lists it). Keep it awake: `adb shell svc power stayon usb`.
- Never change the phone's settings (Wi-Fi, airplane mode, developer options, keyboard settings, VPN). Test on
  the network the phone is on and record it (`phone.py info`). For the mobile-data pass the user switches Wi-Fi
  off; ask in the report if a pass is missing.
- Work only in a chat you create yourself. Its first message starts with `[release-check]`. Never type into,
  retry, edit, stop, fork or delete any other chat. Delete your own chat at the end (case F8).
- In Settings only read the version: never sign out, upload logs, check for updates or change options. The one
  exception is `外观` in case UI4, which is set back to what it was.
- Never press the system Back key on the app's chat list (it leaves the app); use the app's `返回` button.
- Use the model `deepseek-flash` for test chats and keep messages short.
- Find and tap elements by their text or label (`phone.py find/tap/wait`). Use `phone.py locate` (Jev vision)
  only for things with no text or label, and `phone.py see` for how the screen looks.
- Typing: tap the message box, switch the keyboard to QWERTY with the globe key if it shows pinyin
  (`phone.py find QWERTY` tells which is active), then `phone.py type "<ascii>"`.
- A step that fails: take a screenshot into the report folder, note it, and go on with the next case.
- Write down every number. Note the start time (`date +%s`) for the `crashes` and `net` commands.

## U: unit tests

| ID | How | Pass |
|---|---|---|
| U1 | `JAVA_HOME=$HOME/.local/opt/jdk-21 ANDROID_HOME=$HOME/.local/opt/android-sdk ./gradlew -q :native:testDebugUnitTest` in the Android repo; count results in `native/build/test-results/testDebugUnitTest/*.xml` | 0 failures. `ComfyViewModelTest` and `ClaudeSessionsViewModelTest` are known to fail now and then on "UncaughtExceptionsBeforeTest" / "Dispatchers.Main is used concurrently": rerun only those classes once; pass if the rerun passes |
| U2 | Only when the release includes server changes: `python3 -m unittest discover -s . -p 'test_*.py'` in `agentweb/tests` of the server worktree | `OK`. The runner's shell has `NO_PROXY=*`: a test that fails only because of it is a test bug to report, not a release failure |

## S: smoke (about 5 minutes)

| ID | Steps | Pass |
|---|---|---|
| S1 | `phone.py install <apk>`, then `phone.py info` | versionCode equals the build's |
| S2 | `phone.py launch`; `wait 对话 10`; `find deepseek-flash` or any chat title | list shows within 10 s, at least one chat |
| S3 | Tap the first chat title; `wait` for any message text (15 s); `tap 返回` | history shows; back on the list |
| S4 | `tap 新对话`; check the model chip (`find deepseek-flash`), else open it, `tap deepseek-flash`, `tap 完成`; tap the box; type `[release-check] reply with the single word pong`; `tap 发送`; `wait pong 90` | `pong` arrives; note seconds from send to answer |
| S5 | In the chat: `tap 更多选项` → `tap 模型与设置` → `wait 思考强度` → `tap 完成` | sheet opens and closes |
| S6 | `tap 设置` tab; `find 0.8` (the version line reads `AgentWeb App <version> · <commit> · <date>`) | version and commit match the build (`git log -1` at build time) |

## F: system and function (about 15 minutes, in the S4 chat)

| ID | Steps | Pass |
|---|---|---|
| F1 Mid-turn message | Send `[release-check] run the shell command sleep 40, then reply with the word done`. When a running step shows (`wait 运行命令 30`), type `also reply with the word steered` and `tap 插话` (the send button's label while a turn runs). | The send button is enabled (a spinner while it sends); the final answer contains `steered` (the agent saw the message before the turn ended) and `done` |
| F2 Background hand-over | Send the sleep 40 message again. When it runs: note the time, `phone.py home`, wait 60 s, `phone.py launch`. | In the app log after `home` exactly one `GET /api/chat/stream/… stream opened` (`adb shell run-as com.karewinkcloud.agentweb.client cat no_backup/diagnostics/current.jsonl`); after launch the answer `done` shows in full within 20 s |
| F3 Files | Send `[release-check] create notes.md in the current folder with a heading and three lines, then change its second line`. When done: `tap 更多选项` → `tap 本对话改动的文件` → `tap notes.md`. | The viewer opens; `phone.py see "some lines of code are highlighted in green"` ≥ 0.7; the line the agent replaced shows as a red row with its old text (`see "a red row shows the text of a removed line"` ≥ 0.7, and no `删除了` note: `gone 删除了 3`); `tap 预览` / `tap 源码` switches views; `tap 返回` closes |
| F4 Side question | `tap 旁问`, tap the sheet's own box by its whole placeholder `问点关于这个对话的事…` (the part before … also matches the sheet's explanation; the chat's own box is `输入消息…`), type `what did the agent do?`, `tap 发送` | An answer shows in the sheet; the chat itself gets no new message |
| F5 Pin | In the chat: `tap 更多选项` → `tap 置顶对话` → `tap 返回` | The list shows the group `已置顶` with this chat first; then open it again and `tap 更多选项` → `tap 取消置顶` |
| F6 Archive | In the chat: `tap 更多选项` → `tap 归档对话` | Back on the list and the chat is not in it; the row `已归档的对话` at the end of the list (scroll down) opens the archive, which lists it; open it there, `tap 更多选项` → `tap 移回列表`, `tap 返回` twice: it is in the main list again. Never change the archive's day setting (it is the user's) |
| F7 Memory | On the list: `tap 更多选项` → `tap 记忆` | The sheet shows memories (at least one of `关于你`, `做事方式`, `项目`, `参考`); tapping one shows its text; `tap 完成` twice closes. Never tap `立即整理` (it rewrites the memory) |
| F9 Swipe (run before F8) | On the list, with the test chat's title as the list shows it (noted in S4): `phone.py peek "<title>" right <shots>/F9-archive.png`, `phone.py peek "<title>" left <shots>/F9-delete.png`. Then `phone.py swipe "<title>" right`, at once `tap 撤销`; `wait "<title>" 10`; wait 15 s, `launch`, `find "<title>"`. Then `phone.py swipe "<title>" left`, `wait 删除这个对话？ 5`, `tap 取消` | Mid-swipe (judge the saved shots by eye: `see` only looks at the live screen): blue band with the archive icon to the left (its word may be cut at peek distance), red band with `删除` to the right, `palette` ok on both shots; the undo bar shows `已归档` and `撤销`; after undo the chat is back and still there 15 s later; the delete swipe asks first and `取消` keeps the chat |
| F10 Search by words (run before F8) | On the list tap `搜索对话`, type `steered` (a word only in F1's answer, not in any title), wait 3 s | `对话内容中` shows the test chat with a line of its text; tapping it opens the chat; clearing the search (the × button, `清除搜索`) brings the full list back. `see` is not needed: `find steered` must match the hit's text |
| F11 Cold open from the phone (run before F8) | Open the S3 chat once and go back. `adb shell am force-stop com.karewinkcloud.agentweb.client`, `phone.py launch`, open the same chat. Read the app log line tag `chat`, message `opened` for it | `source` is `disk` (or `memory`), `firstContentMs` ≤ 500, `firstVisible` is the last or next-to-last item of `total` (the chat opens at its end) |
| F12 Hand-over while a turn runs (with F2) | In F2's log after `home`: the lines tag `stream`, message `follow started` | Within ~1 s of `home` the service starts (`work` `following in the background`); about 5 s later one `follow started` with `who` `background` and `from` > 0 (it resumes where the screen was, not at event 0); after `launch` one `follow started` with `who` `screen`. Never two streams of one run at once |
| F13 A finished turn's steps on demand (in the F3 chat) | Tap the folded summary row of a finished turn (`思考了` / `运行了`), then tap one command step to open it | The step's output shows (a loading row first is fine); the app log has one `GET .../messages/<id>/blocks` for that message, and opening a second step of the same turn makes no new request |
| F8 Delete | Open the test chat, `tap 更多选项` → `tap 删除对话` → in the dialog `tap 删除` | Back on the list; `gone [release-check] 10` (the chat is gone) |

When the run includes the UI suite, do F8 last, after UI (which needs the test chat). F11-F13 also run before F8.

Not testable here without changing settings or files on the phone, so skipped (say so in the report): offline
send, sending a picture, rotation, process death.

## ST: stability (about 10 minutes, read-only)

| ID | Steps | Pass |
|---|---|---|
| ST1 | `phone.py memory` (before). Then 20 rounds: tap the chat at position (round mod 3) + 1 in the list, `wait` 3 s, `scroll up 3`, `scroll down 3`, `tap 返回`; every 5th round `phone.py home`, wait 3 s, `phone.py launch`. Then `phone.py memory` (after) and `phone.py crashes <start>` | No crash or ANR; `find 对话` still works; memory see M2 |

## P: performance

Baselines (build 420317, 10-19, phone CPH2723): cold start 469 ms median; chat list scroll 4.4 % janky, p99
23 ms; long chat scroll 0.2 % janky, p99 11 ms.

| ID | How | Pass |
|---|---|---|
| P1 | `phone.py startup 5` | median ≤ 1500 ms (flag anything above 2× baseline) |
| P2 | On the list: `frames reset`, `scroll down 4`, `scroll up 4`, `frames report` | janky ≤ 10 %, p99 ≤ 50 ms |
| P3 | In the longest existing chat (read only): `frames reset`, `scroll up 6`, `scroll down 6`, `frames report` | janky ≤ 10 %, p99 ≤ 50 ms |
| P4 | Seconds from send to `pong` in S4 | record (no limit; the network decides) |

## M: memory

| ID | How | Pass |
|---|---|---|
| M1 | `phone.py memory` after the smoke checks | PSS ≤ 300 MB, Java heap ≤ 96 MB (baseline 165–196 MB PSS, 20–30 MB Java) |
| M2 | Before and after ST1 | PSS grows ≤ 60 MB, Java heap grows ≤ 30 MB |

## N: network and main-thread work (on the phone's current network; say which: Wi-Fi or mobile, with or without VPN)

| ID | How | Pass |
|---|---|---|
| N1 | `phone.py net <start>` at the end | `failureCount` minus `retried` is 0 (a request that failed before it was sent and was tried once more counts in both); `errorsShown` empty; record `avgMs` and `p95Ms` (baseline mobile + VPN: avg 1.7 s, p95 5.6 s; 420317: avg 0.9 s, p95 2.6 s). A failed request the app retried quietly is not a failure when no error was shown |
| N2 | Same output | `streamReconnects` ≤ 3 over the whole run |
| N3 | `phone.py strict <start>` at the end | `ok` true: no new `strict` line in the app log (no socket or leaked-closable work on the main thread, and no disk work beyond the known start-up sites listed in phone.py `KNOWN_STARTUP_DISK`; each line names its type and the app call site). Report `known` too |
| N4 Return after a pause (the 420452 crash path) | `phone.py launch`, `wait 对话 10`; note `t0` (`date +%s`); `phone.py home`; wait 20 s; `phone.py launch`; `wait 对话 10`; then `phone.py crashes <t0>` and `phone.py strict <t0>` | `crashes` ok (no crash, ANR or bad exit) and `strict` ok (returning must not close sockets on the main thread). Build 420452 died on every return after 15 s away |

## UI: look and consistency (about 15 minutes; read-only apart from the test chat)

The app should look like one app everywhere: an Apple Music-like palette (black canvas in dark mode, white in
light mode, grey cards, hairline separators), red as the only accent, blue only for the archive swipe and links,
code in the monospaced font. A component drawn in Material's default colours (lavender, purple, mauve) is a bug:
the theme never set that colour role. Run this suite in the S4 chat before F8 deletes it.

For every screen and overlay below: `phone.py shot <shots>/UI<n>-<name>.png`, then `phone.py palette` on that
file (must be ok; a hit names the colour role and where it is), and on screens with controls `phone.py targets`
(must be ok; a hit names the element and its size in dp). Then the `see` statements the case lists, each ≥ 0.7.
Put every failing shot in the report with what is wrong.

| ID | What to open | Pass (besides palette and targets) |
|---|---|---|
| UI1 Screens | Chat list (top and scrolled to the end), the S4 chat, the archive (`已归档的对话`), the tabs `创作`, `Claude Code`, `设置` | `see "no text is drawn over other text or cut off inside its own row or button (rows partly hidden at the top or bottom edge of a scrolling list, cards cut by the screen's side in a row that scrolls sideways, and titles ending with …, are fine)"`; `see "apart from the coloured model avatars, the screen uses black, white and greys with one red accent; nothing is purple or lavender"` (the avatars carry each model's brand colour on purpose) |
| UI2 Overlays | List `更多选项` menu; chat `更多选项` menu; `模型与设置` sheet; `添加附件` menu; `旁问` sheet; `本对话的提问` (questions list); `本对话改动的文件`; the file viewer (both `源码` and `预览`); `记忆` sheet; the delete dialog (from the chat menu `删除对话`, then `tap 取消`); the undo bar (from F9: shoot it within 3 s of the swipe; it stays 10 s) | `see "the menu, panel or dialog card itself is dark grey with white text, and inside the card red is used only for the main or destructive action"` (in light mode: light grey card on white; red buttons outside the card, such as + or the model chip, do not count); menus and sheets have no purple tint. The file viewer is full-screen, not a card: for it `see "a full-screen page with a top bar, the file's text below and no purple tint"` |
| UI3 Same parts look the same | Compare the shots from UI2 | Every sheet has the same background and title style; both dialogs (delete, any other) have the same card colour and button style; in a dialog with a red destructive button, `取消` is not red; the undo bar matches the cards (no light bar in dark mode). Note any odd one out |
| UI4 Light mode | `设置` → `外观`: note the current choice, choose `浅色` (it applies at once). Repeat UI1 for the list and the chat, and UI2 for one sheet, the delete dialog and the undo bar. Then set `外观` back to the noted choice. Change nothing else on that screen | Same as UI1/UI2 for light mode; `外观` is back to its first value at the end |
| UI5 Keyboard and edges | In the chat tap the message box (the keyboard opens) and shoot; on the list scroll to the end and shoot | `see "the message box sits right above the keyboard and nothing covers it"`; the last list row and `已归档的对话` scroll fully above the bottom tab bar; the undo bar sits above the tab bar, not over it |
| UI6 Text fits | The list (long titles), the archive's day buttons, every menu from UI2 | Long titles end with … on one line; the day buttons read in full (`find "14 天"` and the others); no menu item wraps or is cut |
| UI7 Touch targets | `phone.py targets` on the list, the chat (with the composer), the archive, the file viewer, each sheet from UI2, each with nothing moving (no scroll, keyboard or sheet animating) | No tappable element under 44 dp on a side; list any with its label. `targets` already skips rows cut by a list's edge and anything small in only one of two looks. A text field can report smaller bounds than it shows (the search box above a scrolled list): tap its visible lower edge, and if the keyboard opens (`adb shell dumpsys input_method` shows `mInputShown=true`; close it with `adb shell input keyevent KEYCODE_ENTER`) it passes |
| UI8 Code and diffs | The F3 file viewer, `源码` view | `see "the code uses a monospaced font"`; added lines green, removed lines red with their text, line numbers grey |

## Report

Write `release-reports/<versionCode>-<wifi|mobile>.md` in the Android repo: build, phone, network, start and end
time, one table row per case (ID, pass/fail/skipped, numbers, note) and the screenshots of failed steps next to
it. End with the list of failures and anything that looked wrong but has no case yet. Do not change app code.
