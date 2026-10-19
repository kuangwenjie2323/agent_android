#!/usr/bin/env python3
"""Drive and measure the AgentWeb app on the USB phone for release checks (see docs/RELEASE_TESTS.md).

Every command prints one JSON line, so a test runner (a person or a model) can read results without parsing
screenshots. Elements are found by their visible text or accessibility label, never by fixed coordinates.

    phone.py info                       device, build installed, network (Wi-Fi / mobile / VPN)
    phone.py install <apk>              install over the current build (keeps data)
    phone.py launch                     bring the app to the front
    phone.py home                       go to the home screen (the app goes to the background)
    phone.py find <text>                elements whose text or label contains <text>
    phone.py tap <text> [--long]        tap (or long-press) the first element containing <text>
    phone.py wait <text> [seconds]      wait until an element containing <text> shows (default 20 s)
    phone.py gone <text> [seconds]      wait until no element contains <text>
    phone.py type <ascii text>          type into the focused field (switch the keyboard to QWERTY first)
    phone.py scroll up|down [times]     swipe the middle of the screen
    phone.py shot <path.png>            screenshot
    phone.py startup [runs]             cold-start time in ms (am start -W), median of runs (default 5)
    phone.py memory                     PSS and heaps in MB (dumpsys meminfo)
    phone.py frames reset|report        frame statistics (dumpsys gfxinfo): janky %, p90/p99 ms
    phone.py crashes <since-epoch-s>    crashes and ANRs of the app since then (logcat crash buffer, app log)
    phone.py net <since-epoch-s>        request timings, stream reconnects and errors from the app's own log
    phone.py see <statement>            Jev vision: how likely the screen shows <statement> (looks, colours, layout)
    phone.py locate <target> [--tap]    Jev vision: where <target> is (for things with no text or label)

The two Jev commands need TYPESAFE_API_KEY (and TYPESAFE_PROXY in mainland China) and import AgentWeb's client
from $AGENTWEB_DIR (default: the live checkout). Prefer tap/find: the screen's own labels are exact and free.
"""
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.karewinkcloud.agentweb.client"
ACTIVITY = PACKAGE + "/.MainActivity"
APP_LOG = "no_backup/diagnostics/current.jsonl"


def adb(*args, timeout=60, check=False):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise SystemExit(json.dumps({"ok": False, "error": (result.stderr or result.stdout).strip()[:300]}))
    return result.stdout


def out(**values):
    print(json.dumps(values, ensure_ascii=False))


