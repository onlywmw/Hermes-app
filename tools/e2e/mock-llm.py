#!/usr/bin/env python3
# Mock LLM (OpenAI 兼容 /v1/chat/completions) — agent shell.exec e2e 专用。
# 按调用顺序回放脚本化响应, 驱动真实 AgentLoop 走完: 计划 → 计划外 shell.exec(弹闸)
# → /exchange 写入 → file.pull 取回 → file.write → finish。
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

SCRIPT = [
    # 1) 计划 (不含 shell.exec → 后续 shell.exec 是计划外, 必弹 shellPreview 闸)
    '{"understanding":{"user":"e2e","scenario":"验收","loop":"shell验证","guess":""},'
    '"plan":[{"action":"file.write","path":"answer.txt","desc":"把验证结果写进文件"}]}',
    # 2) 计划外 shell.exec → 应弹确认卡
    '{"action":"shell.exec","cmd":"uname -a && python3 --version","timeoutSec":60}',
    # 3) 已授权, 不再弹闸; 产物写到 /exchange
    '{"action":"shell.exec","cmd":"echo hello-from-linux > /exchange/hello.txt && cat /exchange/hello.txt","timeoutSec":30}',
    # 4) file.pull 取回房间
    '{"action":"file.pull","path":"hello.txt"}',
    # 5) 计划内 file.write
    '{"action":"file.write","path":"answer.txt","content":"shell.exec e2e ok"}',
    # 6) finish
    '{"action":"finish","summary":"e2e 完成"}',
]

calls = []


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', errors='replace')
        idx = len(calls)
        calls.append(body[:120])
        content = SCRIPT[idx] if idx < len(SCRIPT) else '{"action":"finish","summary":"e2e 完成"}'
        print(f'[mock-llm] call#{idx} req={body[:100]!r}', flush=True)
        print(f'[mock-llm] call#{idx} resp={content[:100]}', flush=True)
        resp = json.dumps({
            'id': 'mock', 'object': 'chat.completion',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50},
        }).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f'mock-llm on 127.0.0.1:{port}', flush=True)
    HTTPServer(('127.0.0.1', port), H).serve_forever()
