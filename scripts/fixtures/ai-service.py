#!/usr/bin/env python3
"""Synthetic HTTPS protocol fixture; never forwards requests or logs keys/bodies."""
import argparse, json, ssl, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
p = argparse.ArgumentParser(); p.add_argument('--cert'); p.add_argument('--key'); p.add_argument('--port', type=int); args = p.parse_args()
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def reply(self, body, status=200):
        data = json.dumps(body).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def authorized(self):
        route = self.path.split('/')[1]
        header = {'openai':'Authorization','anthropic':'x-api-key','gemini':'x-goog-api-key'}.get(route, 'Authorization')
        expected = 'Bearer synthetic-ai-only' if header == 'Authorization' else 'synthetic-ai-only'
        return self.headers.get(header) == expected
    def do_GET(self):
        if self.path.startswith('/redirect'):
            self.send_response(302); self.send_header('Location','https://localhost:18743/forbidden'); self.end_headers(); return
        if self.path.startswith('/slow'): time.sleep(10)
        if not self.authorized(): self.reply({'error':'synthetic authentication failure'},401); return
        if self.path.startswith('/gemini'):
            self.reply({'models':[{'name':'models/fixture-model','supportedGenerationMethods':['generateContent']}]})
        else: self.reply({'data':[{'id':'fixture-model'},{'id':'fixture-second'}],'has_more':False})
    def do_POST(self):
        if not self.authorized(): self.reply({},401); return
        size = int(self.headers.get('Content-Length','0'))
        if not 0 < size < 131072: self.reply({},400); return
        raw = self.rfile.read(size).decode()
        if 'ROW_MUST_NOT_LEAK' in raw or 'synthetic-ai-only' in raw: self.reply({},400); return
        body = json.loads(raw)
        if 'tools' in body: self.reply({},400); return
        answer = '下面是只读查询草稿：\n```sql\nSELECT id, name FROM ai_fixture\n```\n请核对字段和权限。'
        if 'You have no database access in this chat' in raw:
            if self.path.startswith('/gemini'):
                messages = [{'role':'assistant' if m['role']=='model' else m['role'], 'content':m['parts'][0]['text']} for m in body['contents']]
            else:
                messages = body['messages'][1:] if self.path.startswith('/openai') else body['messages']
            if any(x in raw for x in ['ai_fixture', '数据库方言', 'ROW_MUST_NOT_LEAK']): self.reply({},400); return
            if messages == [{'role':'user','content':'你好，我叫小舟'}]: answer = '你好，小舟'
            elif messages == [{'role':'user','content':'你好，我叫小舟'}, {'role':'assistant','content':'你好，小舟'}, {'role':'user','content':'我叫什么？'}]: answer = '你叫小舟，已收到上一轮对话'
            else: self.reply({},400); return
        if 'You translate a user' in raw:
            answer = json.dumps({'action':'READ','filters':[{'column':'id','operator':'EQ','value':'2'}],'sorts':[],'values':{}})
        if self.path.startswith('/anthropic'):
            self.reply({'stop_reason':'end_turn','content':[{'type':'text','text':answer}]})
        elif self.path.startswith('/gemini'):
            self.reply({'candidates':[{'finishReason':'STOP','content':{'parts':[{'text':answer}]}}]})
        else: self.reply({'choices':[{'finish_reason':'stop','message':{'content':answer,'tool_calls':[],'function_call':None}}]})
server = ThreadingHTTPServer(('127.0.0.1',args.port), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(args.cert,args.key); server.socket = context.wrap_socket(server.socket,server_side=True)
server.serve_forever()
