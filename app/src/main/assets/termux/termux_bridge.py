#!/usr/bin/env python3
"""Zero Assist Termux bridge.

This bridge is intentionally stdlib-only so it can run in a fresh Termux
Python install. It exposes a small token-protected loopback HTTP API for
health, capability discovery, and bounded argv-based command execution.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


BRIDGE_VERSION = "0.1.0"
TOKEN_HEADER = "X-Zero-Assist-Termux-Token"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8787
DEFAULT_TIMEOUT_SECONDS = 30
MAX_TIMEOUT_SECONDS = 120
DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024
MAX_REQUEST_BYTES = 256 * 1024
TERMUX_HOME = Path(os.environ.get("HOME", "/data/data/com.termux/files/home")).resolve()
TERMUX_USR = Path("/data/data/com.termux/files/usr").resolve()
ZERO_ASSIST_ROOT = TERMUX_HOME / ".zero-assist"
ZERO_ASSIST_WORKSPACE = ZERO_ASSIST_ROOT / "workspace"
LOW_RISK_COMMANDS = {"date", "false", "id", "pwd", "true", "uname", "whoami"}
PYTHON_VERSION_ARGS = {("--version",), ("-V",)}


class BridgeState:
    def __init__(self, token: str) -> None:
        self.token = token
        self.started_at = time.time()
        self.executions: dict[str, dict[str, Any]] = {}


STATE: BridgeState | None = None


def ensure_workspace() -> None:
    ZERO_ASSIST_ROOT.mkdir(mode=0o700, exist_ok=True)
    ZERO_ASSIST_WORKSPACE.mkdir(mode=0o700, exist_ok=True)


def json_response(
    handler: BaseHTTPRequestHandler,
    status: HTTPStatus,
    payload: dict[str, Any],
) -> None:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    handler.send_response(status.value)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(body)


def error_response(
    handler: BaseHTTPRequestHandler,
    status: HTTPStatus,
    message: str,
) -> None:
    json_response(handler, status, {"success": False, "error": {"message": message}})


def is_at_or_inside(path: Path, prefix: Path) -> bool:
    try:
        path.relative_to(prefix)
        return True
    except ValueError:
        return path == prefix


def resolve_cwd(raw_cwd: Any, allow_outside_termux_roots: bool = False) -> Path:
    if raw_cwd is None or str(raw_cwd).strip() == "":
        return ZERO_ASSIST_WORKSPACE
    candidate = Path(str(raw_cwd)).expanduser().resolve()
    if allow_outside_termux_roots:
        return candidate
    if is_at_or_inside(candidate, TERMUX_HOME) or is_at_or_inside(candidate, TERMUX_USR):
        return candidate
    raise ValueError("working_directory must stay inside the Termux home or usr directories")


def command_name(argv0: str) -> str:
    return Path(argv0).name.lower()


def contains_shell_fragment(value: str) -> bool:
    fragments = ("&&", "||", ";", "`", "$(", "| sh", "| bash", ">/", "</")
    lowered = value.lower()
    return any(fragment in lowered for fragment in fragments)


def validate_low_risk_argv(argv: list[str]) -> None:
    if not argv:
        raise ValueError("argv must contain at least one command")
    if any(not isinstance(item, str) or item.strip() == "" for item in argv):
        raise ValueError("argv must contain only non-empty strings")
    if any(contains_shell_fragment(item) for item in argv):
        raise ValueError("shell chaining, redirection, and command substitution require user approval")

    name = command_name(argv[0])
    args = tuple(argv[1:])
    if name in {"python", "python3"} and args in PYTHON_VERSION_ARGS:
        return
    if name == "uname" and args in {(), ("-a",), ("-m",), ("-r",), ("-s",)}:
        return
    if name in LOW_RISK_COMMANDS and args == ():
        return
    raise ValueError("only bounded low-risk diagnostic commands run without user approval")


def approval_fingerprint(argv: list[str], cwd: Path) -> str:
    canonical = (
        "termux-v1\nargv="
        + json.dumps(argv, ensure_ascii=False, separators=(",", ":"))
        + "\nworking_directory="
        + json.dumps(str(cwd), ensure_ascii=False, separators=(",", ":"))
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def validate_approved_argv(argv: list[str], cwd: Path, approval: Any) -> None:
    if not isinstance(approval, dict) or approval.get("approved") is not True:
        validate_low_risk_argv(argv)
        return
    if not argv:
        raise ValueError("argv must contain at least one command")
    if any(not isinstance(item, str) or item.strip() == "" for item in argv):
        raise ValueError("argv must contain only non-empty strings")
    expected = approval_fingerprint(argv, cwd)
    actual = str(approval.get("fingerprint") or "").strip().lower()
    if actual != expected:
        raise ValueError("approval fingerprint does not match this command, arguments, and working directory")


def truncate_bytes(data: bytes, max_bytes: int) -> tuple[str, bool]:
    truncated = len(data) > max_bytes
    visible = data[:max_bytes]
    return visible.decode("utf-8", errors="replace"), truncated


def command_version(command: str) -> str | None:
    path = shutil.which(command)
    if not path:
        return None
    version_args = ["--version"]
    if command in {"python", "python3"}:
        version_args = ["--version"]
    try:
        result = subprocess.run(
            [path, *version_args],
            capture_output=True,
            timeout=3,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    text = (result.stdout or result.stderr).decode("utf-8", errors="replace").strip()
    return text.splitlines()[0] if text else None


def discover_proot() -> dict[str, Any]:
    path = shutil.which("proot-distro")
    if not path:
        return {"available": False, "distros": [], "active_distro": None}
    distros: list[str] = []
    try:
        result = subprocess.run(
            [path, "list"],
            capture_output=True,
            timeout=5,
            check=False,
        )
        output = result.stdout.decode("utf-8", errors="replace")
        for line in output.splitlines():
            cleaned = line.strip().lstrip("*").strip()
            if cleaned and not cleaned.lower().startswith(("available", "installed")):
                distros.append(cleaned.split()[0])
    except (OSError, subprocess.SubprocessError):
        pass
    return {"available": True, "distros": sorted(set(distros)), "active_distro": None}


def capabilities_payload() -> dict[str, Any]:
    commands = {}
    for command in ["python", "python3", "git", "node", "npm", "pkg", "proot-distro"]:
        path = shutil.which(command)
        commands[command] = {
            "available": path is not None,
            "path": path,
            "version": command_version(command) if path else None,
        }

    return {
        "success": True,
        "bridge": {
            "version": BRIDGE_VERSION,
            "token_header": TOKEN_HEADER,
            "supports": ["health", "capabilities", "execute"],
        },
        "workspace": {
            "root": str(ZERO_ASSIST_WORKSPACE),
            "termux_home": str(TERMUX_HOME),
            "termux_usr": str(TERMUX_USR),
        },
        "commands": commands,
        "python": {
            "available": shutil.which("python3") is not None or shutil.which("python") is not None,
            "version": sys.version.split()[0],
            "executable": sys.executable,
        },
        "proot": discover_proot(),
        "limits": {
            "approval_required": True,
            "timeout_seconds": DEFAULT_TIMEOUT_SECONDS,
            "max_timeout_seconds": MAX_TIMEOUT_SECONDS,
            "max_output_bytes": DEFAULT_MAX_OUTPUT_BYTES,
            "execution_mode": "argv_only_low_risk_direct_or_user_approved",
        },
    }


def health_payload() -> dict[str, Any]:
    proot = discover_proot()
    return {
        "ready": True,
        "status": "ready",
        "reason": "Zero Assist Termux bridge is ready.",
        "version": BRIDGE_VERSION,
        "uptime_seconds": int(time.time() - (STATE.started_at if STATE else time.time())),
        "workspace": str(ZERO_ASSIST_WORKSPACE),
        "python": {
            "version": sys.version.split()[0],
            "executable": sys.executable,
        },
        "proot": proot,
    }


def execute_payload(payload: dict[str, Any]) -> dict[str, Any]:
    argv = payload.get("argv")
    if not isinstance(argv, list):
        raise ValueError("argv must be an array")
    argv = [str(item).strip() for item in argv]
    approval = payload.get("approval")
    approved = isinstance(approval, dict) and approval.get("approved") is True
    cwd = resolve_cwd(
        payload.get("working_directory") or payload.get("cwd"),
        allow_outside_termux_roots=approved,
    )
    validate_approved_argv(argv, cwd, approval)
    cwd.mkdir(mode=0o700, parents=True, exist_ok=True)

    timeout = int(payload.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)
    timeout = max(1, min(timeout, MAX_TIMEOUT_SECONDS))
    max_output = int(payload.get("max_output_bytes") or DEFAULT_MAX_OUTPUT_BYTES)
    max_output = max(1024, min(max_output, DEFAULT_MAX_OUTPUT_BYTES))
    execution_id = f"zatx_{uuid.uuid4().hex}"
    started_at = time.time()
    try:
        result = subprocess.run(
            argv,
            cwd=str(cwd),
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        duration_ms = int((time.time() - started_at) * 1000)
        stdout, stdout_truncated = truncate_bytes(result.stdout, max_output)
        stderr, stderr_truncated = truncate_bytes(result.stderr, max_output)
        response = {
            "success": result.returncode == 0,
            "id": execution_id,
            "status": "completed" if result.returncode == 0 else "failed",
            "argv": argv,
            "working_directory": str(cwd),
            "exit_code": result.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "duration_ms": duration_ms,
            "truncated": stdout_truncated or stderr_truncated,
        }
    except subprocess.TimeoutExpired as error:
        duration_ms = int((time.time() - started_at) * 1000)
        stdout, stdout_truncated = truncate_bytes(error.stdout or b"", max_output)
        stderr, stderr_truncated = truncate_bytes(error.stderr or b"", max_output)
        response = {
            "success": False,
            "id": execution_id,
            "status": "timed_out",
            "argv": argv,
            "working_directory": str(cwd),
            "exit_code": None,
            "stdout": stdout,
            "stderr": stderr,
            "duration_ms": duration_ms,
            "truncated": stdout_truncated or stderr_truncated,
            "error": {"message": f"Command exceeded timeout of {timeout}s."},
        }

    if STATE is not None:
        STATE.executions[execution_id] = response
    return response


def execute_stream_payload(payload: dict[str, Any]) -> None:
    """Execute a command and stream output as JSON lines.

    Each line is a JSON object with keys: chunk_type, data, done.
    The last line has done=true.
    """
    argv = payload.get("argv")
    if not isinstance(argv, list):
        raise ValueError("argv must be an array")
    argv = [str(item).strip() for item in argv]
    approval = payload.get("approval")
    approved = isinstance(approval, dict) and approval.get("approved") is True
    cwd = resolve_cwd(
        payload.get("working_directory") or payload.get("cwd"),
        allow_outside_termux_roots=approved,
    )
    validate_approved_argv(argv, cwd, approval)
    cwd.mkdir(mode=0o700, parents=True, exist_ok=True)
    timeout = int(payload.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)
    timeout = max(1, min(timeout, MAX_TIMEOUT_SECONDS))
    max_output = int(payload.get("max_output_bytes") or DEFAULT_MAX_OUTPUT_BYTES)
    max_output = max(1024, min(max_output, DEFAULT_MAX_OUTPUT_BYTES))
    execution_id = f"zatx_{uuid.uuid4().hex}"
    started_at = time.time()

    proc: subprocess.Popen[bytes] | None = None
    try:
        proc = subprocess.Popen(
            argv,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        stdout_buf = bytearray()
        stderr_buf = bytearray()
        stdout_limit_reached = False
        stderr_limit_reached = False

        while True:
            if proc.stdout is not None:
                chunk = proc.stdout.read(4096)
                if chunk:
                    stdout_buf.extend(chunk)
                    if len(stdout_buf) <= max_output:
                        sys.stdout.write(json.dumps({"chunk_type": "stdout", "data": chunk.decode("utf-8", errors="replace"), "done": False}) + "\n")
                        sys.stdout.flush()
                    elif not stdout_limit_reached:
                        stdout_limit_reached = True
            if proc.stderr is not None:
                chunk = proc.stderr.read(4096)
                if chunk:
                    stderr_buf.extend(chunk)
                    if len(stderr_buf) <= max_output:
                        sys.stdout.write(json.dumps({"chunk_type": "stderr", "data": chunk.decode("utf-8", errors="replace"), "done": False}) + "\n")
                        sys.stdout.flush()
                    elif not stderr_limit_reached:
                        stderr_limit_reached = True
            if proc.poll() is not None:
                break
            if time.time() - started_at > timeout:
                proc.kill()
                break

        duration_ms = int((time.time() - started_at) * 1000)
        exit_code = proc.returncode
        final_stdout = stdout_buf[:max_output].decode("utf-8", errors="replace")
        final_stderr = stderr_buf[:max_output].decode("utf-8", errors="replace")
        sys.stdout.write(json.dumps({
            "chunk_type": "result",
            "data": "",
            "done": True,
            "execution_id": execution_id,
            "success": exit_code == 0,
            "exit_code": exit_code,
            "stdout": final_stdout,
            "stderr": final_stderr,
            "duration_ms": duration_ms,
        }) + "\n")
        sys.stdout.flush()
    except Exception as error:
        duration_ms = int((time.time() - started_at) * 1000)
        sys.stdout.write(json.dumps({
            "chunk_type": "error",
            "data": "",
            "done": True,
            "execution_id": execution_id,
            "success": False,
            "error": str(error),
            "duration_ms": duration_ms,
        }) + "\n")
        sys.stdout.flush()


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "ZeroAssistTermuxBridge/0.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def authenticate(self) -> bool:
        expected = STATE.token if STATE else ""
        actual = self.headers.get(TOKEN_HEADER, "")
        if not expected or actual != expected:
            error_response(self, HTTPStatus.UNAUTHORIZED, "Invalid or missing Termux bridge token.")
            return False
        return True

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if not self.authenticate():
            return
        if self.path == "/health":
            json_response(self, HTTPStatus.OK, health_payload())
            return
        if self.path == "/capabilities":
            json_response(self, HTTPStatus.OK, capabilities_payload())
            return
        if self.path.startswith("/executions/"):
            execution_id = self.path.rsplit("/", 1)[-1]
            record = STATE.executions.get(execution_id) if STATE else None
            if record is None:
                error_response(self, HTTPStatus.NOT_FOUND, "Execution id not found.")
            else:
                json_response(self, HTTPStatus.OK, record)
            return
        error_response(self, HTTPStatus.NOT_FOUND, "Unknown endpoint.")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if not self.authenticate():
            return
        if self.path == "/execute":
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0 or length > MAX_REQUEST_BYTES:
                error_response(self, HTTPStatus.BAD_REQUEST, "Invalid request body size.")
                return
            try:
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("request body must be a JSON object")
                result = execute_payload(payload)
            except (json.JSONDecodeError, ValueError, OSError) as error:
                error_response(self, HTTPStatus.BAD_REQUEST, str(error))
                return
            json_response(self, HTTPStatus.OK, result)
            return
        if self.path == "/execute_stream":
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0 or length > MAX_REQUEST_BYTES:
                error_response(self, HTTPStatus.BAD_REQUEST, "Invalid request body size.")
                return
            try:
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("request body must be a JSON object")
            except (json.JSONDecodeError, ValueError) as error:
                error_response(self, HTTPStatus.BAD_REQUEST, str(error))
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            try:
                execute_stream_payload(payload)
            except Exception as error:
                self.wfile.write((json.dumps({"chunk_type": "error", "data": "", "done": True, "error": str(error)}) + "\n").encode("utf-8"))
                self.wfile.flush()
            return
        if self.path.startswith("/cancel/"):
            error_response(self, HTTPStatus.NOT_IMPLEMENTED, "Cancellation is not available for synchronous low-risk commands yet.")
            return
        error_response(self, HTTPStatus.NOT_FOUND, "Unknown endpoint.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Zero Assist Termux bridge")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--token", required=True)
    args = parser.parse_args()

    if args.host not in {"127.0.0.1", "localhost"}:
        print("Termux bridge refuses non-loopback hosts.", file=sys.stderr)
        return 2
    if args.port < 1 or args.port > 65535:
        print("Termux bridge port must be between 1 and 65535.", file=sys.stderr)
        return 2
    if not args.token.strip():
        print("Termux bridge token is required.", file=sys.stderr)
        return 2

    ensure_workspace()
    global STATE
    STATE = BridgeState(args.token.strip())
    server = ThreadingHTTPServer((args.host, args.port), BridgeHandler)
    print(f"Zero Assist Termux bridge {BRIDGE_VERSION} listening on {args.host}:{args.port}")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
