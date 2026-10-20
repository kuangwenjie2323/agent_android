#!/usr/bin/env python3
"""Hand one bounded task to DeepSeek flash at max effort, the way AgentWeb runs it (Claude Code's CLI on DeepSeek).

Claude (the lead) writes the task brief, reviews the change and commits it; the worker only edits and tests. The
DeepSeek key is read from the running AgentWeb server's environment, passed to the child only, never printed.

    tools/deepseek-worker.py <brief.md> [--name <run>] [--cwd <dir>]

The whole stream goes to worker-runs/<run>.log; the worker's final answer is printed and saved as
worker-runs/<run>.md.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENTWEB_DIR = os.environ.get("AGENTWEB_DIR", "/home/karewink/claude/workagent/agentweb")
SDK = os.environ.get("ANDROID_HOME", os.path.expanduser("~/.local/opt/android-sdk"))
MODEL = "deepseek-flash[1m]"

SERVER_RULES = """Rules for this task (binding):
- You are a worker. The lead (Claude) wrote this brief, will review your diff and commits it. Do not commit, push,
  stash, reset or check out branches, and do not revert changes you did not make.
- Work only inside {cwd} (the AgentWeb server worktree). Never touch /home/karewink/claude/workagent (the live
  checkout), the live server process, its database or its config, and never restart anything. No phone (no adb).
- Test with: cd tests && python3 -m unittest discover -s . -p 'test_*.py' (run with NO_PROXY unset). Tests must not
  touch real databases or real external APIs. Leave the suite green.
- Match the surrounding code: its naming, comment density (short why-comments) and English text.
- Finish with a short report: what you changed (files), why, the test results (counts), and anything you could not
  verify or chose not to do.
"""

RULES = """Rules for this task (binding):
- You are a worker. The lead (Claude) wrote this brief, will review your diff and commits it. Do not commit, push,
  stash, reset or check out branches, and do not revert changes you did not make.
- Work only inside {cwd}. Never touch the phone (no adb), the live server, its database or /home/karewink/claude/workagent.
- Build and test with: JAVA_HOME=$HOME/.local/opt/jdk-21 ANDROID_HOME=$HOME/.local/opt/android-sdk ./gradlew -q
  :native:testDebugUnitTest (and :native:assembleDebug when you change UI). Leave the tree building and green.
- Match the surrounding code: its naming, comment density (short why-comments) and English text; Chinese only in
  values-zh/strings.xml.
- Finish with a short report: what you changed (files), why, the test results (counts), and anything you could not
  verify or chose not to do.
"""


def server_env():
    listing = subprocess.run(["ss", "-ltnp"], capture_output=True, text=True).stdout
    match = re.search(r":18791 .*?pid=(\d+)", listing)
    if not match:
        raise SystemExit("The AgentWeb server (port 18791) is not running: its keys are needed.")
    with open(f"/proc/{match.group(1)}/environ", "rb") as handle:
        pairs = (item.split(b"=", 1) for item in handle.read().split(b"\0") if b"=" in item)
        return {k.decode(): v.decode() for k, v in pairs}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("brief")
    parser.add_argument("--name")
    parser.add_argument("--cwd", default=ROOT)
    args = parser.parse_args()
    name = args.name or os.path.splitext(os.path.basename(args.brief))[0]
    with open(args.brief, encoding="utf-8") as handle:
        brief = handle.read()
    live = server_env()
    sys.path.insert(0, AGENTWEB_DIR)
    import provider_env
    source = dict(os.environ)
    if live.get("DEEPSEEK_API_KEY"):
        source["DEEPSEEK_API_KEY"] = live["DEEPSEEK_API_KEY"]
    env = provider_env.claude_code_env("deepseek", MODEL, source=source)
    env.update({"JAVA_HOME": os.path.expanduser("~/.local/opt/jdk-21"), "ANDROID_HOME": SDK})
    runs = os.path.join(ROOT, "worker-runs")
    os.makedirs(runs, exist_ok=True)
    log = os.path.join(runs, f"{name}.log")
    rules = SERVER_RULES if "workagent-refactor" in os.path.abspath(args.cwd) else RULES
    prompt = rules.format(cwd=args.cwd) + "\n\n" + brief
    started = time.time()
    answer, status = "", None
    with open(log, "w") as out:
        process = subprocess.Popen(
            ["claude", "--print", "--verbose", "--model", MODEL, "--effort", "max", "--permission-mode",
             "bypassPermissions", "--output-format", "stream-json", "-p", prompt],
            cwd=args.cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            out.write(line)
            if line.startswith("{"):
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if event.get("type") == "result":
                    answer = event.get("result") or ""
        status = process.wait()
    with open(os.path.join(runs, f"{name}.md"), "w", encoding="utf-8") as handle:
        handle.write(answer)
    print(json.dumps({"run": name, "status": status, "minutes": round((time.time() - started) / 60, 1), "log": log}))
    print(answer)


if __name__ == "__main__":
    main()
