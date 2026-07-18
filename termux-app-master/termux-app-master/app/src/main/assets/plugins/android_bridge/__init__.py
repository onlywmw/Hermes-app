"""Hermes plugin that exposes Android capabilities via the Android bridge socket."""

import json
import logging
from typing import Any, Dict

from hermes_cli.plugins import PluginContext
from tools.registry import registry
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


def _bridge_call(method: str, params: Dict[str, Any] = None) -> str:
    """调用桥并把结果序列化为 JSON 字符串（hermes 工具契约要求 str 返回）。"""
    result = get_bridge_client().call(method, params)
    return json.dumps(result, ensure_ascii=False)


def _clipboard_read(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("clipboard_read")


def _clipboard_write(text: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("clipboard_write", {"text": text})


def _notification_show(title: str, message: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("notification", {"title": title, "message": message})


def _device_info(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("device_info")


def _vibrate(duration: int = 200, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("vibrate", {"duration": duration})


def _torch(on: bool = True, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("torch", {"on": on})


def _battery_status(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("battery")


def _shell(command: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("shell", {"command": command})


def _root_shell(command: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("root_shell", {"command": command})


def _accessibility_dump(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("accessibility_dump")


def _accessibility_click(text: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("accessibility_click", {"text": text})


def _accessibility_input(text: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("accessibility_input", {"text": text})


def _device_admin_lock(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("device_admin_lock")


def _location_get(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("location_get")


def _sms_list(limit: int = 20, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("sms_list", {"limit": limit})


def _sms_send(to: str, body: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("sms_send", {"to": to, "body": body})


def _contacts_list(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("contacts_list")


def _app_list(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("app_list")


def _app_open(package: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("app_open", {"package": package})


def _volume_get(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("volume_get")


def _volume_set(volume: int, stream: int = 3, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("volume_set", {"stream": stream, "volume": volume})


def _brightness_get(**_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("brightness_get")


def _brightness_set(brightness: int, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("brightness_set", {"brightness": brightness})


def _open_url(url: str, **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("open_url", {"url": url})


def _alarm_set(hour: int, minutes: int = 0, message: str = "Hermes 提醒", **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("alarm_set", {"hour": hour, "minutes": minutes, "message": message})


def _calendar_add(title: str, start_ms: int, end_ms: int = 0, description: str = "",
                  location: str = "", **_kwargs: Any) -> Dict[str, Any]:
    return _bridge_call("calendar_add", {
        "title": title, "start_ms": start_ms, "end_ms": end_ms,
        "description": description, "location": location,
    })


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

    registry.register(
        name="android_sms_list",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max number of recent SMS messages to return.", "default": 20},
            },
        },
        handler=lambda args, **_kw: _sms_list(**args),
        check_fn=_check_bridge_available,
        description="List recent SMS messages (requires SMS permission).",
        emoji="✉️",
    )

    registry.register(
        name="android_sms_send",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "to": {"type": "string", "description": "Recipient phone number."},
                "body": {"type": "string", "description": "SMS text body."},
            },
            "required": ["to", "body"],
        },
        handler=lambda args, **_kw: _sms_send(**args),
        check_fn=_check_bridge_available,
        description="Send an SMS message (requires SMS permission). Confirm with the user before sending.",
        emoji="📤",
    )

    registry.register(
        name="android_contacts_list",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _contacts_list(**args),
        check_fn=_check_bridge_available,
        description="List device contacts (requires contacts permission).",
        emoji="👥",
    )

    registry.register(
        name="android_app_list",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _app_list(**args),
        check_fn=_check_bridge_available,
        description="List installed Android apps with package names.",
        emoji="📦",
    )

    registry.register(
        name="android_app_open",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name of the app to open, e.g. com.android.settings."},
            },
            "required": ["package"],
        },
        handler=lambda args, **_kw: _app_open(**args),
        check_fn=_check_bridge_available,
        description="Open (launch) an Android app by package name.",
        emoji="🚀",
    )

    registry.register(
        name="android_volume_get",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _volume_get(**args),
        check_fn=_check_bridge_available,
        description="Get current Android volume levels.",
        emoji="🔊",
    )

    registry.register(
        name="android_volume_set",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "volume": {"type": "integer", "description": "Target volume index."},
                "stream": {"type": "integer", "description": "Audio stream: 3=music, 2=ring, 4=alarm, 1=system, 0=voice call.", "default": 3},
            },
            "required": ["volume"],
        },
        handler=lambda args, **_kw: _volume_set(**args),
        check_fn=_check_bridge_available,
        description="Set Android volume for an audio stream.",
        emoji="🔉",
    )

    registry.register(
        name="android_brightness_get",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {},
        },
        handler=lambda args, **_kw: _brightness_get(**args),
        check_fn=_check_bridge_available,
        description="Get current screen brightness (0-255) and mode.",
        emoji="☀️",
    )

    registry.register(
        name="android_brightness_set",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "brightness": {"type": "integer", "description": "Brightness value 0-255."},
            },
            "required": ["brightness"],
        },
        handler=lambda args, **_kw: _brightness_set(**args),
        check_fn=_check_bridge_available,
        description="Set screen brightness (0-255, requires write-settings permission).",
        emoji="🔆",
    )

    registry.register(
        name="android_open_url",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL to open in the browser."},
            },
            "required": ["url"],
        },
        handler=lambda args, **_kw: _open_url(**args),
        check_fn=_check_bridge_available,
        description="Open a URL in the device browser.",
        emoji="🌐",
    )

    registry.register(
        name="android_alarm_set",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "hour": {"type": "integer", "description": "Hour of day, 0-23."},
                "minutes": {"type": "integer", "description": "Minutes, 0-59.", "default": 0},
                "message": {"type": "string", "description": "Alarm label text."},
            },
            "required": ["hour"],
        },
        handler=lambda args, **_kw: _alarm_set(**args),
        check_fn=_check_bridge_available,
        description="Set an alarm (Hermes self-managed: exact AlarmManager wakeup + fullscreen notification, label always correct; NOT the system Clock app). Prefer this over cronjob when the user says 定闹钟/闹钟.",
        emoji="⏰",
    )

    registry.register(
        name="android_calendar_add",
        toolset=TOOLSET_NAME,
        schema={
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Event title."},
                "start_ms": {"type": "integer", "description": "Event start time, Unix epoch milliseconds."},
                "end_ms": {"type": "integer", "description": "Event end time, Unix epoch milliseconds. Defaults to start+1h.", "default": 0},
                "description": {"type": "string", "description": "Event description/notes.", "default": ""},
                "location": {"type": "string", "description": "Event location.", "default": ""},
            },
            "required": ["title", "start_ms"],
        },
        handler=lambda args, **_kw: _calendar_add(**args),
        check_fn=_check_bridge_available,
        description="Add an event to the device calendar (syncs via the calendar account). Use when the user asks to 同步日历/加到日历/日程.",
        emoji="📅",
    )

    logger.info("Android bridge plugin registered")


# (name, emoji, description, schema, 调用函数)
_SIMPLE_TOOLS = [
    ("android_timer_set", "⏱️",
     "Set a countdown timer (fullscreen alert, Hermes self-managed). Use for 倒计时 / X分钟后叫我.",
     {"type": "object", "properties": {
         "seconds": {"type": "integer", "description": "Countdown seconds, 1-86400."},
         "message": {"type": "string", "default": "⏱️ 倒计时结束"},
     }, "required": ["seconds"]},
     lambda seconds, message="⏱️ 倒计时结束": _bridge_call(
         "timer_set", {"seconds": seconds, "message": message})),
    ("android_alarm_list", "📋",
     "List all pending Hermes alarms and timers.",
     {"type": "object", "properties": {}},
     lambda: _bridge_call("alarm_list")),
    ("android_alarm_cancel", "🗑️",
     "Cancel a Hermes alarm/timer by id (see android_alarm_list).",
     {"type": "object", "properties": {
         "id": {"type": "integer", "description": "Alarm id from android_alarm_list."},
     }, "required": ["id"]},
     lambda id: _bridge_call("alarm_cancel", {"id": id})),
    ("android_calendar_list", "📅",
     "List calendar events in a time range (defaults: now ~ +7 days). Times are Unix epoch ms.",
     {"type": "object", "properties": {
         "start_ms": {"type": "integer", "default": 0},
         "end_ms": {"type": "integer", "default": 0},
     }},
     lambda start_ms=0, end_ms=0: _bridge_call(
         "calendar_list", {"start_ms": start_ms, "end_ms": end_ms})),
    ("android_calendar_delete", "🗑️",
     "Delete a calendar event by id (see android_calendar_list).",
     {"type": "object", "properties": {
         "event_id": {"type": "integer"},
     }, "required": ["event_id"]},
     lambda event_id: _bridge_call("calendar_delete", {"event_id": event_id})),
    ("android_media", "🎵",
     "Control media playback: play_pause / play / pause / next / previous / stop. No permission needed.",
     {"type": "object", "properties": {
         "action": {"type": "string", "description": "play_pause|play|pause|next|previous|stop"},
     }, "required": ["action"]},
     lambda action: _bridge_call("media", {"action": action})),
    ("android_global", "🧭",
     "Global navigation: home / back / recents / notifications / quick_settings / power / lock (requires accessibility service).",
     {"type": "object", "properties": {
         "action": {"type": "string"},
     }, "required": ["action"]},
     lambda action: _bridge_call("global", {"action": action})),
    ("android_swipe", "👆",
     "Swipe gesture: direction up/down/left/right, or explicit x1,y1,x2,y2 pixels + duration ms (requires accessibility service).",
     {"type": "object", "properties": {
         "direction": {"type": "string", "default": ""},
         "x1": {"type": "integer", "default": 0}, "y1": {"type": "integer", "default": 0},
         "x2": {"type": "integer", "default": 0}, "y2": {"type": "integer", "default": 0},
         "duration": {"type": "integer", "default": 300},
     }},
     lambda direction="", x1=0, y1=0, x2=0, y2=0, duration=300: _bridge_call(
         "swipe", {"direction": direction, "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                   "duration": duration})),
    ("android_screenshot", "📸",
     "Take a screenshot via accessibility (API 30+), returns saved PNG path.",
     {"type": "object", "properties": {}},
     lambda: _bridge_call("screenshot")),
    ("android_tts_speak", "📢",
     "Speak text aloud via TextToSpeech (Chinese). Use for 播报/读出来.",
     {"type": "object", "properties": {
         "text": {"type": "string"},
     }, "required": ["text"]},
     lambda text: _bridge_call("tts_speak", {"text": text})),
    ("android_share_text", "🔗",
     "Open system share sheet to share text/link to another app (e.g. WeChat).",
     {"type": "object", "properties": {
         "text": {"type": "string"},
         "title": {"type": "string", "default": "分享到…"},
     }, "required": ["text"]},
     lambda text, title="分享到…": _bridge_call(
         "share_text", {"text": text, "title": title})),
    ("android_dial", "📞",
     "Open the dialer with a phone number prefilled (user confirms to call).",
     {"type": "object", "properties": {
         "number": {"type": "string"},
     }, "required": ["number"]},
     lambda number: _bridge_call("dial", {"number": number})),
    ("android_network_status", "📶",
     "Get network type, WiFi SSID, IP, free storage, memory, uptime.",
     {"type": "object", "properties": {}},
     lambda: _bridge_call("network_status")),
    ("android_airplane", "✈️",
     "Toggle airplane mode (requires root).",
     {"type": "object", "properties": {
         "on": {"type": "boolean", "default": True},
     }},
     lambda on=True: _bridge_call("airplane", {"on": on})),
    ("android_input_tap", "👆",
     "Tap screen at pixel coordinates via root input injection (no accessibility needed).",
     {"type": "object", "properties": {
         "x": {"type": "integer"}, "y": {"type": "integer"},
     }, "required": ["x", "y"]},
     lambda x, y: _bridge_call("input_tap", {"x": x, "y": y})),
    ("android_input_swipe", "👉",
     "Swipe between pixel coordinates via root input injection.",
     {"type": "object", "properties": {
         "x1": {"type": "integer"}, "y1": {"type": "integer"},
         "x2": {"type": "integer"}, "y2": {"type": "integer"},
         "duration": {"type": "integer", "default": 300},
     }, "required": ["x1", "y1", "x2", "y2"]},
     lambda x1, y1, x2, y2, duration=300: _bridge_call(
         "input_swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration})),
    ("android_screencap", "🖥️",
     "Take a screenshot via root screencap (no accessibility, no prompt), returns PNG path.",
     {"type": "object", "properties": {}},
     lambda: _bridge_call("screencap")),
    ("android_reboot", "🔁",
     "Reboot / shutdown / reboot-to-recovery (requires root). Confirm with the user first!",
     {"type": "object", "properties": {
         "mode": {"type": "string", "description": "reboot|shutdown|recovery", "default": "reboot"},
     }},
     lambda mode="reboot": _bridge_call("reboot", {"mode": mode})),
]

for _name, _emoji, _desc, _schema, _fn in _SIMPLE_TOOLS:
    registry.register(
        name=_name,
        toolset=TOOLSET_NAME,
        schema=_schema,
        handler=lambda args, __fn=_fn, **_kw: __fn(**args),
        check_fn=_check_bridge_available,
        description=_desc,
        emoji=_emoji,
    )
