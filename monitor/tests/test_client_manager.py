import multiprocessing
import time
import unittest
from urllib.parse import parse_qs, urlparse

from bili_monitor.client import BiliClient, SourceError, SourceUnavailable
from bili_monitor.runtime import Manager


class ClientTest(unittest.TestCase):
    def test_latest_fifty_before_filter_and_incomplete_page(self):
        calls = []

        def transport(url, headers):
            path = urlparse(url).path
            if path.endswith("/nav"):
                return {"code": 0, "data": {"wbi_img": {
                    "img_url": "https://example.test/" + "a" * 32 + ".png",
                    "sub_url": "https://example.test/" + "b" * 32 + ".png"}}}
            calls.append(url)
            offset = parse_qs(urlparse(url).query).get("offset", [None])[0]
            if offset is None:
                return {"code": 0, "data": {"items": [{"id_str": str(i)} for i in range(1, 31)],
                                            "has_more": True, "offset": "next"}}
            return {"code": 0, "data": {"items": [{"id_str": str(i)} for i in range(31, 61)],
                                        "has_more": True, "offset": "third"}}

        client = BiliClient("", transport)
        self.assertEqual([item["id_str"] for item in client.latest_fifty("123")][-1], "50")
        self.assertEqual(len(calls), 2)

        def incomplete(url, headers):
            if urlparse(url).path.endswith("/nav"):
                return transport(url, headers)
            return {"code": 0, "data": {"items": [], "has_more": True, "offset": "next"}}

        with self.assertRaises(SourceError):
            BiliClient("", incomplete).latest_fifty("123")

    def test_transient_empty_comment_page_and_mid_page_failure(self):
        def response(url, headers):
            page = int(parse_qs(urlparse(url).query)["pn"][0])
            if page == 1:
                return {"code": 0, "data": {"page": {"count": 25},
                                            "replies": [{"rpid": i} for i in range(20)]}}
            return {"code": 0, "data": {"page": {"count": 25}, "replies": []}}

        with self.assertRaisesRegex(SourceError, "瞬时空"):
            BiliClient("", response).replies("1", 11, "2")

        def failure(url, headers):
            if parse_qs(urlparse(url).query)["pn"][0] == "2":
                raise SourceError("超时")
            return response(url, headers)

        with self.assertRaises(SourceError):
            BiliClient("", failure).replies("1", 11, "2")

    def test_only_explicit_missing_detail_is_unavailable(self):
        with self.assertRaises(SourceError) as absent:
            BiliClient("", lambda url, headers: {"code": 0, "data": {}}).detail("123")
        self.assertNotIsInstance(absent.exception, SourceUnavailable)
        with self.assertRaises(SourceUnavailable):
            BiliClient("", lambda url, headers: {"code": -404}).detail("123")

    def test_root_count_includes_nested_and_transient_empty_tail_is_retried(self):
        page_two_calls = [0]

        def transport(url, headers):
            page = int(parse_qs(urlparse(url).query)["pn"][0])
            if page == 1:
                replies = [{"rpid": i} for i in range(1, 21)]
            elif page == 2:
                page_two_calls[0] += 1
                replies = [] if page_two_calls[0] == 1 else [{"rpid": 21}]
            else:
                replies = []
            return {"code": 0, "data": {"page": {"count": 50, "size": 20},
                                        "replies": replies}}

        roots = BiliClient("", transport).roots("1", 11, full=True)
        self.assertEqual(len(roots), 21)
        self.assertEqual(page_two_calls[0], 2)


class Event:
    def __init__(self):
        self.set_called = False

    def set(self):
        self.set_called = True


class Process:
    def __init__(self, **kwargs):
        self.args = kwargs["args"]
        self.alive = False
        self.started = False

    def start(self):
        self.started = True
        self.alive = True

    def is_alive(self):
        return self.alive

    def join(self, timeout=None):
        pass


class Context:
    def __init__(self):
        self.processes = []

    def Event(self):
        return Event()

    def Process(self, **kwargs):
        process = Process(**kwargs)
        self.processes.append(process)
        return process


class Internal:
    def __init__(self):
        self.uids = ["1"]

    def enabled_uids(self):
        return self.uids


def waiting_worker(uid, stop_event, base_url):
    stop_event.wait(10)


class ManagerTest(unittest.TestCase):
    def test_exactly_one_child_disable_and_backoff(self):
        internal, context = Internal(), Context()
        instant = [0]
        manager = Manager(internal, "http://localhost:8080", context, clock=lambda: instant[0])
        manager.reconcile()
        manager.reconcile()
        self.assertEqual(len(context.processes), 1)
        self.assertEqual(context.processes[0].args[0], "1")
        self.assertEqual(len(context.processes[0].args), 3)  # Cookie only in environment.
        context.processes[0].alive = False
        manager.reconcile()
        self.assertEqual(len(context.processes), 1)
        instant[0] = 3
        manager.reconcile()
        self.assertEqual(len(context.processes), 2)
        internal.uids = []
        manager.reconcile()
        self.assertTrue(context.processes[1].args[1].set_called)
        internal.uids = ["1"]
        manager.reconcile()
        self.assertEqual(len(context.processes), 2)  # Stop request has not exited yet.
        context.processes[1].alive = False
        manager.reconcile()
        self.assertEqual(len(context.processes), 3)  # Intentional stop has no crash backoff.

    def test_spawned_child_stops_before_replacement(self):
        internal = Internal()
        manager = Manager(internal, "http://localhost:8080",
                          context=multiprocessing.get_context("spawn"),
                          worker_target=waiting_worker)
        try:
            manager.reconcile()
            first = manager.children["1"][0]
            manager.reconcile()
            self.assertEqual(manager.children["1"][0].pid, first.pid)
            internal.uids = []
            deadline = time.monotonic() + 5
            while "1" in manager.children and time.monotonic() < deadline:
                manager.reconcile()
                time.sleep(0.05)
            self.assertNotIn("1", manager.children)
            self.assertFalse(first.is_alive())
            internal.uids = ["1"]
            manager.reconcile()
            self.assertNotEqual(manager.children["1"][0].pid, first.pid)
        finally:
            for process, event in manager.children.values():
                event.set()
                process.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
