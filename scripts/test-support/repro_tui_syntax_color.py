#!/usr/bin/env python3
import os
import pty
import select
import signal
import struct
import sys
import termios
import fcntl
import time
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[2]
WORK_DIR = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT_DIR / "build" / "repro"
OUTPUT_FILE = Path(sys.argv[2]) if len(sys.argv) > 2 else WORK_DIR / "tui-output.txt"
SCENARIO = sys.argv[3] if len(sys.argv) > 3 else "default"
NORMALIZED_FILE = OUTPUT_FILE.with_suffix(".normalized.txt")
BAT_LOG_FILE = WORK_DIR / "bat-invocations.log"
HOME_DIR = WORK_DIR / "home"
CONFIG_DIR = HOME_DIR / ".config" / "ocp"
CACHE_DIR = HOME_DIR / ".config" / "ocp"
OPENCODE_DIR = HOME_DIR / ".config" / "opencode"
WORKSPACE_DIR = WORK_DIR / "workspace"
REPO_DIR = WORK_DIR / "zzz-fixture-repo"
FIXTURE_BIN = ROOT_DIR / "scripts" / "test-support" / "fixtures" / "bin"

for path in [CONFIG_DIR, CACHE_DIR, OPENCODE_DIR, WORKSPACE_DIR, REPO_DIR]:
    path.mkdir(parents=True, exist_ok=True)

if BAT_LOG_FILE.exists():
    BAT_LOG_FILE.unlink()

if SCENARIO == "deep-merge":
    parent_dir = REPO_DIR / "parent"
    child_dir = REPO_DIR / "child"
    parent_dir.mkdir(parents=True, exist_ok=True)
    child_dir.mkdir(parents=True, exist_ok=True)
    (REPO_DIR / "repository.json").write_text(
        '{"profiles":[{"name":"parent"},{"name":"child","extends_from":"parent"}]}'
    )
    (parent_dir / "opencode.json").write_text(
        '{"theme":"dark","parentOnly":1,"shared":{"fromParent":true}}'
    )
    (child_dir / "opencode.json").write_text(
        '{"theme":"light","childOnly":2,"shared":{"fromChild":true}}'
    )
else:
    profile_dir = REPO_DIR / "default"
    profile_dir.mkdir(parents=True, exist_ok=True)
    (REPO_DIR / "repository.json").write_text('{"profiles":[{"name":"default"}]}')
    if SCENARIO == "jsonc":
        (profile_dir / "opencode.jsonc").write_text('{"theme":"dark","nested":{"x":1}}')
    else:
        (profile_dir / "opencode.json").write_text('{"theme":"dark","nested":{"x":1}}')

(CONFIG_DIR / "config.json").write_text(
    '{"config":{"latestOcpVersion":"0.1.0","lastOcpVersionCheckEpochSeconds":4102444800},"repositories":[{"name":"zzz-fixture-repo","uri":null,"localPath":"'
    + str(REPO_DIR)
    + '"}]}'
)


env = os.environ.copy()
env["TERM"] = "xterm-256color"
env["PATH"] = str(FIXTURE_BIN) + os.pathsep + env.get("PATH", "")
env["OCP_BAT_PATH"] = os.environ.get("OCP_BAT_PATH", str(FIXTURE_BIN / "bat"))
env["OCP_BAT_LOG"] = str(BAT_LOG_FILE)
env["OCP_CONFIG_DIR"] = str(CONFIG_DIR)
env["OCP_CACHE_DIR"] = str(CACHE_DIR)
env["OCP_OPENCODE_CONFIG_DIR"] = str(OPENCODE_DIR)
env["OCP_WORKING_DIR"] = str(WORKSPACE_DIR)
argv = [
    str(ROOT_DIR / "scripts" / "test-support" / "run_native_repro.sh"),
    str(WORK_DIR),
]


def drain(fd, timeout):
    end = time.time() + timeout
    chunks = []
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.05)
        if fd not in r:
            continue
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            break
        if not chunk:
            break
        chunks.append(chunk)
    return b"".join(chunks).decode(errors="ignore")


def normalize_terminal_output(text, rows=40, cols=120):
    screen = [[" " for _ in range(cols)] for _ in range(rows)]
    row = 0
    col = 0
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "\x1b":
            if i + 1 < len(text) and text[i + 1] == "[":
                j = i + 2
                while j < len(text) and text[j] not in "ABCDEFGHJKSTfmhlpqruxyn":
                    j += 1
                if j >= len(text):
                    break
                params = text[i + 2 : j]
                final = text[j]
                if final in ("H", "f"):
                    parts = [p for p in params.split(";") if p]
                    row = max(
                        0, min(rows - 1, (int(parts[0]) if len(parts) > 0 else 1) - 1)
                    )
                    col = max(
                        0, min(cols - 1, (int(parts[1]) if len(parts) > 1 else 1) - 1)
                    )
                elif final == "A":
                    row = max(0, row - (int(params) if params.isdigit() else 1))
                elif final == "B":
                    row = min(rows - 1, row + (int(params) if params.isdigit() else 1))
                elif final == "C":
                    col = min(cols - 1, col + (int(params) if params.isdigit() else 1))
                elif final == "D":
                    col = max(0, col - (int(params) if params.isdigit() else 1))
                elif final == "J":
                    if params in ("2", ""):
                        screen = [[" " for _ in range(cols)] for _ in range(rows)]
                        row = 0
                        col = 0
                elif final == "K":
                    for c in range(col, cols):
                        screen[row][c] = " "
                i = j + 1
                continue
            else:
                i += 1
                continue
        if ch == "\r":
            col = 0
        elif ch == "\n":
            row = min(rows - 1, row + 1)
            col = 0
        elif ch >= " ":
            if 0 <= row < rows and 0 <= col < cols:
                screen[row][col] = ch
            col = min(cols - 1, col + 1)
        i += 1
    return "\n".join("".join(line).rstrip() for line in screen)


