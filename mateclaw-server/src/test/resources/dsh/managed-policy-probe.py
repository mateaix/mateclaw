"""Opt-in real SDK probe with a local deterministic Messages endpoint; no user credentials.
Run with Node24 first on PATH: python3 managed-policy-probe.py /absolute/managed/bin/dsh
"""
import http.server
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading


def probe(executable, root):
    target = root / 'guarded.txt'
    target.write_text('before')
    calls = []
    failures = []

    class Endpoint(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            calls.append(request)
            n = len(calls)
            if n == 3:
                target.write_text('external-change')
            script = [
                ('edit', {'file_path': str(target), 'old_string': 'before', 'new_string': 'after'}),
                ('read', {'file_path': str(target)}),
                ('edit', {'file_path': str(target), 'old_string': 'before', 'new_string': 'after'}),
                ('read', {'file_path': str(target)}),
                ('edit', {'file_path': str(target), 'old_string': 'external-change', 'new_string': 'after'}),
            ]
            events = [{'type': 'message_start', 'message': {'id': f'm{n}', 'type': 'message', 'role': 'assistant', 'model': 'bounded-test', 'content': [], 'usage': {'input_tokens': 3, 'output_tokens': 0}}}]
            if n <= len(script):
                name, arguments = script[n-1]
                events += [
                    {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'tool_use', 'id': f'call{n}', 'name': name, 'input': {}}},
                    {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'input_json_delta', 'partial_json': json.dumps(arguments)}},
                ]
            else:
                events += [
                    {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'text', 'text': ''}},
                    {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'text_delta', 'text': 'POLICY_OK'}},
                ]
            events += [{'type': 'content_block_stop', 'index': 0}, {'type': 'message_delta', 'delta': {'stop_reason': 'tool_use' if n <= len(script) else 'end_turn', 'stop_sequence': None}, 'usage': {'output_tokens': 10}}, {'type': 'message_stop'}]
            body = ''.join(f'event: {e["type"]}\ndata: {json.dumps(e)}\n\n' for e in events).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Endpoint)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    env = {k: os.environ[k] for k in ('PATH', 'TMPDIR') if k in os.environ}
    env.update(HOME=str(root), DSH_HOME=str(root/'home'), DSH_AGENTS_HOME=str(root/'agents'),
               DEEPSEEK_API_KEY='local-probe-only', DEEPSEEK_BASE_URL=f'http://127.0.0.1:{server.server_port}',
               MATECLAW_DSH_MODEL='bounded-test', MATECLAW_DSH_CONTEXT_WINDOW='15360', MATECLAW_DSH_MAX_TOKENS='1000')
    frames = queue.Queue()
    with tempfile.TemporaryFile() as diagnostics:
        process = subprocess.Popen([executable, '--profile', 'sdk'], cwd=root, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=diagnostics, text=True)
        def receive():
            for line in process.stdout:
                try:
                    frames.put(json.loads(line))
                except ValueError as error:
                    failures.append(str(error))
            frames.put(None)
        threading.Thread(target=receive, daemon=True).start()
        def send(method, identifier, params):
            process.stdin.write(json.dumps(dict(jsonrpc='2.0', method=method, id=identifier, params=params))+'\n')
            process.stdin.flush()
        try:
            send('initialize', 'init', dict(cwd=str(root), provider='deepseek-official', model='bounded-test', maxTokens=1000))
            while True:
                frame = frames.get(timeout=30)
                assert frame is not None, 'SDK exited during initialize'
                if frame.get('id') == 'init':
                    assert 'result' in frame, frame
                    break
            send('session/prompt', 'prompt', dict(sessionId='policy-probe', contentBlocks=[dict(type='text', text='Follow the tool requests and finish.')]))
            codes, capacities, reasons = [], [], []
            while True:
                frame = frames.get(timeout=45)
                assert frame is not None, 'SDK exited before terminal status'
                params = frame.get('params', {})
                event = params.get('event', {})
                data = event.get('data', {})
                if event.get('type') == 'tool/result':
                    codes.append(data.get('error', {}).get('code', 'OK'))
                if event.get('type') == 'request/context':
                    capacities.append(data.get('contextWindow'))
                if event.get('type') == 'turn/end':
                    reasons.append(data.get('reason', {}).get('kind'))
                if reasons and frame.get('method') == 'session.status' and params.get('status') == 'idle':
                    break
            assert codes == ['FS_NOT_OBSERVED', 'OK', 'FS_STALE_VERSION', 'OK', 'OK'], codes
            assert capacities and all(c == 15360 for c in capacities), capacities
            assert all(c['max_tokens'] == 1000 for c in calls)
            assert target.read_text() == 'after'
            assert reasons == ['completed'], reasons
            assert not failures, failures
            send('shutdown', 'stop', {})
            assert process.wait(timeout=10) == 0
            print(json.dumps(dict(success=True, toolCodes=codes, contextWindows=capacities, maxTokens=1000, modelRequests=len(calls))))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            server.shutdown()


if __name__ == '__main__':
    with tempfile.TemporaryDirectory(prefix='dsh-policy-') as directory:
        probe(str(Path(sys.argv[1]).resolve()), Path(directory))
