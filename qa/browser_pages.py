"""Two immutable, loopback-only pages; never exposes filesystem content or a command endpoint."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from threading import Thread

ORIGINAL='http://127.0.0.1:26323/console-original'
NAVIGATED='http://127.0.0.1:26323/console-navigated'


class Pages:
    def __enter__(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                values={'/console-original':('#16324f','Original display page'),'/console-navigated':('#327755','New display page')}
                if self.path not in values:
                    self.send_error(404);return
                colour,title=values[self.path]
                body=('<!doctype html><meta charset=utf-8><title>'+title+'</title><style>html,body{margin:0;height:100%;background:'+colour+';color:white;font:20px sans-serif}body{display:grid;place-items:center}</style>'+title).encode()
                self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.send_header('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'");self.end_headers();self.wfile.write(body)
            def log_message(self,*args):pass
        self.server=ThreadingHTTPServer(('127.0.0.1',26323),Handler)
        self.server.daemon_threads=True
        self.thread=Thread(target=self.server.serve_forever,kwargs={'poll_interval':.1},daemon=True);self.thread.start();return self
    def __exit__(self,*args):
        self.server.shutdown();self.server.server_close();self.thread.join(2)
