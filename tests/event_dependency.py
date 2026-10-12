import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import httpx

proxy = len(sys.argv) > 1 and sys.argv[1] == 'proxy'
drops = 0

class Dependency(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def respond(self, status, body):
        raw = json.dumps(body).encode() if isinstance(body, dict) else body
        self.send_response(status); self.send_header('Content-Length', str(len(raw))); self.end_headers()
        try: self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_GET(self):
        if self.path == '/api/v1/auth/jwks': self.respond(200, json.loads(Path('/keys/jwks.json').read_text()))
        elif self.path == '/observations': self.respond(200, {'droppedAcknowledgements': drops})
        else: self.respond(200, {'status': 'UP'})
    def do_POST(self):
        global drops
        if not proxy or self.path != '/internal/events/stock-changed':
            self.respond(404, {'error': 'NOT_FOUND'}); return
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 < length <= 65536:
            self.respond(413, {'error': 'BODY_SIZE'}); return
        body = self.rfile.read(length)
        headers = {name: self.headers.get(name, '') for name in ('Authorization', 'Content-Type', 'X-Correlation-Id')}
        try:
            with httpx.Client(timeout=3, trust_env=False) as client:
                response = client.post('http://worker:8080/internal/events/stock-changed', content=body, headers=headers)
            if response.status_code == 200 and drops == 0:
                drops += 1
                self.respond(503, {'error': 'SYNTHETIC_LOST_ACK'})
            else: self.respond(response.status_code, response.content)
        except httpx.HTTPError: self.respond(503, {'error': 'WORKER_UNAVAILABLE'})

HTTPServer(('0.0.0.0', 8080), Dependency).serve_forever()
