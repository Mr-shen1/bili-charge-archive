import io
import json
import unittest
from urllib.error import HTTPError

from bili_monitor.delivery import DeliveryWorker, FeishuSender, SendError


class Response(io.BytesIO):
    status = 200

    def __init__(self, data, content_type="application/json"):
        super().__init__(data)
        self.headers = {"Content-Type": content_type}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class Opener:
    def __init__(self, fail_upload=False):
        self.fail_upload = fail_upload
        self.calls = []

    def open(self, request, timeout=None):
        url = request.full_url
        self.calls.append(url)
        if url.endswith("tenant_access_token/internal"):
            return Response(b'{"code":0,"tenant_access_token":"test-token","expire":7200}')
        if url.startswith("https://i0.hdslb.com/"):
            return Response(b"fake-image", "image/jpeg")
        if url.endswith("/im/v1/images"):
            if self.fail_upload:
                raise HTTPError(url, 500, "error", {}, io.BytesIO())
            return Response(b'{"code":0,"data":{"image_key":"img_test"}}')
        if "/bot/v2/hook/" in url:
            self.payload = json.loads(request.data)
            return Response(b'{"code":0,"msg":"success"}')
        raise AssertionError(url)


def claim(images=None):
    return {"eventId": 1, "role": "ALL", "leaseToken": "worker:lease", "upUid": "123",
            "dynamicId": "456", "commentRpid": None, "eventType": "DYNAMIC",
            "messageText": "正文", "imageSourceUrls": images or [],
            "webhook": "https://open.feishu.cn/open-apis/bot/v2/hook/test-only"}


class Internal:
    def __init__(self, jobs):
        self.jobs = jobs
        self.results = []

    def claim(self, worker_id):
        return self.jobs.pop(0) if self.jobs else None

    def delivery_result(self, job, worker_id, success, error=None):
        self.results.append((job["eventId"], success, error))


class DeliveryTest(unittest.TestCase):
    def test_text_and_image_payloads(self):
        opener = Opener()
        sender = FeishuSender("app-id", "app-secret", opener)
        sender.send(claim())
        self.assertEqual(opener.payload["msg_type"], "text")
        self.assertIn("https://t.bilibili.com/456", opener.payload["content"]["text"])

        sender.send(claim(["https://i0.hdslb.com/bfs/test.jpg"]))
        self.assertEqual(opener.payload["msg_type"], "post")
        paragraphs = opener.payload["content"]["post"]["zh_cn"]["content"]
        self.assertEqual(paragraphs[-1][0]["image_key"], "img_test")
        self.assertEqual(opener.calls.count("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"), 1)

    def test_upload_failure_is_reported_without_webhook_send(self):
        opener = Opener(fail_upload=True)
        internal = Internal([claim(["https://i0.hdslb.com/bfs/test.jpg"])])
        worker = DeliveryWorker(internal, FeishuSender("app-id", "app-secret", opener), "worker")
        self.assertTrue(worker.once())
        self.assertEqual(internal.results, [(1, False, "UPLOAD_HTTP_500")])
        self.assertFalse(any("/bot/v2/hook/" in url for url in opener.calls))

    def test_success_then_result_crash_is_not_reported_as_send_failure(self):
        class BrokenInternal(Internal):
            def delivery_result(self, job, worker_id, success, error=None):
                raise RuntimeError("database offline")

        internal = BrokenInternal([claim()])
        opener = Opener()
        worker = DeliveryWorker(internal, FeishuSender(opener=opener), "worker")
        with self.assertRaises(RuntimeError):
            worker.once()
        self.assertEqual(sum("/bot/v2/hook/" in url for url in opener.calls), 1)

    def test_rejects_non_bilibili_image_and_non_feishu_webhook(self):
        sender = FeishuSender("app-id", "app-secret", Opener())
        with self.assertRaisesRegex(SendError, "IMAGE_URL_INVALID"):
            sender.payload(claim(["https://attacker.example/p.jpg"]))
        bad = claim()
        bad["webhook"] = "https://attacker.example/hook"
        with self.assertRaisesRegex(SendError, "WEBHOOK_INVALID"):
            sender.send(bad)


if __name__ == "__main__":
    unittest.main()
