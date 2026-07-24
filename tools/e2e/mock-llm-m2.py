#!/usr/bin/env python3
# Mock LLM (M2 委派验收) — 一个端口同时扮演两个大脑:
#   MOV agent (system 含 "MOV agent 的大脑") → 按脚本回放, 驱动真实 AgentLoop 走
#     shell.exec hermes --version / hermes -z 委派;
#   内嵌 Hermes (其他 system) → 固定回 DELEGATION-OK (无 tool_calls, oneshot 直接收尾)。
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

MOV_SCRIPT = [
    '{"understanding":{"user":"e2e","scenario":"M2委派验收","loop":"委派hermes","guess":""},'
    '"plan":[{"action":"file.write","path":"answer2.txt","desc":"写验证结果"}]}',
    '{"action":"shell.exec","cmd":"~/.hermes-venv/bin/hermes --version","timeoutSec":120}',
    '{"action":"shell.exec","cmd":"~/.hermes-venv/bin/hermes -z \'Reply with exactly: DELEGATION-OK\'","timeoutSec":300}',
    '{"action":"file.write","path":"answer2.txt","content":"hermes delegation e2e ok"}',
    '{"action":"finish","summary":"M2 委派验收完成"}',
]

mov_calls = []
hermes_calls = []


def chat_resp(content, stream=False):
    if stream:
        # OpenAI SDK 流式: SSE chunk + finish_reason + [DONE]
        chunk1 = json.dumps({
            'id': 'mock', 'object': 'chat.completion.chunk',
            'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': content}}]})
        chunk2 = json.dumps({
            'id': 'mock', 'object': 'chat.completion.chunk',
            'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}]})
        return (f'data: {chunk1}\n\ndata: {chunk2}\n\ndata: [DONE]\n\n').encode('utf-8'), 'text/event-stream'
    return json.dumps({
        'id': 'mock', 'object': 'chat.completion',
        'choices': [{'index': 0, 'finish_reason': 'stop',
                     'message': {'role': 'assistant', 'content': content}}],
        'usage': {'prompt_tokens': 100, 'completion_tokens': 50},
    }).encode('utf-8'), 'application/json'


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', errors='replace')
        try:
            req = json.loads(body)
            msgs = req.get('messages', [])
            stream = bool(req.get('stream'))
            sys_prompt = next((m.get('content', '') for m in msgs
                               if m.get('role') == 'system'), '')
        except Exception:
            sys_prompt = ''
            stream = False
        if 'MOV agent' in sys_prompt:
            idx = len(mov_calls)
            mov_calls.append(idx)
            content = MOV_SCRIPT[idx] if idx < len(MOV_SCRIPT) else '{"action":"finish","summary":"完"}'
            print(f'[mock] MOV#{idx} -> {content[:80]}', flush=True)
        else:
            hermes_calls.append(sys_prompt[:60])
            content = 'DELEGATION-OK'
            print(f'[mock] HERMES#{len(hermes_calls)} stream={stream} -> DELEGATION-OK', flush=True)
        resp, ctype = chat_resp(content, stream)
        self.send_response(200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f'mock-llm-m2 on 127.0.0.1:{port}', flush=True)
    HTTPServer(('127.0.0.1', port), H).serve_forever()
