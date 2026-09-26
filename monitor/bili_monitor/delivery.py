"""飞书发送器：认领在数据库事务中，外部发送后再逐目标确认。"""

from __future__ import annotations

import json
import logging
import os
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


LOG = logging.getLogger(__name__)


class SendError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class FeishuSender:
    def __init__(self, app_id: str = "", app_secret: str = "", opener=None):
        self.app_id = app_id
        self.app_secret = app_secret
        self.opener = opener or build_opener(NoRedirect)
        self.token = ""
        self.token_host = ""
        self.token_until = 0.0

    def _json(self, url: str, payload: dict, headers: dict | None = None) -> dict:
        req = Request(url, json.dumps(payload, ensure_ascii=False).encode(),
                      {"Content-Type": "application/json; charset=utf-8", **(headers or {})},
                      method="POST")
        try:
            with self.opener.open(req, timeout=12) as response:
                if response.status != 200:
                    raise SendError(f"HTTP_{response.status}")
                data = json.load(response)
        except (HTTPError, URLError, TimeoutError, ValueError) as error:
            raise SendError(f"HTTP_{getattr(error, 'code', 'FAILED')}") from None
        if not isinstance(data, dict):
            raise SendError("INVALID_RESPONSE")
        code = data.get("code", data.get("StatusCode"))
        if code is None or str(code) != "0":
            raise SendError("FEISHU_CODE_" + str(code)[:20].replace("-", "_"))
        return data

    def _token(self, host: str, refresh: bool = False) -> str:
        if not refresh and self.token and self.token_host == host and time.monotonic() < self.token_until:
            return self.token
        if not self.app_id or not self.app_secret:
            raise SendError("FEISHU_APP_CREDENTIALS_MISSING")
        data = self._json(f"https://{host}/open-apis/auth/v3/tenant_access_token/internal",
                          {"app_id": self.app_id, "app_secret": self.app_secret})
        token = data.get("tenant_access_token")
        if not isinstance(token, str) or not token:
            raise SendError("TOKEN_MISSING")
        self.token = token
        self.token_host = host
        self.token_until = time.monotonic() + max(0, int(data.get("expire", 7200)) - 60)
        return token

    def _image_bytes(self, raw: str) -> tuple[bytes, str, str]:
        if raw.startswith("//"):
            raw = "https:" + raw
        parsed = urlsplit(raw)
        host = (parsed.hostname or "").lower()
        try:
            port = parsed.port
        except ValueError:
            raise SendError("IMAGE_URL_INVALID") from None
        if (parsed.scheme != "https" or not host.endswith(".hdslb.com")
                or port not in (None, 443) or parsed.username or parsed.password):
            raise SendError("IMAGE_URL_INVALID")
        url = urlunsplit(("https", host, parsed.path, parsed.query, ""))
        req = Request(url, headers={"Referer": "https://www.bilibili.com/", "User-Agent": "Mozilla/5.0"})
        try:
            with self.opener.open(req, timeout=15) as response:
                if response.status != 200:
                    raise SendError(f"IMAGE_HTTP_{response.status}")
                kind = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
                if not kind.startswith("image/"):
                    raise SendError("IMAGE_CONTENT_TYPE")
                content = response.read(10 * 1024 * 1024 + 1)
        except (HTTPError, URLError, TimeoutError) as error:
            raise SendError(f"IMAGE_HTTP_{getattr(error, 'code', 'FAILED')}") from None
        if not content or len(content) > 10 * 1024 * 1024:
            raise SendError("IMAGE_SIZE_INVALID")
        name = os.path.basename(parsed.path) or "image.jpg"
        name = "".join(char for char in name if char.isalnum() or char in "._-")[:100] or "image.jpg"
        return content, kind, name

    def _upload(self, raw: str, host: str) -> str:
        content, kind, name = self._image_bytes(raw)
        boundary = "----bili-" + uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image_type\"\r\n\r\nmessage\r\n"
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{name}\"\r\n"
                f"Content-Type: {kind}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
        for attempt in range(2):
            token = self._token(host, refresh=attempt == 1)
            request = Request(f"https://{host}/open-apis/im/v1/images", body,
                              {"Authorization": f"Bearer {token}",
                               "Content-Type": f"multipart/form-data; boundary={boundary}"}, method="POST")
            try:
                with self.opener.open(request, timeout=15) as response:
                    data = json.load(response)
            except HTTPError as error:
                if error.code == 401 and attempt == 0:
                    continue
                raise SendError(f"UPLOAD_HTTP_{error.code}") from None
            except (URLError, TimeoutError, ValueError):
                raise SendError("UPLOAD_FAILED") from None
            if data.get("code") == 234002 and attempt == 0:
                continue
            key = (data.get("data") or {}).get("image_key")
            if data.get("code") != 0 or not isinstance(key, str) or not key:
                raise SendError("UPLOAD_CODE_" + str(data.get("code"))[:20].replace("-", "_"))
            return key
        raise SendError("UPLOAD_AUTH_FAILED")

    def payload(self, claim: dict, host="open.feishu.cn") -> dict:
        kind = claim["eventType"]
        dynamic_id = claim.get("dynamicId")
        parts = []
        if kind == "DYNAMIC":
            parts = [f"动态 ID: {dynamic_id}", f"UP主 UID: {claim['upUid']}",
                     f"内容: {claim['messageText']}", f"链接: https://t.bilibili.com/{dynamic_id}"]
        elif kind == "COMMENT":
            parts = [f"动态 ID: {dynamic_id}", f"评论 ID: {claim['commentRpid']}",
                     f"内容: {claim['messageText']}", f"链接: https://t.bilibili.com/{dynamic_id}"]
        else:
            parts = [claim["messageText"]]
        images = claim.get("imageSourceUrls") or []
        text = "\n".join(parts)
        if not images:
            return {"msg_type": "text", "content": {"text": text}}
        keys = [self._upload(url, host) for url in images]
        paragraphs = [[{"tag": "text", "text": line or " "}] for line in parts]
        paragraphs.extend([{"tag": "img", "image_key": key}] for key in keys)
        return {"msg_type": "post", "content": {"post": {"zh_cn": {
            "title": "B站动态" if kind == "DYNAMIC" else "B站评论",
            "content": paragraphs}}}}

    def send(self, claim: dict):
        webhook = claim["webhook"]
        parsed = urlsplit(webhook)
        if (parsed.scheme != "https" or parsed.hostname not in ("open.feishu.cn", "open.larksuite.com")
                or not parsed.path.startswith("/open-apis/bot/v2/hook/")):
            raise SendError("WEBHOOK_INVALID")
        self._json(webhook, self.payload(claim, parsed.hostname))


class DeliveryWorker:
    def __init__(self, internal, sender, worker_id=None):
        self.internal = internal
        self.sender = sender
        self.worker_id = worker_id or uuid.uuid4().hex[:24]

    def once(self) -> bool:
        claim = self.internal.claim(self.worker_id)
        if claim is None:
            return False
        try:
            self.sender.send(claim)
        except SendError as error:
            self.internal.delivery_result(claim, self.worker_id, False, str(error))
        except Exception as error:
            self.internal.delivery_result(claim, self.worker_id, False,
                                          "SEND_EXCEPTION_" + type(error).__name__.upper())
        else:
            # A crash here leaves SENDING until lease expiry; retry may duplicate, never drop.
            self.internal.delivery_result(claim, self.worker_id, True)
        return True

    def run(self, stop_event):
        while not stop_event.is_set():
            try:
                sent = self.once()
            except Exception as error:
                LOG.warning("飞书投递轮询失败：%s", type(error).__name__)
                sent = False
            stop_event.wait(1 if sent else 3)
