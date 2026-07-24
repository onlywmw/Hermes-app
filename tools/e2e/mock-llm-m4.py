#!/usr/bin/env python3
# Mock LLM (M4 部署验收) — 驱动真实 AgentLoop 走全栈部署链路:
# 写 FastAPI 后端 + HTML 前端 → 本地起服务 curl 自测 → movscp/movssh 部署 → 远端健康检查。
# 后端/前端内容真实可用 (不是占位), 服务在 rootfs 里真实起, ssh 目标是 localhost 夹具。
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

API_PY = r'''from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()
NOTES = []

class Note(BaseModel):
    text: str

@app.get("/api/notes")
def list_notes():
    return {"notes": NOTES}

@app.post("/api/notes")
def add_note(n: Note):
    NOTES.append(n.text)
    return {"ok": True, "count": len(NOTES)}

@app.get("/api/health")
def health():
    return {"status": "ok", "notes": len(NOTES)}
'''

HTML = '''<!DOCTYPE html><html><head><meta charset="utf-8"><title>留言板</title>
<style>body{font-family:sans-serif;max-width:480px;margin:24px auto;padding:0 12px}
input{flex:1;padding:10px}button{padding:10px 16px}.row{display:flex;gap:8px}
li{padding:8px;border-bottom:1px solid #eee}</style></head><body>
<h2>留言板 (FastAPI 后端)</h2>
<div class="row"><input id="t" placeholder="写点什么…"><button onclick="add()">提交</button></div>
<ul id="list"></ul>
<script>
const API = "http://127.0.0.1:8023/api";
async function load(){const r=await fetch(API+"/notes");const d=await r.json();
document.getElementById("list").innerHTML=d.notes.map(n=>"<li>"+n+"</li>").join("");}
async function add(){const t=document.getElementById("t").value;if(!t)return;
await fetch(API+"/notes",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({text:t})});
document.getElementById("t").value="";load();}
load();
</script></body></html>'''

MOV_SCRIPT = [
    # 1) 计划 (含 shell.exec → 批准即授权, 无闸)
    '{"understanding":{"user":"全栈留言板","scenario":"M4部署验收","loop":"自测+部署","guess":""},'
    '"plan":[{"action":"file.write","path":"guestbook_api.py","desc":"FastAPI 后端"},'
    '{"action":"file.write","path":"guestbook.html","desc":"单文件前端"},'
    '{"action":"shell.exec","desc":"装依赖+本地起服务自测"},'
    '{"action":"shell.exec","desc":"部署到服务器"},'
    '{"action":"shell.exec","desc":"远端健康检查"}]}',
    # 2) 写后端
    json.dumps({"action": "file.write", "path": "guestbook_api.py", "content": API_PY}, ensure_ascii=False),
    # 3) 写前端
    json.dumps({"action": "file.write", "path": "guestbook.html", "content": HTML}, ensure_ascii=False),
    # 4) file.push 后端进 /exchange
    '{"action":"file.push","path":"guestbook_api.py"}',
    # 5) 装依赖, 本地起服务自测 (9000 端口)
    '{"action":"shell.exec","timeoutSec":420,"cmd":"local_placeholder"}',
    # 6) 部署: 远端建目录 + scp 后端 + 远端起服务 (8023 端口) + 健康检查
    '{"action":"shell.exec","timeoutSec":120,"cmd":"deploy_placeholder"}',
    # 7) finish
    '{"action":"finish","summary":"留言板全栈交付: 后端 FastAPI 已部署并通过健康检查, 前端 guestbook.html 可对接"}',
]

calls = []


def local_cmd():
    return ("pip install -q --break-system-packages fastapi 'uvicorn[standard]' 2>&1 | tail -1"
            " && cd /exchange && (nohup python3 -m uvicorn guestbook_api:app"
            " --host 127.0.0.1 --port 9000 > /tmp/gb-local.log 2>&1 &)"
            " && sleep 4 && curl -s http://127.0.0.1:9000/api/health"
            " && curl -s -X POST http://127.0.0.1:9000/api/notes"
            " -H 'Content-Type: application/json' -d '{\"text\":\"local-test\"}'"
            " && curl -s http://127.0.0.1:9000/api/notes")


def deploy_cmd():
    return ("movssh 'mkdir -p /root/deploy'"
            " && movscp /exchange/guestbook_api.py /root/deploy/"
            " && movssh 'cd /root/deploy"
            " && (nohup python3 -m uvicorn guestbook_api:app"
            " --host 127.0.0.1 --port 8023 > /tmp/gb-remote.log 2>&1 &)"
            " && sleep 4 && curl -s http://127.0.0.1:8023/api/health"
            " && curl -s -X POST http://127.0.0.1:8023/api/notes"
            " -H \"Content-Type: application/json\" -d \"{\\\"text\\\":\\\"remote-test\\\"}\""
            " && curl -s http://127.0.0.1:8023/api/notes'")


def chat_resp(content):
    return json.dumps({
        'id': 'mock', 'object': 'chat.completion',
        'choices': [{'index': 0, 'finish_reason': 'stop',
                     'message': {'role': 'assistant', 'content': content}}],
        'usage': {'prompt_tokens': 100, 'completion_tokens': 50},
    }).encode('utf-8')


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', errors='replace')
        idx = len(calls)
        calls.append(idx)
        if idx < len(MOV_SCRIPT):
            content = MOV_SCRIPT[idx]
            if 'local_placeholder' in content:
                content = json.dumps({"action": "shell.exec", "timeoutSec": 420,
                                      "cmd": local_cmd()})
            elif 'deploy_placeholder' in content:
                content = json.dumps({"action": "shell.exec", "timeoutSec": 120,
                                      "cmd": deploy_cmd()})
        else:
            content = '{"action":"finish","summary":"完"}'
        print(f'[mock-m4] MOV#{idx} -> {content[:90]}', flush=True)
        resp = chat_resp(content)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f'mock-llm-m4 on 127.0.0.1:{port}', flush=True)
    HTTPServer(('127.0.0.1', port), H).serve_forever()
