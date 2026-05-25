#!/usr/bin/env python3
"""
MCP 工具同步脚本（Webhook 模式）
GitHub push 时触发同步，无 push 不消耗资源
首次运行自动初始化 sparse clone

部署步骤：
  1. GitHub 仓库 → Settings → Webhooks → Add webhook
     Payload URL: http://<服务器IP>:<WEBHOOK_PORT>/webhook
     Content type: application/json
     Secret: 与 WEBHOOK_SECRET 保持一致
     事件: Just the push event
  2. python3 sync_github.py

启动命令：
nohup python3 /app/scripts/utils/sync_github.py >> /app/scripts/utils/sync_github.log 2>&1 &
cat /app/scripts/utils/sync_github.log

"""
import hashlib
import hmac
import json
import logging
import os
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

# ── 配置（按需修改） ────────────────────────────────────────────────────
_token = os.environ.get("GITHUB_TOKEN", "")
if not _token:
    raise RuntimeError("环境变量 GITHUB_TOKEN 未设置，无法访问私有仓库")
REPO_URL = f"https://{_token}@github.com/shenyuya/AILab.git"
REPO_BRANCH = "main"
CACHE_DIR = "/app/scripts/.sync_cache"           # sparse clone 缓存目录

WEBHOOK_PORT = 18089
WEBHOOK_SECRET = "PingAn%10"           # 与 GitHub Webhook Secret 保持一致

# 源目录（repo 内相对路径） → 目标目录（服务器绝对路径）
SYNC_MAPPINGS = [
    ("common", "/app/scripts/common"),
    ("ddl",    "/app/scripts/ddl"),
]

PIP_BIN        = "/app/scripts/python-env/mcp-env/bin/pip"
REQUIREMENTS   = "/app/scripts/common/requirement.txt"
PYTHON_BIN     = "/app/scripts/python-env/mcp-env/bin/python3"
MIGRATE_SCRIPT = "/app/scripts/ddl/migrate.py"
# ──────────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [sync] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger(__name__)


# ── Git 操作 ────────────────────────────────────────────────────────────

def run(cmd: list[str], cwd: str = None, timeout: int = 120) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    # 若设置了代理则透传给 git
    for key in ("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY", "ALL_PROXY"):
        if key in os.environ:
            env[key] = os.environ[key]
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, env=env)


def init_sparse_clone() -> None:
    log.info("初始化 sparse clone → %s", CACHE_DIR)
    os.makedirs(CACHE_DIR, exist_ok=True)

    run(["git", "init"], cwd=CACHE_DIR)
    run(["git", "remote", "add", "origin", REPO_URL], cwd=CACHE_DIR)
    run(["git", "config", "core.sparseCheckout", "true"], cwd=CACHE_DIR)

    sparse_file = os.path.join(CACHE_DIR, ".git", "info", "sparse-checkout")
    with open(sparse_file, "w") as f:
        for src, _ in SYNC_MAPPINGS:
            f.write(src.rstrip("/") + "/\n")

    result = run(["git", "pull", "--depth=1", "origin", REPO_BRANCH], cwd=CACHE_DIR)
    if result.returncode != 0:
        raise RuntimeError(f"sparse clone 失败:\n{result.stderr}")
    log.info("sparse clone 完成")


def _update_sparse_checkout() -> None:
    sparse_file = os.path.join(CACHE_DIR, ".git", "info", "sparse-checkout")
    with open(sparse_file, "w") as f:
        for src, _ in SYNC_MAPPINGS:
            f.write(src.rstrip("/") + "/\n")


def pull() -> bool:
    _update_sparse_checkout()
    result = run(["git", "pull", "origin", REPO_BRANCH], cwd=CACHE_DIR)
    if result.returncode != 0:
        log.error("git pull 失败: %s", result.stderr.strip())
        return False
    updated = "Already up to date." not in result.stdout
    if updated:
        log.info("拉取成功: %s", result.stdout.strip())
    return updated


def sync_dirs() -> None:
    for src_rel, dst in SYNC_MAPPINGS:
        src = os.path.join(CACHE_DIR, src_rel)
        if not os.path.exists(src):
            log.warning("源目录不存在，跳过: %s", src)
            continue
        changed = _sync_dir(src, dst)
        if changed == 0:
            log.info("%s 无变化", src_rel)


IGNORE_DIRS = {"__pycache__", ".git"}


