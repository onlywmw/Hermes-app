"""Hermes plugin that exposes Android capabilities via the Android bridge socket."""

import logging
from typing import Any, Dict

from hermes_cli.plugins import PluginContext
from tools import registry
from toolsets import TOOLSETS

from .client import get_bridge_client

logger = logging.getLogger(__name__)

TOOLSET_NAME = "android_control"


def _register_toolset() -> None:
    """Add the android_control toolset to Hermes if not already present."""
    if TOOLSET_NAME not in TOOLSETS:
        TOOLSETS[TOOLSET_NAME] = {
            "description": "Android system control tools: clipboard, notifications, sensors, calls, root, etc.",
            "tools": [],
            "includes": [],
        }


def _check_bridge_available() -> bool:
    try:
        client = get_bridge_client()
        client.call("ping", timeout=2.0)
        return True
    except Exception as e:
        logger.debug("Android bridge not available: %s", e)
        return False


def _clipboard_read(**_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("clipboard_read")


def _clipboard_write(text: str, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("clipboard_write", {"text": text})


def _notification_show(title: str, message: str, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("notification_show", {"title": title, "message": message})


def _device_info(**_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("device_info")


def _vibrate(duration: int = 200, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("vibrate", {"duration": duration})


def _torch(on: bool = True, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("torch", {"on": on})


def _battery_status(**_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("battery_status")


def _shell(command: str, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("shell", {"command": command})


def _root_shell(command: str, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("root_shell", {"command": command})


def _accessibility_dump(**_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("accessibility_dump")


def _accessibility_click(text: str, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("accessibility_click", {"text": text})


def _accessibility_input(text: str, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("accessibility_input", {"text": text})


def _device_admin_lock(**_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("device_admin_lock")


def _device_admin_wipe(external: bool = False, **_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("device_admin_wipe", {"external": external})


def _location_get(**_kwargs: Any) -> Dict[str, Any]:
    return get_bridge_client().call("location_get")


def register(ctx: PluginContext) -> None:
    """Plugin entry point called by Hermes during plugin discovery."""
    _register_toolset()

    registry.register(
        name="android_clipboard_read",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _clipboard_read(**args),
        check_fn=_check_bridge_available,
        description="Read the current text from the Android clipboard.",
        emoji="📋",
    )

    registry.register(
        name="android_clipboard_write",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to write to the Android clipboard."},
            },
            "required": ["text"],
        },
        handler=lambda args, **_kw: _clipboard_write(**args),
        check_fn=_check_bridge_available,
        description="Write text to the Android clipboard.",
        emoji="📋",
    )

    registry.register(
        name="android_notification_show",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Notification title."},
                "message": {"type": "string", "description": "Notification body."},
            },
            "required": ["title", "message"],
        },
        handler=lambda args, **_kw: _notification_show(**args),
        check_fn=_check_bridge_available,
        description="Show an Android notification.",
        emoji="🔔",
    )

    registry.register(
        name="android_device_info",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _device_info(**args),
        check_fn=_check_bridge_available,
        description="Get basic Android device info from the bridge.",
        emoji="📱",
    )

    registry.register(
        name="android_vibrate",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "duration": {"type": "integer", "description": "Vibration duration in milliseconds.", "default": 200},
            },
        },
        handler=lambda args, **_kw: _vibrate(**args),
        check_fn=_check_bridge_available,
        description="Vibrate the Android device.",
        emoji="📳",
    )

    registry.register(
        name="android_torch",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "on": {"type": "boolean", "description": "Turn torch on or off.", "default": True},
            },
        },
        handler=lambda args, **_kw: _torch(**args),
        check_fn=_check_bridge_available,
        description="Toggle the Android camera flashlight.",
        emoji="🔦",
    )

    registry.register(
        name="android_battery_status",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _battery_status(**args),
        check_fn=_check_bridge_available,
        description="Get Android battery level and charging status.",
        emoji="🔋",
    )

    registry.register(
        name="android_shell",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute in the app context."},
            },
            "required": ["command"],
        },
        handler=lambda args, **_kw: _shell(**args),
        check_fn=_check_bridge_available,
        description="Run a shell command in the Android app context (not Termux).",
        emoji="🐚",
    )

    registry.register(
        name="android_root_shell",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute as root via su."},
            },
            "required": ["command"],
        },
        handler=lambda args, **_kw: _root_shell(**args),
        check_fn=_check_bridge_available,
        description="Run a shell command as root via su (requires rooted device).",
        emoji="🔓",
    )

    registry.register(
        name="android_accessibility_dump",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _accessibility_dump(**args),
        check_fn=_check_bridge_available,
        description="Dump the current Android window UI hierarchy (requires accessibility service enabled).",
        emoji="🖱️",
    )

    registry.register(
        name="android_accessibility_click",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text/content-desc of the element to click."},
            },
            "required": ["text"],
        },
        handler=lambda args, **_kw: _accessibility_click(**args),
        check_fn=_check_bridge_available,
        description="Click a UI element by its visible text (requires accessibility service enabled).",
        emoji="👆",
    )

    registry.register(
        name="android_accessibility_input",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to input into the focused editable field."},
            },
            "required": ["text"],
        },
        handler=lambda args, **_kw: _accessibility_input(**args),
        check_fn=_check_bridge_available,
        description="Type text into the focused input field (requires accessibility service enabled).",
        emoji="⌨️",
    )

    registry.register(
        name="android_device_admin_lock",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _device_admin_lock(**args),
        check_fn=_check_bridge_available,
        description="Lock the device screen (requires device admin enabled).",
        emoji="🔒",
    )

    registry.register(
        name="android_device_admin_wipe",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "external": {"type": "boolean", "description": "Also wipe external storage.", "default": False},
            },
        },
        handler=lambda args, **_kw: _device_admin_wipe(**args),
        check_fn=_check_bridge_available,
        description="Factory reset the device (requires device admin enabled). Use with extreme caution.",
        emoji="💣",
    )

    registry.register(
        name="android_location_get",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _location_get(**args),
        check_fn=_check_bridge_available,
        description="Get last known device location (requires location permission).",
        emoji="📍",
    )

    logger.info("Android bridge plugin registered")
