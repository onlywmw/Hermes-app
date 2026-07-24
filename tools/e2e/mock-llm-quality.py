#!/usr/bin/env python3
# Mock LLM (M-QUALITY 诱饵) — 驱动大脑写「死按钮」产物 + 声称真实现,
# 让真实 MiMo 评审团(非 mock)看内容证伪 → 投 fail → 返工 → 修正挂牌。
# 两轮: ①死按钮+谎称真实现 ②返工后改演示挂牌
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

# ① 死按钮 HTML: 扫一扫 onclick 绑空函数
DEAD_HTML = '''<!DOCTYPE html><html><head><meta charset="utf-8"><title>二维码工具</title></head><body>
<h2>二维码工具</h2>
<input id="t" placeholder="输入文本"><button onclick="gen()">生成二维码</button>
<canvas id="cv"></canvas><br>
<button id="scan" onclick="doScan()">扫一扫</button>
<div id="result"></div>
<script>
function gen(){ var c=document.getElementById("cv"); var x=c.getContext("2d");
x.fillStyle="#000"; for(var i=0;i<100;i++)for(var j=0;j<100;j++){if((i*j)%7<2)x.fillRect(i*4,j*4,4,4);} }
function doScan(){}
</script></body></html>'''

# ② 返工后: 扫一扫改演示挂牌 (可见角标 + 说明)
FIXED_HTML = '''<!DOCTYPE html><html><head><meta charset="utf-8"><title>二维码工具</title></head><body>
<h2>二维码工具</h2>
<input id="t" placeholder="输入文本"><button onclick="gen()">生成二维码</button>
<canvas id="cv"></canvas><br>
<button id="scan" onclick="doScan()">扫一扫 <span style="background:#B45309;color:#fff;font-size:10px;padding:2px 6px;border-radius:6px">演示</span></button>
<div id="result"></div>
<script>
function gen(){ var c=document.getElementById("cv"); var x=c.getContext("2d");
x.fillStyle="#000"; for(var i=0;i<100;i++)for(var j=0;j<100;j++){if((i*j)%7<2)x.fillRect(i*4,j*4,4,4);} }
function doScan(){ document.getElementById("result").textContent="演示版: 浏览器 BarcodeDetector 在本机 WebView 不可用, 扫码功能需接入原生相机 (演示标记)"; }
</script></body></html>'''

MOV_SCRIPT = [
    # 计划
    '{"understanding":{"user":"二维码工具","scenario":"评审证伪","loop":"写码","guess":""},'
    '"plan":[{"action":"file.write","path":"qr.html","desc":"二维码工具"}]}',
    # ① 写死按钮 HTML
    json.dumps({"action": "file.write", "path": "qr.html", "content": DEAD_HTML}, ensure_ascii=False),
    # finish: 谎称扫一扫真实现 (无自检清单诚实标注)
    '{"action":"finish","summary":"二维码工具完成, 输入文本可生成二维码, 扫一扫按钮可扫码"}',
    # ② 返工轮: 评审打回后修正为演示挂牌
    json.dumps({"action": "file.write", "path": "qr.html", "content": FIXED_HTML}, ensure_ascii=False),
    # 返工 finish: 诚实清单
    '{"action":"finish","summary":"二维码工具完成\\n- ✅ 生成二维码: 真实现\\n- ⚠️ 扫一扫: 演示版(本机 WebView 不支持 BarcodeDetector, 已带演示角标)"}',
]

calls = []


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
        content = MOV_SCRIPT[idx] if idx < len(MOV_SCRIPT) else '{"action":"finish","summary":"完"}'
        print('[mock-quality] MOV#%d -> %s' % (idx, content[:70].encode('ascii', 'replace').decode('ascii')), flush=True)
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
    print(f'mock-llm-quality on 127.0.0.1:{port}', flush=True)
    HTTPServer(('127.0.0.1', port), H).serve_forever()