def send_key(fd, key_bytes, transcript, delay=0.5):
    os.write(fd, key_bytes)
    time.sleep(delay)
    return transcript + drain(fd, delay)


def wait_for_predicate(fd, transcript, predicate, timeout=6.0):
    end = time.time() + timeout
    current = transcript
    while time.time() < end:
        current += drain(fd, 0.2)
        normalized = normalize_terminal_output(current)
        if predicate(normalized):
            return current, normalized, True
    return current, normalize_terminal_output(current), False


pid, fd = pty.fork()
if pid == 0:
    os.chdir(ROOT_DIR)
    os.execvpe(argv[0], argv, env)

fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("hhhh", 40, 120, 0, 0))
os.kill(pid, signal.SIGWINCH)
transcript = drain(fd, 4.0)
normalized = normalize_terminal_output(transcript)

# wait until the main UI is visible
transcript, normalized, interactive_started = wait_for_predicate(
    fd,
    transcript,
    lambda n: (
        "Repositories / Profiles / Files" in n
        and "Ready. Select a node in the hierarchy." in n
    ),
    timeout=10.0,
)

# dismiss modal if present
if "OCP Notice" in normalized:
    transcript = send_key(fd, b"\x1b", transcript, 0.8)
    transcript, normalized, _ = wait_for_predicate(
        fd, transcript, lambda n: "OCP Notice" not in n, timeout=5.0
    )

transcript = send_key(fd, b"\x1b[C", transcript, 0.6)
transcript = send_key(fd, b"\x1b[B", transcript, 0.6)
normalized = normalize_terminal_output(transcript)
if "▶ 👤" in normalized:
    transcript = send_key(fd, b"\x1b[C", transcript, 0.6)
transcript = send_key(fd, b"\x1b[B", transcript, 0.6)
if SCENARIO == "deep-merge":
    transcript = send_key(fd, b"\x1b[A", transcript, 0.6)
normalized = normalize_terminal_output(transcript)

# try opening selected node and shift focus to detail pane
transcript = send_key(fd, b"\r", transcript, 0.8)
transcript = send_key(fd, b"\t", transcript, 0.8)
transcript, normalized, _ = wait_for_predicate(
    fd,
    transcript,
    lambda n: (
        "Preview:" in n
        or "opencode.json" in n
        or "opencode.jsonc" in n
        or "theme" in n
        or "nested" in n
        or "deep-merged" in n
        or "parentOnly" in n
        or "childOnly" in n
    ),
    timeout=5.0,
)

transcript, normalized, _ = wait_for_predicate(
    fd,
    transcript,
    lambda n: "BAT_FIXTURE_HIT" in n,
    timeout=4.0,
)

transcript = send_key(fd, b"\x1b[A", transcript, 0.6)
transcript = send_key(fd, b"\x1b[B", transcript, 0.6)
transcript, normalized, _ = wait_for_predicate(
    fd,
    transcript,
    lambda n: (
        "BAT_FIXTURE_HIT" in n
        or ("parentOnly" in n and "childOnly" in n)
        or ("theme" in n and "nested" in n)
    ),
    timeout=4.0,
)

if SCENARIO == "deep-merge":
    transcript, normalized, _ = wait_for_predicate(
        fd,
        transcript,
        lambda n: "parentOnly" in n and "childOnly" in n,
        timeout=8.0,
    )

transcript = send_key(fd, b"q", transcript, 0.5)

try:
    os.kill(pid, signal.SIGTERM)
except OSError:
    pass

normalized = normalize_terminal_output(transcript)
OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
OUTPUT_FILE.write_text(transcript)
NORMALIZED_FILE.write_text(normalized)
if BAT_LOG_FILE.exists():
    print(f"BAT_INVOCATIONS_LOG={BAT_LOG_FILE}")
preview_text_found = any(
    token in normalized
    for token in ["Preview:", "opencode.json", "opencode.jsonc", "theme", "nested"]
)
fixture_color_found = "\x1b[31m" in transcript or "\x1b[0;31m" in transcript
fixture_text_found = "BAT_FIXTURE_HIT" in normalized
deep_merge_label_found = "(deep-merged)" in normalized
merged_content_found = "parentOnly" in normalized and "childOnly" in normalized
reselect_content_found = (
    "BAT_FIXTURE_HIT" in normalized
    or ("parentOnly" in normalized and "childOnly" in normalized)
    or ("theme" in normalized and "nested" in normalized)
)
print(f"INTERACTIVE_STARTED={interactive_started}")
print(f"PREVIEW_TEXT_FOUND={preview_text_found}")
print(f"FIXTURE_COLOR_FOUND={fixture_color_found}")
print(f"FIXTURE_TEXT_FOUND={fixture_text_found}")
print(f"DEEP_MERGE_LABEL_FOUND={deep_merge_label_found}")
print(f"MERGED_CONTENT_FOUND={merged_content_found}")
print(f"RESELECT_CONTENT_FOUND={reselect_content_found}")