def elements():
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    xml = xml[xml.find("<?xml"):xml.rfind(">") + 1] if "<?xml" in xml else xml
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    found = []
    for node in root.iter("node"):
        label = node.get("text") or node.get("content-desc") or ""
        bounds = re.findall(r"\d+", node.get("bounds", ""))
        if label and len(bounds) == 4:
            x1, y1, x2, y2 = map(int, bounds)
            found.append({"text": label, "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                          "enabled": node.get("enabled") == "true"})
    return found


def matching(text):
    # An element whose whole text is the label comes first (a dialog's 删除 button before its 删除这个对话？ title);
    # otherwise screen order.
    return sorted((e for e in elements() if text in e["text"]), key=lambda e: e["text"] != text)


def info():
    code = re.search(r"versionCode=(\d+)", adb("shell", "dumpsys", "package", PACKAGE))
    wifi = "enabled" in adb("shell", "dumpsys wifi | grep -m1 'Wi-Fi is'")
    connectivity = adb("shell", "dumpsys connectivity | grep -E 'NetworkAgentInfo' | head -20")
    out(ok=True, device=adb("shell", "getprop", "ro.product.model").strip(),
        android=adb("shell", "getprop", "ro.build.version.release").strip(),
        versionCode=int(code.group(1)) if code else None, wifi=wifi,
        mobile="MOBILE" in connectivity, vpn="VPN CONNECTED" in connectivity)


def tap(text, long=False):
    hits = matching(text)
    if not hits:
        return out(ok=False, error=f"no element contains {text!r}")
    e = hits[0]
    if long:
        adb("shell", "input", "swipe", str(e["x"]), str(e["y"]), str(e["x"]), str(e["y"]), "900")
    else:
        adb("shell", "input", "tap", str(e["x"]), str(e["y"]))
    out(ok=True, tapped=e["text"][:80], enabled=e["enabled"])


def wait(text, seconds=20.0, present=True):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if bool(matching(text)) == present:
            return out(ok=True, waited=round(seconds - (deadline - time.time()), 1))
        time.sleep(0.7)
    out(ok=False, error=f"{text!r} {'never showed' if present else 'still shows'} after {seconds} s")


def startup(runs=5):
    times = []
    for _ in range(runs):
        adb("shell", "am", "force-stop", PACKAGE)
        time.sleep(1.5)
        text = adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        match = re.search(r"TotalTime: (\d+)", text)
        if match:
            times.append(int(match.group(1)))
        time.sleep(3)
    out(ok=bool(times), runs=times, medianMs=statistics.median(times) if times else None)


def memory():
    text = adb("shell", "dumpsys", "meminfo", PACKAGE)
    def row(name):
        match = re.search(rf"^\s*{name}:?\s+(\d+)", text, re.M)
        return round(int(match.group(1)) / 1024, 1) if match else None
    total = re.search(r"TOTAL PSS:\s+(\d+)", text) or re.search(r"^\s*TOTAL\s+(\d+)", text, re.M)
    out(ok=total is not None, pssMb=round(int(total.group(1)) / 1024, 1) if total else None,
        javaHeapMb=row("Java Heap"), nativeHeapMb=row("Native Heap"), graphicsMb=row("Graphics"))


def frames(action):
    if action == "reset":
        adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
        return out(ok=True)
    text = adb("shell", "dumpsys", "gfxinfo", PACKAGE)
    def number(pattern):
        match = re.search(pattern, text)
        return float(match.group(1)) if match else None
    out(ok=True, frames=number(r"Total frames rendered: (\d+)"), jankyPercent=number(r"Janky frames: \d+ \(([\d.]+)%\)"),
        p90Ms=number(r"90th percentile: (\d+)ms"), p99Ms=number(r"99th percentile: (\d+)ms"))


def app_log(since):
    lines = adb("shell", "run-as", PACKAGE, "cat", APP_LOG)
    rows = []
    for line in lines.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get("t", 0) / 1000 >= since:
            rows.append(row)
    return rows


def crashes(since):
    stamp = time.strftime("%m-%d %H:%M:%S.000", time.localtime(since))
    crash = [l for l in adb("logcat", "-d", "-b", "crash", "-T", stamp).splitlines() if PACKAGE in l]
    anr = [l for l in adb("logcat", "-d", "-b", "events", "-T", stamp).splitlines() if "am_anr" in l and PACKAGE in l]
    own = [r for r in app_log(since) if r.get("tag") in ("crash", "exit") and r.get("l") in ("W", "E")]
    out(ok=not crash and not anr and not own, crashLines=crash[:5], anrs=anr[:5], appLog=own[:5])


def net(since):
    rows = app_log(since)
    times, failures, reconnects, errors, retried = [], [], 0, [], 0
    for r in rows:
        m = r.get("m", "")
        if r.get("tag") == "net" and isinstance(r.get("ms"), (int, float)) and " 200" in m:
            times.append(r["ms"])
        if r.get("tag") == "net" and r.get("avgMs") is not None:  # "GET ok x27": 27 reads summed up
            count = re.search(r"x(\d+)", m)
            times.extend([r["avgMs"]] * (int(count.group(1)) if count else 1))
        if r.get("tag") == "net" and r.get("l") == "W":
            failures.append(m[:90])
        if r.get("tag") == "http" and m.endswith(" retried"):  # failed before it was sent, tried once more
            retried += 1
        if r.get("tag") == "stream" and m == "reconnecting":
            reconnects += 1
        if r.get("tag") == "ui" and m == "error shown":
            errors.append(r.get("code") or r.get("error", "")[:80])
    times.sort()
    out(ok=True, requests=len(times), avgMs=round(statistics.mean(times)) if times else None,
        p95Ms=times[int(len(times) * .95) - 1] if len(times) >= 20 else (times[-1] if times else None),
        failures=failures[:10], failureCount=len(failures), retried=retried, streamReconnects=reconnects, errorsShown=errors)


def jev_screen():
    """A screenshot scaled to what Jev takes (at most 1568 px a side) as a request's image, and the full size."""
    sys.path.insert(0, os.environ.get("AGENTWEB_DIR", "/home/karewink/claude/workagent/agentweb"))
    import typesafe_client
    from PIL import Image
    folder = tempfile.mkdtemp(prefix="phone-jev-")
    full, small = os.path.join(folder, "full.png"), os.path.join(folder, "screen.png")
    with open(full, "wb") as handle:
        handle.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    with Image.open(full) as image:
        size = image.size
        scale = min(1.0, 1568 / max(size))
        image.convert("RGB").resize((round(size[0] * scale), round(size[1] * scale))).save(small, "PNG")
    images = typesafe_client.Images()
    return typesafe_client, images, images.add(small, "screen"), size


def see(statement):
    jev, images, screen, _ = jev_screen()
    try:
        result = jev.ask({"screen": screen}, {"seen": jev.noul(f"In the phone screenshot `screen`: {statement}")}, images=images)
    except jev.TypeSafeError as error:
        return out(ok=False, error=str(error))
    out(ok=True, statement=statement, answer=result["answers"]["seen"], model=result.get("model"))


def locate(target, tap_it=False):
    jev, images, screen, (width, height) = jev_screen()
    try:
        result = jev.ask({"screen": screen}, {
            "shown": jev.noul(f"In the phone screenshot `screen`, {target} is visible"),
            "where": jev.box(f"{target} in `screen`")}, images=images)
    except jev.TypeSafeError as error:
        return out(ok=False, error=str(error))
    where = result["answers"]["where"]
    box = where.get("box") or {}
    if not box:
        return out(ok=False, error="no box in the answer", answer=where)
    x = round((box["left"] + box["right"]) / 2 * width)
    y = round((box["top"] + box["bottom"]) / 2 * height)
    if tap_it:
        adb("shell", "input", "tap", str(x), str(y))
    out(ok=True, target=target, x=x, y=y, confidence=where.get("confidence"), shown=result["answers"]["shown"], tapped=tap_it)


def main(argv):
    if not argv:
        return print(__doc__)
    command, rest = argv[0], argv[1:]
    if command == "info": return info()
    if command == "install":
        result = adb("install", "-r", rest[0], timeout=240)
        return out(ok="Success" in result, result=result.strip()[-200:])
    if command == "launch":
        adb("shell", "am", "start", "-n", ACTIVITY); return out(ok=True)
    if command == "home":
        adb("shell", "input", "keyevent", "KEYCODE_HOME"); return out(ok=True)
    if command == "find": return out(ok=True, elements=matching(rest[0])[:10])
    if command == "tap": return tap(rest[0], "--long" in rest)
    if command == "wait": return wait(rest[0], float(rest[1]) if len(rest) > 1 else 20)
    if command == "gone": return wait(rest[0], float(rest[1]) if len(rest) > 1 else 20, present=False)
    if command == "type":
        adb("shell", "input", "text", rest[0].replace(" ", "%s")); return out(ok=True)
    if command == "scroll":
        times = int(rest[1]) if len(rest) > 1 else 1
        for _ in range(times):
            ys = ("700", "2000") if rest[0] == "up" else ("2000", "700")
            adb("shell", "input", "swipe", "600", ys[0], "600", ys[1], "350"); time.sleep(0.6)
        return out(ok=True)
    if command == "shot":
        with open(rest[0], "wb") as handle:
            handle.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
        return out(ok=True, path=rest[0])
    if command == "startup": return startup(int(rest[0]) if rest else 5)
    if command == "memory": return memory()
    if command == "frames": return frames(rest[0])
    if command == "crashes": return crashes(float(rest[0]))
    if command == "net": return net(float(rest[0]))
    if command == "see": return see(rest[0])
    if command == "locate": return locate(rest[0], "--tap" in rest)
    out(ok=False, error=f"unknown command {command!r}")


if __name__ == "__main__":
    main(sys.argv[1:])
