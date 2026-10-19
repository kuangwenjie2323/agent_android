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
- In Settings only read the version: never sign out, upload logs, check for updates or change options.
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
| F3 Files | Send `[release-check] create notes.md in the current folder with a heading and three lines, then change its second line`. When done: `tap 更多选项` → `tap 本对话改动的文件` → `tap notes.md`. | The viewer opens; `phone.py see "some lines of code are highlighted in green"` ≥ 0.7; `tap 预览` / `tap 源码` switches views; `tap 返回` closes |
| F4 Side question | `tap 旁问`, type `what did the agent do?`, `tap 发送` | An answer shows in the sheet; the chat itself gets no new message |
| F5 Pin | In the chat: `tap 更多选项` → `tap 置顶对话` → `tap 返回` | The list shows the group `已置顶` with this chat first; then open it again and `tap 更多选项` → `tap 取消置顶` |
| F6 Archive | In the chat: `tap 更多选项` → `tap 归档对话` | Back on the list and the chat is not in it; the row `已归档的对话` at the end of the list (scroll down) opens the archive, which lists it; open it there, `tap 更多选项` → `tap 移回列表`, `tap 返回` twice: it is in the main list again. Never change the archive's day setting (it is the user's) |
| F7 Memory | On the list: `tap 更多选项` → `tap 记忆` | The sheet shows memories (at least one of `关于你`, `做事方式`, `项目`, `参考`); tapping one shows its text; `tap 完成` twice closes. Never tap `立即整理` (it rewrites the memory) |
| F8 Delete | Open the test chat, `tap 更多选项` → `tap 删除对话` → in the dialog `tap 删除` | Back on the list; `gone [release-check] 10` (the chat is gone) |

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

## N: network (on the phone's current network; say which: Wi-Fi or mobile, with or without VPN)

| ID | How | Pass |
|---|---|---|
| N1 | `phone.py net <start>` at the end | `failureCount` 0 apart from `canceled` lines; `errorsShown` empty; record `avgMs` and `p95Ms` (baseline mobile + VPN: avg 1.7 s, p95 5.6 s; 420317: avg 0.9 s, p95 2.6 s). A failed request the app retried quietly is not a failure when no error was shown |
| N2 | Same output | `streamReconnects` ≤ 3 over the whole run |

## Report

Write `release-reports/<versionCode>-<wifi|mobile>.md` in the Android repo: build, phone, network, start and end
time, one table row per case (ID, pass/fail/skipped, numbers, note) and the screenshots of failed steps next to
it. End with the list of failures and anything that looked wrong but has no case yet. Do not change app code.
