#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
轻量级 Hermes Android Agent
- 使用 urllib 调用 OpenRouter 兼容 OpenAI 的 Chat Completion API
- 通过 Android bridge (Unix socket) 调用设备能力
- 提供本地 HTTP 接口 /chat 供 dashboard 或外部调用
"""

import json
import os
import socket
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

# 让 agent 能找到 bridge client
HERMES_HOME = Path(os.environ.get("HERMES_HOME", "/data/data/com.termux/files/home/.hermes"))
PLUGIN_DIR = HERMES_HOME / "plugins" / "android_bridge"
if str(PLUGIN_DIR) not in sys.path:
    sys.path.insert(0, str(PLUGIN_DIR))

from client import AndroidBridgeClient  # noqa: E402

DEFAULT_SOCKET = "tcp:127.0.0.1:18081"
DEFAULT_MODEL = "openai/gpt-4o-mini"
API_BASE = "https://openrouter.ai/api/v1"
AGENT_PORT = 18080

# 内置中国主流大模型厂商 OpenAI 兼容接入点。
# 用户只需在 UI 选择厂商、填写模型名和 API Key，base_url 自动匹配。
PROVIDERS = {
    "deepseek": {
        "name": "DeepSeek",
        "base_url": "https://api.deepseek.com",
        "default_model": "deepseek-v4-flash",
    },
    "kimi": {
        "name": "Kimi (Moonshot)",
        "base_url": "https://api.moonshot.cn/v1",
        "default_model": "moonshot-v1-8k",
    },
    "qwen": {
        "name": "通义千问 (DashScope)",
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "default_model": "qwen-plus",
    },
    "zhipu": {
        "name": "智谱 GLM",
        "base_url": "https://open.bigmodel.cn/api/paas/v4",
        "default_model": "glm-4-flash",
    },
    "doubao": {
        "name": "豆包 (火山方舟)",
        "base_url": "https://ark.cn-beijing.volces.com/api/v3",
        "default_model": "doubao-pro-32k",
    },
    "yi": {
        "name": "零一万物 Yi",
        "base_url": "https://api.lingyiwanwu.com/v1",
        "default_model": "yi-lightning",
    },
    "openrouter": {
        "name": "OpenRouter",
        "base_url": "https://openrouter.ai/api/v1",
        "default_model": "openai/gpt-4o-mini",
    },
}


def load_env():
    env = {}
    env_file = HERMES_HOME / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    # 兼容旧版 OPENROUTER_* 配置
    env.setdefault("OPENROUTER_API_KEY", os.environ.get("OPENROUTER_API_KEY", ""))
    env.setdefault("OPENROUTER_MODEL", os.environ.get("OPENROUTER_MODEL", DEFAULT_MODEL))
    env.setdefault("OPENROUTER_BASE_URL", os.environ.get("OPENROUTER_BASE_URL", API_BASE))

    # 新版：用户选择厂商 + 模型 + API Key
    env.setdefault("PROVIDER", "")
    env.setdefault("MODEL", "")
    env.setdefault("API_KEY", "")

    # 如果没有新版配置，但存在旧版 OPENROUTER 配置，自动迁移到 openrouter 厂商
    if not env["PROVIDER"] and env["OPENROUTER_API_KEY"]:
        env["PROVIDER"] = "openrouter"
        env["API_KEY"] = env["OPENROUTER_API_KEY"]
        env["MODEL"] = env["OPENROUTER_MODEL"]
        env["BASE_URL"] = env["OPENROUTER_BASE_URL"]

    # 根据厂商自动填充 base_url
    provider = env.get("PROVIDER", "").lower()
    if provider in PROVIDERS:
        env.setdefault("BASE_URL", PROVIDERS[provider]["base_url"])
    else:
        env.setdefault("BASE_URL", env.get("OPENROUTER_BASE_URL", API_BASE))

    return env


ENV = load_env()
BRIDGE = AndroidBridgeClient(os.environ.get("HERMES_ANDROID_BRIDGE_SOCKET", DEFAULT_SOCKET))


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "clipboard_read",
            "description": "读取当前剪贴板文本内容",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "clipboard_write",
            "description": "写入文本到剪贴板",
            "parameters": {
                "type": "object",
                "properties": {"text": {"type": "string", "description": "要写入的文本"}},
                "required": ["text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "notification",
            "description": "发送一条系统通知",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "message": {"type": "string"},
                },
                "required": ["title", "message"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "shell",
            "description": "在 Termux 中执行 shell 命令（非 root）",
            "parameters": {
                "type": "object",
                "properties": {"command": {"type": "string"}},
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "root_shell",
            "description": "以 root 权限执行 shell 命令（需要设备已 root）",
            "parameters": {
                "type": "object",
                "properties": {"command": {"type": "string"}},
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "accessibility_dump",
            "description": "获取当前屏幕无障碍节点树，用于定位可点击元素",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "accessibility_click",
            "description": "点击屏幕上包含指定文本的元素",
            "parameters": {
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "accessibility_input",
            "description": "在焦点输入框中输入文本",
            "parameters": {
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "device_admin_lock",
            "description": "立即锁屏（需要已激活设备管理员）",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "location_get",
            "description": "获取最近一次已知位置",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "battery",
            "description": "读取电池电量与状态",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "device_info",
            "description": "读取设备基本信息",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "sms_list",
            "description": "读取最近收到的短信",
            "parameters": {
                "type": "object",
                "properties": {"limit": {"type": "integer", "description": "返回条数"}},
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "sms_send",
            "description": "发送短信",
            "parameters": {
                "type": "object",
                "properties": {
                    "to": {"type": "string", "description": "接收手机号"},
                    "body": {"type": "string", "description": "短信内容"},
                },
                "required": ["to", "body"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "contacts_list",
            "description": "读取通讯录联系人",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "app_list",
            "description": "列出已安装应用",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "app_open",
            "description": "打开指定包名的应用",
            "parameters": {
                "type": "object",
                "properties": {"package": {"type": "string"}},
                "required": ["package"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "volume_get",
            "description": "读取当前音量",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "volume_set",
            "description": "设置音量",
            "parameters": {
                "type": "object",
                "properties": {
                    "stream": {"type": "integer", "description": "音频流类型，默认音乐"},
                    "volume": {"type": "integer", "description": "音量值"},
                },
                "required": ["volume"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "brightness_get",
            "description": "读取屏幕亮度",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "brightness_set",
            "description": "设置屏幕亮度（0-255）",
            "parameters": {
                "type": "object",
                "properties": {"brightness": {"type": "integer"}},
                "required": ["brightness"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_url",
            "description": "用浏览器打开网址",
            "parameters": {
                "type": "object",
                "properties": {"url": {"type": "string"}},
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "alarm_set",
            "description": "定一个 Hermes 自管闹钟（精确唤醒+全屏响铃通知，标签可靠）。hour 0-23, minutes 0-59, message 闹钟标签",
            "parameters": {
                "type": "object",
                "properties": {
                    "hour": {"type": "integer"},
                    "minutes": {"type": "integer"},
                    "message": {"type": "string"},
                },
                "required": ["hour"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calendar_add",
            "description": "向日历添加日程。title 标题, start_ms 开始毫秒时间戳, end_ms 结束毫秒时间戳(可选), description 备注(可选), location 地点(可选)",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "start_ms": {"type": "integer"},
                    "end_ms": {"type": "integer"},
                    "description": {"type": "string"},
                    "location": {"type": "string"},
                },
                "required": ["title", "start_ms"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "timer_set",
            "description": "倒计时。seconds 秒数, message 提示语(可选)",
            "parameters": {"type": "object", "properties": {"seconds": {"type": "integer"}, "message": {"type": "string"}}, "required": ["seconds"]},
        },
    },
    {"type": "function", "function": {"name": "alarm_list", "description": "列出所有未触发的闹钟和倒计时", "parameters": {"type": "object", "properties": {}}}},
    {
        "type": "function",
        "function": {
            "name": "alarm_cancel",
            "description": "按 id 取消闹钟/倒计时",
            "parameters": {"type": "object", "properties": {"id": {"type": "integer"}}, "required": ["id"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calendar_list",
            "description": "查询日历日程。start_ms/end_ms 毫秒时间戳(可选，默认现在起7天)",
            "parameters": {"type": "object", "properties": {"start_ms": {"type": "integer"}, "end_ms": {"type": "integer"}}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calendar_delete",
            "description": "按 event_id 删除日历日程",
            "parameters": {"type": "object", "properties": {"event_id": {"type": "integer"}}, "required": ["event_id"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "media",
            "description": "媒体控制。action: play_pause/play/pause/next/previous/stop",
            "parameters": {"type": "object", "properties": {"action": {"type": "string"}}, "required": ["action"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "global",
            "description": "全局导航(需无障碍)。action: home/back/recents/notifications/quick_settings/power/lock",
            "parameters": {"type": "object", "properties": {"action": {"type": "string"}}, "required": ["action"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "swipe",
            "description": "手势滑动(需无障碍)。direction: up/down/left/right，或给 x1,y1,x2,y2,duration",
            "parameters": {"type": "object", "properties": {"direction": {"type": "string"}, "x1": {"type": "integer"}, "y1": {"type": "integer"}, "x2": {"type": "integer"}, "y2": {"type": "integer"}, "duration": {"type": "integer"}}},
        },
    },
    {"type": "function", "function": {"name": "screenshot", "description": "无障碍截图(Android 11+)，返回保存路径", "parameters": {"type": "object", "properties": {}}}},
    {
        "type": "function",
        "function": {
            "name": "tts_speak",
            "description": "语音播报文本(中文)",
            "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "share_text",
            "description": "打开系统分享面板分享文本",
            "parameters": {"type": "object", "properties": {"text": {"type": "string"}, "title": {"type": "string"}}, "required": ["text"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "dial",
            "description": "打开拨号盘并填入号码",
            "parameters": {"type": "object", "properties": {"number": {"type": "string"}}, "required": ["number"]},
        },
    },
    {"type": "function", "function": {"name": "network_status", "description": "查询网络类型/WiFi名/IP/存储/内存/开机时长", "parameters": {"type": "object", "properties": {}}}},
    {
        "type": "function",
        "function": {
            "name": "airplane",
            "description": "开关飞行模式(需 root)。on true/false",
            "parameters": {"type": "object", "properties": {"on": {"type": "boolean"}}, "required": ["on"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "input_tap",
            "description": "root 模拟点击屏幕坐标。x, y 像素",
            "parameters": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}}, "required": ["x", "y"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "input_swipe",
            "description": "root 模拟滑动。x1,y1 到 x2,y2，duration 毫秒",
            "parameters": {"type": "object", "properties": {"x1": {"type": "integer"}, "y1": {"type": "integer"}, "x2": {"type": "integer"}, "y2": {"type": "integer"}, "duration": {"type": "integer"}}, "required": ["x1", "y1", "x2", "y2"]},
        },
    },
    {"type": "function", "function": {"name": "screencap", "description": "root 无感截图，返回保存路径", "parameters": {"type": "object", "properties": {}}}},
    {
        "type": "function",
        "function": {
            "name": "reboot",
            "description": "重启/关机(需 root)。mode: reboot/shutdown/recovery。危险操作，先确认",
            "parameters": {"type": "object", "properties": {"mode": {"type": "string"}}},
        },
    },
]

SYSTEM_PROMPT = """你是 Hermes Android 智能体，运行在用户的 Android 设备上。你可以使用以下工具直接控制手机/平板：

