#!/usr/bin/env python3
"""Run docs/RELEASE_TESTS.md with DeepSeek flash and leave its report in release-reports/.

DeepSeek flash runs as AgentWeb runs it: Claude Code's CLI on DeepSeek's API. The keys are taken from the running
AgentWeb server's environment, passed to the child only, and never printed or written down.

    tools/release-check/run_with_deepseek.py <apk> [suites]     suites: e.g. "U S F ST P M N" (default: all)
"""
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AGENTWEB_DIR = os.environ.get("AGENTWEB_DIR", "/home/karewink/claude/workagent/agentweb")
SDK = os.environ.get("ANDROID_HOME", os.path.expanduser("~/.local/opt/android-sdk"))
TIMEOUT_S = int(os.environ.get("RELEASE_CHECK_TIMEOUT_S", "3600"))


def server_env():
    listing = subprocess.run(["ss", "-ltnp"], capture_output=True, text=True).stdout
    match = re.search(r":18791 .*?pid=(\d+)", listing)
    if not match:
        raise SystemExit("The AgentWeb server (port 18791) is not running: its keys are needed.")
    with open(f"/proc/{match.group(1)}/environ", "rb") as handle:
        pairs = (item.split(b"=", 1) for item in handle.read().split(b"\0") if b"=" in item)
        return {k.decode(): v.decode() for k, v in pairs}


def version_code(apk):
    tools = sorted(os.listdir(os.path.join(SDK, "build-tools")))
    badging = subprocess.run([os.path.join(SDK, "build-tools", tools[-1], "aapt2"), "dump", "badging", apk],
                             capture_output=True, text=True).stdout
    match = re.search(r"versionCode='(\d+)'", badging)
    if not match:
        raise SystemExit(f"Cannot read the versionCode of {apk}")
    return match.group(1)


def main(argv):
    if not argv:
        raise SystemExit(__doc__)
    apk = os.path.abspath(argv[0])
    suites = argv[1] if len(argv) > 1 else "U S F ST P M N"
    code = version_code(apk)
    live = server_env()
    sys.path.insert(0, AGENTWEB_DIR)
    import provider_env
    source = dict(os.environ)
    for name in ("DEEPSEEK_API_KEY", "TYPESAFE_API_KEY", "TYPESAFE_PROXY"):
        if live.get(name):
            source[name] = live[name]
    env = provider_env.claude_code_env("deepseek", "deepseek-flash[1m]", source=source)
    env.update({"JAVA_HOME": os.path.expanduser("~/.local/opt/jdk-21"), "ANDROID_HOME": SDK, "AGENTWEB_DIR": AGENTWEB_DIR})
    os.makedirs(os.path.join(ROOT, "release-reports"), exist_ok=True)
    log = os.path.join(ROOT, "release-reports", f"{code}-run.log")
    prompt = (
        f"Run the release checks of the AgentWeb Android app for the build {apk} (versionCode {code}). "
        f"Read docs/RELEASE_TESTS.md first and follow it exactly; its rules are binding. Suites to run: {suites}. "
        "Use tools/release-check/phone.py for every phone action and measurement. Do not change any source file. "
        f"Write the report to release-reports/{code}-<wifi|mobile>.md as the document describes, then print its path "
        "and a one-line summary.")
    started = time.time()
    with open(log, "w") as out:
        try:
            result = subprocess.run(
                ["claude", "--print", "--verbose", "--model", "deepseek-flash[1m]", "--permission-mode", "bypassPermissions",
                 "--output-format", "stream-json", "-p", prompt],
                cwd=ROOT, env=env, stdout=out, stderr=subprocess.STDOUT, timeout=TIMEOUT_S)
            status = result.returncode
        except subprocess.TimeoutExpired:
            status = "timeout"
    print(json.dumps({"build": code, "status": status, "minutes": round((time.time() - started) / 60, 1), "log": log}))


if __name__ == "__main__":
    main(sys.argv[1:])