def _sync_dir(src: str, dst: str) -> int:
    os.makedirs(dst, exist_ok=True)
    changed = 0

    src_names = {n for n in os.listdir(src) if n not in IGNORE_DIRS}
    dst_names = {n for n in (os.listdir(dst) if os.path.exists(dst) else []) if n not in IGNORE_DIRS}

    for name in src_names:
        src_path = os.path.join(src, name)
        dst_path = os.path.join(dst, name)
        if os.path.isdir(src_path):
            changed += _sync_dir(src_path, dst_path)
        elif not _same_file(src_path, dst_path):
            shutil.copy2(src_path, dst_path)
            log.info("同步: %s → %s", src_path, dst_path)
            changed += 1

    for name in dst_names - src_names:
        dst_path = os.path.join(dst, name)
        if os.path.isdir(dst_path):
            shutil.rmtree(dst_path)
            log.info("删除目录: %s", dst_path)
        else:
            os.remove(dst_path)
            log.info("删除: %s", dst_path)
        changed += 1

    return changed


def _file_hash(path: str) -> str:
    if not os.path.exists(path):
        return ""
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def install_requirements() -> None:
    log.info("检测到 requirements 变化，开始安装依赖")
    result = run([PIP_BIN, "install", "-r", REQUIREMENTS], timeout=600)
    if result.returncode == 0:
        log.info("依赖安装成功")
    else:
        log.error("依赖安装失败:\n%s", result.stderr.strip())


def run_migrate() -> None:
    log.info("检测到 migrations 变化，开始执行数据库迁移")
    result = run([PYTHON_BIN, MIGRATE_SCRIPT])
    if result.returncode == 0:
        log.info("数据库迁移成功:\n%s", result.stdout.strip())
    else:
        log.error("数据库迁移失败:\n%s", result.stderr.strip())


def _dir_hash(path: str) -> str:
    """对目录下所有文件内容做聚合 hash，用于检测目录整体变化"""
    if not os.path.exists(path):
        return ""
    h = hashlib.md5()
    for fname in sorted(os.listdir(path)):
        fpath = os.path.join(path, fname)
        if os.path.isfile(fpath):
            with open(fpath, "rb") as f:
                h.update(fname.encode())
                h.update(f.read())
    return h.hexdigest()


def _same_file(a: str, b: str) -> bool:
    if not os.path.exists(b):
        return False
    if os.path.getsize(a) != os.path.getsize(b):
        return False
    with open(a, "rb") as fa, open(b, "rb") as fb:
        return fa.read() == fb.read()


# ── Webhook 处理 ────────────────────────────────────────────────────────

def _verify_signature(secret: str, payload: bytes, signature: str) -> bool:
    expected = "sha256=" + hmac.new(
        secret.encode(), payload, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


class WebhookHandler(BaseHTTPRequestHandler):

    def do_POST(self):
        if self.path != "/webhook":
            self._reply(404, "Not Found")
            return

        length = int(self.headers.get("Content-Length", 0))
        payload = self.rfile.read(length)

        # 验证签名
        signature = self.headers.get("X-Hub-Signature-256", "")
        if WEBHOOK_SECRET and not _verify_signature(WEBHOOK_SECRET, payload, signature):
            log.warning("签名验证失败，拒绝请求")
            self._reply(401, "Unauthorized")
            return

        # 只处理 push 事件
        event = self.headers.get("X-GitHub-Event", "")
        if event != "push":
            self._reply(200, f"ignored event: {event}")
            return

        # 只处理目标分支
        try:
            body = json.loads(payload)
            ref = body.get("ref", "")
        except Exception:
            self._reply(400, "invalid json")
            return

        if ref != f"refs/heads/{REPO_BRANCH}":
            self._reply(200, f"ignored ref: {ref}")
            return

        log.info("收到 push 事件，开始同步")
        self._reply(200, "ok")

        # 异步执行同步（避免阻塞 webhook 响应）
        import threading
        threading.Thread(target=self._do_sync, daemon=True).start()

    def _do_sync(self):
        try:
            if pull():
                req_hash_before = _file_hash(REQUIREMENTS)
                migrations_dir = os.path.join(os.path.dirname(MIGRATE_SCRIPT), "migrations")
                migrations_hash_before = _dir_hash(migrations_dir)
                sync_dirs()
                if _file_hash(REQUIREMENTS) != req_hash_before:
                    install_requirements()
                if _dir_hash(migrations_dir) != migrations_hash_before:
                    run_migrate()
            else:
                log.info("无新内容，跳过同步")
        except Exception as e:
            log.error("同步失败: %s", e)

    def _reply(self, code: int, msg: str):
        body = msg.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # 屏蔽默认的 HTTP 访问日志


# ── 入口 ────────────────────────────────────────────────────────────────

def main():
    if not os.path.exists(os.path.join(CACHE_DIR, ".git")):
        init_sparse_clone()
        sync_dirs()

    server = HTTPServer(("0.0.0.0", WEBHOOK_PORT), WebhookHandler)
    log.info("Webhook 监听 :%d，等待 GitHub push 事件", WEBHOOK_PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