{tools}

当需要操作时，请使用 function_call 格式：
{{"name": "工具名", "arguments": {{"参数": "值"}}}}

注意：
- 所有回复请使用中文。
- 调用工具后请根据返回结果用中文总结。
- 不要假设工具一定成功；如果失败请告诉用户原因。
- 敏感操作（root_shell、device_admin_lock/wipe）执行前需简要说明。
""".format(tools="\n".join(f"- {t['function']['name']}: {t['function']['description']}" for t in TOOLS))


def call_llm(messages):
    provider = ENV.get("PROVIDER", "").lower()
    key = ENV.get("API_KEY", "")
    model = ENV.get("MODEL", "")

    # 兼容旧版
    if not provider:
        provider = "openrouter"
    if not key:
        key = ENV.get("OPENROUTER_API_KEY", "")
    if not model:
        model = ENV.get("OPENROUTER_MODEL", DEFAULT_MODEL)

    # 厂商模型名大小写敏感，统一去空格并转小写，防止手滑（如 deepseek-v4-Pro）
    model = model.strip().lower()

    if not key:
        return {"error": "未配置 API Key，请在设置中填写"}
    if not model:
        return {"error": "未配置模型名称，请在设置中填写"}

    cfg = PROVIDERS.get(provider)
    if cfg:
        api_base = cfg["base_url"].rstrip("/")
    else:
        api_base = ENV.get("BASE_URL", API_BASE).rstrip("/")

    data = {
        "model": model,
        "messages": messages,
        "tools": TOOLS,
        "tool_choice": "auto",
    }

    headers = {
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
    }
    # OpenRouter 需要额外标识头
    if provider == "openrouter":
        headers.update({
            "HTTP-Referer": "https://hermes.android.local",
            "X-Title": "Hermes Android",
        })

    req = urllib.request.Request(
        f"{api_base}/chat/completions",
        data=json.dumps(data, ensure_ascii=False).encode("utf-8"),
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="ignore")
        return {"error": f"HTTP {e.code}: {body}"}
    except Exception as e:
        return {"error": str(e)}


def execute_tool(name, arguments):
    try:
        return BRIDGE.call(name, arguments or {})
    except Exception as e:
        return {"success": False, "error": str(e)}


def run_turn(user_message):
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_message},
    ]

    for _ in range(5):
        resp = call_llm(messages)
        if "error" in resp:
            return f"调用模型失败：{resp['error']}"

        choice = resp.get("choices", [{}])[0]
        message = choice.get("message", {})
        content = message.get("content") or ""
        tool_calls = message.get("tool_calls") or []

        if not tool_calls:
            return content

        messages.append({
            "role": "assistant",
            "content": content,
            "tool_calls": tool_calls,
        })

        for tc in tool_calls:
            fn = tc.get("function", {})
            name = fn.get("name", "")
            try:
                args = json.loads(fn.get("arguments", "{}"))
            except json.JSONDecodeError:
                args = {}
            result = execute_tool(name, args)
            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "name": name,
                "content": json.dumps(result, ensure_ascii=False),
            })

    return "工具调用次数超过上限，请简化需求。"


class ChatHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _json(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/chat":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if isinstance(payload, str):
                payload = json.loads(payload)
            message = payload.get("message", "") if isinstance(payload, dict) else ""
            reply = run_turn(message) if message else "请输入消息"
            self._json(200, {"reply": reply})
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._json(500, {"error": str(e), "traceback": traceback.format_exc()})

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "ok", "model": ENV.get("OPENROUTER_MODEL", DEFAULT_MODEL)})
        else:
            self._json(404, {"error": "not found"})


def main():
    # 测试 bridge 连通性
    try:
        info = BRIDGE.call("device_info", {})
        print(f"[HermesAgent] bridge ready: {info.get('model', 'unknown')}")
    except Exception as e:
        print(f"[HermesAgent] bridge not ready: {e}")

    HTTPServer.allow_reuse_address = True
    server = HTTPServer(("127.0.0.1", AGENT_PORT), ChatHandler)
    print(f"[HermesAgent] listening on http://127.0.0.1:{AGENT_PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
