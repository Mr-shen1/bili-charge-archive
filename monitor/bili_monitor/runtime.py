"""每个启用 UP 一个子进程的管理器。"""

from __future__ import annotations

import logging
import multiprocessing
import os
import signal
import threading
import time

from .client import BiliClient, InternalClient, SourceError
from .scanner import Scanner


LOG = logging.getLogger(__name__)


def worker(uid: str, stop_event, base_url: str):
    internal = InternalClient(base_url, os.environ["MONITOR_API_TOKEN"])
    source = BiliClient(os.environ.get("BILI_COOKIE", ""))
    scanner = Scanner(source, internal, uid, should_stop=stop_event.is_set)
    next_start = 0.0
    while not stop_event.is_set():
        remaining = next_start - time.monotonic()
        if remaining > 0 and stop_event.wait(remaining):
            break
        next_start = time.monotonic() + 15
        try:
            if not internal.config(uid)["enabled"]:
                break
            internal.status(uid, "STARTED")
            complete = scanner.round()
            if complete and not stop_event.is_set():
                internal.status(uid, "SUCCEEDED")
            elif stop_event.is_set():
                break
        except Exception as error:
            # Errors are deliberately short and never include HTTP headers or response bodies.
            message = str(error) if isinstance(error, SourceError) else type(error).__name__
            LOG.warning("UP %s 扫描失败：%s", uid, message)
            try:
                internal.status(uid, "ERROR", message)
            except Exception:
                LOG.warning("UP %s 扫描错误状态写入失败", uid)
        if stop_event.wait(max(0, next_start - time.monotonic())):
            break


class Manager:
    def __init__(self, internal, base_url: str, context=None, clock=time.monotonic,
                 worker_target=worker):
        self.internal = internal
        self.base_url = base_url
        self.context = context or multiprocessing.get_context("spawn")
        self.clock = clock
        self.worker_target = worker_target
        self.children = {}
        self.failures = {}
        self.stopping_since = {}

    def reconcile(self):
        enabled = set(self.internal.enabled_uids())
        for uid, (process, stop_event) in list(self.children.items()):
            if uid not in enabled:
                stop_event.set()
                self.stopping_since.setdefault(uid, self.clock())
                if process.is_alive() and self.clock() - self.stopping_since[uid] >= 60:
                    process.terminate()
                if not process.is_alive():
                    process.join()
                    del self.children[uid]
                    self.failures.pop(uid, None)
                    self.stopping_since.pop(uid, None)
            elif not process.is_alive():
                process.join()
                del self.children[uid]
                if self.stopping_since.pop(uid, None) is None:
                    failures = self.failures.get(uid, (0, 0))[0] + 1
                    self.failures[uid] = (failures, self.clock() + min(60, 2 ** min(failures, 6)))
                    LOG.warning("UP %s 子进程退出，等待退避重启", uid)
        for uid in sorted(enabled):
            if uid in self.children or self.failures.get(uid, (0, 0))[1] > self.clock():
                continue
            stop_event = self.context.Event()
            process = self.context.Process(target=self.worker_target,
                                           args=(uid, stop_event, self.base_url),
                                           name=f"up-{uid}")
            process.start()
            self.children[uid] = (process, stop_event)

    def run(self, stop_event=None):
        while stop_event is None or not stop_event.is_set():
            try:
                self.reconcile()
            except Exception as error:
                LOG.warning("启用 UP 清单暂不可用：%s", type(error).__name__)
            if stop_event is not None:
                stop_event.wait(15)
            else:
                time.sleep(15)
        for process, event in self.children.values():
            event.set()
        for process, _ in self.children.values():
            process.join(timeout=30)
            if process.is_alive():
                process.terminate()
                process.join()


def main():
    logging.basicConfig(level=logging.INFO)
    base_url = os.environ.get("MONITOR_INTERNAL_URL", "http://spring-app:8080")
    manager = Manager(InternalClient(base_url, os.environ["MONITOR_API_TOKEN"]), base_url)
    stop_event = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop_event.set())
    signal.signal(signal.SIGINT, lambda *_: stop_event.set())
    manager.run(stop_event)
