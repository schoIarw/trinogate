#!/usr/bin/env python3
"""Minimal Trino coordinator mock for CI smoke tests.

Implements just enough of the Trino statement protocol:
  POST /v1/statement                  -> QueryResults JSON (page 1)
  GET  /v1/statement/{id}/{token}     -> final page JSON
  DELETE /v1/statement/{id}/{token}   -> 204
  GET  /v1/info                       -> coordinator info JSON (health check)

SQL containing "SLOW" returns a two-page query (nextUri present on page 1),
otherwise a single page with data [[1]].
"""
import json
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def page(query_id, next_uri=None, state="FINISHED"):
    body = {
        "id": query_id,
        "infoUri": f"http://127.0.0.1:{PORT}/ui/query.html?id={query_id}",
        "columns": [{
            "name": "c1",
            "type": "bigint",
            "typeSignature": {"rawType": "bigint", "arguments": []},
        }],
        "data": [[1]],
        "stats": {"state": state},
    }
    if next_uri:
        body["nextUri"] = next_uri
    return json.dumps(body).encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body=None, content_type="application/json"):
        if body is None:
            self.send_response(code)
            self.end_headers()
            return
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/v1/statement":
            self._send(404, b'{"error":"not found"}')
            return
        length = int(self.headers.get("Content-Length", 0))
        sql = self.rfile.read(length).decode("utf-8", "replace")
        query_id = f"backend-{uuid.uuid4().hex[:8]}"
        if "SLOW" in sql:
            next_uri = f"http://127.0.0.1:{PORT}/v1/statement/executing/{query_id}/1"
            self._send(200, page(query_id, next_uri=next_uri, state="RUNNING"))
        else:
            self._send(200, page(query_id))

    def do_GET(self):
        if self.path == "/v1/info":
            body = json.dumps({
                "nodeVersion": {"version": "trino-446-fake"},
                "environment": "ci-mock",
                "starting": False,
            }).encode()
            self._send(200, body)
        elif self.path.startswith("/v1/statement/"):
            query_id = self.path.split("/")[3] if len(self.path.split("/")) > 3 else "unknown"
            self._send(200, page(query_id))
        else:
            self._send(404, b'{"error":"not found"}')

    def do_DELETE(self):
        if self.path.startswith("/v1/statement/"):
            self._send(204)
        else:
            self._send(404, b'{"error":"not found"}')


if __name__ == "__main__":
    PORT = int(__import__("os").environ.get("MOCK_PORT", "19091"))
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"mock-trino listening on {PORT}", flush=True)
    threading.Event().wait()
