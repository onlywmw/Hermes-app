"""Client for the Hermes Android bridge.

Supports both Unix domain sockets (filesystem or abstract) and localhost TCP.
The bridge address is read from the HERMES_ANDROID_BRIDGE_SOCKET environment
variable, defaulting to a local TCP connection.
"""

import json
import logging
import os
import socket
import threading
import time
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

DEFAULT_SOCKET_PATH = "tcp:127.0.0.1:18081"


def get_socket_path() -> str:
    return os.environ.get("HERMES_ANDROID_BRIDGE_SOCKET") or DEFAULT_SOCKET_PATH


class AndroidBridgeClient:
    """JSON-RPC client to the Android app's Hermes bridge."""

    def __init__(self, socket_path: Optional[str] = None):
        self.socket_path = socket_path or get_socket_path()
        self._lock = threading.Lock()
        self._sock: Optional[socket.socket] = None
        self._next_id = 1

    def _connect(self) -> socket.socket:
        path = self.socket_path
        if path.startswith("tcp:"):
            # tcp:host:port
            _, host, port = path.split(":")
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(30)
            sock.connect((host, int(port)))
        elif path.startswith("@"):
            # Android abstract namespace socket
            sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            sock.settimeout(30)
            sock.connect("\0" + path[1:])
        else:
            # Filesystem Unix domain socket
            sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            sock.settimeout(30)
            sock.connect(path)
        return sock

    def _ensure_connected(self) -> socket.socket:
        if self._sock is None:
            self._sock = self._connect()
        return self._sock

    def call(self, method: str, params: Optional[Dict[str, Any]] = None, timeout: float = 30.0) -> Dict[str, Any]:
        with self._lock:
            request_id = self._next_id
            self._next_id += 1
            payload = {
                "jsonrpc": "2.0",
                "method": method,
                "params": params or {},
                "id": request_id,
            }
            message = json.dumps(payload) + "\n"
            logger.debug("Bridge request: %s", message.strip())

            last_error = None
            for attempt in range(3):
                try:
                    sock = self._ensure_connected()
                    sock.settimeout(timeout)
                    sock.sendall(message.encode("utf-8"))
                    response = self._read_line(sock)
                    logger.debug("Bridge response: %s", response.strip() if response else "(empty)")
                    if not response:
                        raise ConnectionError("Empty response from bridge")
                    data = json.loads(response)
                    if "error" in data:
                        raise RuntimeError(data["error"].get("message", "Unknown bridge error"))
                    return data.get("result", {})
                except (socket.error, OSError, TimeoutError) as e:
                    last_error = e
                    logger.warning("Bridge call failed (attempt %d): %s", attempt + 1, e)
                    self._sock = None
                    time.sleep(0.2 * (attempt + 1))
            raise ConnectionError(f"Android bridge unreachable after retries: {last_error}")

    def _read_line(self, sock: socket.socket) -> str:
        buffer = bytearray()
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buffer.extend(chunk)
            if b"\n" in chunk:
                break
        return buffer.decode("utf-8")


# Module-level singleton for reuse across tool calls.
_bridge_client: Optional[AndroidBridgeClient] = None
_bridge_lock = threading.Lock()


def get_bridge_client() -> AndroidBridgeClient:
    global _bridge_client
    with _bridge_lock:
        if _bridge_client is None:
            _bridge_client = AndroidBridgeClient()
        return _bridge_client
