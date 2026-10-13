#!/usr/bin/env python3
"""Synthetic fixture setup and adversarial TLS peer. Never used by the APK."""
import base64, http.server, json, pathlib, ssl, sys, time, urllib.request, urllib.error
mode, folder, kind = sys.argv[1:]
p = pathlib.Path(folder)
password = (p/'password').read_text()
context = ssl.create_default_context(cafile=str(p/'server/ca.pem'))
if mode == 'seed':
    port = (p/'port').read_text().strip()
    admin = 'elastic' if kind == 'ELASTICSEARCH' else 'admin'
    def request(method, path, body=None, user=admin, expected=200):
        data = None if body is None else (body if isinstance(body, str) else json.dumps(body)).encode()
        req = urllib.request.Request('https://localhost:'+port+path, data=data, method=method,
            headers={'Authorization': 'Basic '+base64.b64encode((user+':'+password).encode()).decode(), 'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(req, context=context, timeout=10) as r:
                assert r.status == expected, r.status
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == expected: return None
            raise RuntimeError('Fixture HTTP status '+str(e.code)) from None
    for attempt in range(90):
        try: root = request('GET','/'); break
        except Exception: time.sleep(1)
    else: raise RuntimeError('Search fixture failed readiness')
    if kind == 'ELASTICSEARCH':
        request('PUT','/_security/role/datasail_reader', {'cluster':['monitor'],'indices':[{'names':['synthetic'],'privileges':['read','view_index_metadata','monitor']}]})
        request('PUT','/_security/user/reader', {'password':password,'roles':['datasail_reader']})
        request('PUT','/_security/role/datasail_denied', {'cluster':['monitor'],'indices':[]})
        request('PUT','/_security/user/denied', {'password':password,'roles':['datasail_denied']})
    request('PUT','/synthetic', {'settings':{'number_of_shards':1,'number_of_replicas':0}, 'mappings':{'properties':{'code':{'type':'keyword'},'number':{'type':'integer'},'long':{'type':'long'},'decimal':{'type':'double'},'large':{'type':'text','index':False}}}})
    lines=[]
    for i in range(205):
        lines.append(json.dumps({'index':{'_index':'synthetic','_id':str(i)}}))
        # Literal decimal verifies that _source lexical precision survives client parsing.
        doc={'number':i,'code':str(i).zfill(4),'long':9007199254740993,'empty':'','nullable':None}
        if i == 204: doc['large']='x'*9000
        lines.append(json.dumps(doc)[:-1]+',"decimal":1.2300}')
    result=request('POST','/_bulk?refresh=true','\n'.join(lines)+'\n')
    assert result['errors'] is False
    # Server account itself forbids writes, independent of the client's fixed endpoint list.
    request('PUT','/synthetic/_doc/forbidden', {'x':1}, user='reader', expected=403)
    request('GET','/_cat/indices/synthetic?format=json&h=index&expand_wildcards=open',user='reader')
    (p/'server-version.json').write_text(json.dumps({'kind':kind,'version':root['version'],'readerWriteDenied':True},indent=2)+'\n')
    print('Synthetic search server ready: '+kind+' '+root['version']['number'])
elif mode == 'fault':
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            if self.headers.get('Content-Length'): self.rfile.read(int(self.headers['Content-Length']))
            self.reply()
        def do_GET(self): self.reply()
        def reply(self):
            try:
                user=base64.b64decode(self.headers.get('Authorization',' ').split()[-1]).decode().split(':')[0]
                if self.path == '/leaked':
                    (p/'redirect-leaked').write_text('unexpected redirected request'); self.send_response(500); self.end_headers(); return
                if user == 'slow': time.sleep(20)
                if user == 'redirect':
                    self.send_response(302); self.send_header('Location','/leaked'); self.end_headers(); return
                if user == 'oversized':
                    self.send_response(200); self.send_header('Content-Length','2097153'); self.end_headers(); return
                if user == 'duplicate': data=b'{"version":{},"version":{}}'
                elif user == 'malformed': data=b'\xff'
                elif self.path.startswith('/_cat'): data=b'[{"index":"synthetic"}]'
                elif '/_search' in self.path: data=b'{"timed_out":true,"_shards":{"failed":1},"hits":{"hits":[]}}'
                else: data=json.dumps({'version':{'number':'fixture',**({'distribution':'opensearch'} if kind=='OPENSEARCH' else {})},'tagline':'You Know, for Search'}).encode()
                self.send_response(200); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
            except (OSError, ValueError): pass
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(p/'server/cert.pem',p/'server/key.pem')
    server.socket=tls.wrap_socket(server.socket,server_side=True)
    (p/'fault-port').write_text(str(server.server_port))
    server.serve_forever()
