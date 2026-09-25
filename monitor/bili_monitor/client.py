"""B 站读取与内部批次传输；认证值只从环境读取。"""

from __future__ import annotations

import hashlib
import json
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


MIXIN = (46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
         27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
         37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
         22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52)


class SourceError(RuntimeError):
    pass


class SourceUnavailable(SourceError):
    """A detail request explicitly says that its source no longer exists."""


def request_json(url: str, headers: dict, payload: dict | None = None) -> dict:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = Request(url, data=body, headers=headers,
                  method="POST" if body is not None else "GET")
    try:
        with urlopen(req, timeout=20) as response:
            return json.load(response)
    except HTTPError as error:
        if error.code == 404 and url.startswith("https://api.bilibili.com/"):
            raise SourceUnavailable("来源明确返回 404") from None
        if "/internal/" in url:
            try:
                failure = json.loads(error.read(2048))
                code = str(failure.get("code") or "UNKNOWN")[:40]
                message = str(failure.get("message") or "")[:160]
                raise SourceError(f"内部接口 HTTP {error.code} {code}: {message}") from None
            except (ValueError, AttributeError):
                pass
        raise SourceError(f"HTTP {error.code}") from None
    except (URLError, TimeoutError, ValueError) as error:
        raise SourceError(type(error).__name__) from None


class BiliClient:
    API = "https://api.bilibili.com"

    def __init__(self, cookie: str, transport=request_json):
        self.transport = transport
        self.headers = {"User-Agent": "Mozilla/5.0", "Referer": "https://www.bilibili.com/"}
        if cookie:
            self.headers["Cookie"] = cookie
        self._wbi_key = None
        self._wbi_day = None
        self._root_snapshots = {}

    def _get(self, path: str, params: dict | None = None, *, detail=False):
        url = self.API + path + ("?" + urlencode(params) if params else "")
        try:
            data = self.transport(url, self.headers)
        except SourceUnavailable:
            if detail:
                raise
            raise SourceError("列表请求失败") from None
        code = data.get("code")
        if code != 0:
            if detail and code in (-404, 404):
                raise SourceUnavailable("来源明确返回不存在")
            raise SourceError(f"B 站 API 错误 {code}")
        if not isinstance(data.get("data"), dict):
            raise SourceError("B 站响应缺少 data")
        return data["data"]

    def _sign(self, params: dict) -> dict:
        day = time.strftime("%Y%m%d")
        if self._wbi_day != day:
            images = self._get("/x/web-interface/nav").get("wbi_img") or {}
            keys = []
            for key in ("img_url", "sub_url"):
                match = re.search(r"/([0-9a-fA-F]+)\.png", images.get(key, ""))
                if not match:
                    raise SourceError("WBI 密钥无效")
                keys.append(match.group(1))
            raw = "".join(keys)
            self._wbi_key = "".join(raw[i] for i in MIXIN)[:32]
            self._wbi_day = day
        signed = {key: "".join(ch for ch in str(value) if ch not in "!'()*")
                  for key, value in {**params, "wts": int(time.time())}.items()}
        signed = dict(sorted(signed.items()))
        signed["w_rid"] = hashlib.md5((urlencode(signed) + self._wbi_key).encode()).hexdigest()
        return signed

    def latest_fifty(self, uid: str) -> list[dict]:
        raw = []
        offset = None
        while len(raw) < 50:
            params = {"host_mid": uid, "platform": "web", "timezone_offset": -480,
                      "web_location": "333.1387", "features": "itemOpusStyle,listOnlyfans,commentsNewVersion"}
            if offset:
                params["offset"] = offset
            page = self._get("/x/polymer/web-dynamic/v1/feed/space", self._sign(params))
            items = page.get("items")
            if not isinstance(items, list):
                raise SourceError("动态列表缺少 items")
            raw.extend(items)
            if not page.get("has_more"):
                break
            next_offset = page.get("offset")
            if not items or not next_offset or next_offset == offset:
                raise SourceError("动态列表分页不完整")
            offset = next_offset
        return raw[:50]

    def detail(self, dynamic_id: str) -> dict:
        data = self._get("/x/polymer/web-dynamic/v1/detail",
                         {"id": dynamic_id, "timezone_offset": -480, "platform": "web"}, detail=True)
        item = data.get("item")
        if not isinstance(item, dict):
            raise SourceError("动态详情缺少 item，无法确认来源状态")
        return item

    def _pages(self, path: str, params: dict, *, max_pages=10000):
        page_num = 1
        first_count = first_size = None
        while page_num <= max_pages:
            data = self._get(path, {**params, "pn": page_num, "ps": 20})
            replies = data.get("replies") or []
            if not isinstance(replies, list):
                raise SourceError("评论分页格式无效")
            page = data.get("page") or {}
            count = page.get("count")
            size = int(page.get("size") or 20)
            if page_num == 1:
                first_count, first_size = count, size
            elif count != first_count or size != first_size:
                raise SourceError("评论分页快照发生变化")
            if count is not None and (page_num - 1) * size < int(count) and not replies:
                raise SourceError("评论分页瞬时空响应")
            yield replies
            if count is not None:
                if page_num * size >= int(count):
                    return
            elif len(replies) < size:
                return
            if not replies:
                raise SourceError("评论分页中断")
            page_num += 1
        raise SourceError("评论分页超出上限")

    def roots(self, oid: str, comment_type: int, *, full: bool) -> list[dict]:
        result = []
        initial_count = initial_size = None
        for page_num in range(1, 10001):
            params = {"oid": oid, "type": comment_type, "sort": 0, "pn": page_num, "ps": 20}
            data = self._get("/x/v2/reply", params)
            page = data.get("page") or {}
            count = int(page.get("count") or 0)
            size = int(page.get("size") or 20)
            if page_num == 1:
                initial_count, initial_size = count, size
                result.extend(data.get("top_replies") or [])
            elif count != initial_count or size != initial_size:
                raise SourceError("根评论分页快照发生变化")
            replies = data.get("replies") or []
            if not isinstance(replies, list):
                raise SourceError("根评论分页格式无效")
            if page_num == 1:
                self._root_snapshots[(oid, comment_type)] = (
                    count, tuple(str(r.get("rpid_str") or r.get("rpid")) for r in replies))
            if not replies and count > 0:
                # Root page.count includes nested replies; verify an empty tail instead of
                # using that total as the number of root pages.
                retry = self._get("/x/v2/reply", params)
                if int((retry.get("page") or {}).get("count") or 0) != count:
                    raise SourceError("根评论分页快照发生变化")
                replies = retry.get("replies") or []
                if not replies and page_num == 1:
                    raise SourceError("根评论首页瞬时空响应")
                if not replies:
                    return result
            result.extend(replies)
            if not full and page_num >= 2:
                return result
            if len(replies) < size or count == 0:
                if full and replies and len(replies) < size:
                    tail = self._get("/x/v2/reply", {**params, "pn": page_num + 1})
                    if tail.get("replies"):
                        raise SourceError("根评论尾页不完整")
                return result
        raise SourceError("根评论分页超出上限")

    def validate_root_snapshot(self, oid: str, comment_type: int):
        expected = self._root_snapshots[(oid, comment_type)]
        page = self._get("/x/v2/reply",
                         {"oid": oid, "type": comment_type, "sort": 0, "pn": 1, "ps": 20})
        actual = (int((page.get("page") or {}).get("count") or 0),
                  tuple(str(r.get("rpid_str") or r.get("rpid"))
                        for r in page.get("replies") or []))
        if actual != expected:
            raise SourceError("评论快照在完整校准期间发生变化")

    def replies(self, oid: str, comment_type: int, root: str) -> list[dict]:
        result = []
        for page in self._pages("/x/v2/reply/reply",
                                {"oid": oid, "type": comment_type, "root": root}):
            result.extend(page)
        return result


class InternalClient:
    def __init__(self, base_url: str, token: str, transport=request_json):
        if len(token) < 32:
            raise ValueError("MONITOR_API_TOKEN 必须至少 32 字符")
        self.base_url = base_url.rstrip("/")
        self.headers = {"X-Monitor-Token": token, "Content-Type": "application/json"}
        self.transport = transport

    def call(self, path: str, payload: dict | None = None):
        response = self.transport(self.base_url + path, self.headers, payload)
        if not isinstance(response, dict) or "data" not in response:
            raise SourceError("内部接口响应无效")
        return response["data"]

    def enabled_uids(self):
        return self.call("/internal/ups?enabled=true")

    def config(self, uid):
        return self.call(f"/internal/ups/{uid}/config")

    def batch(self, uid, payload):
        return self.call(f"/internal/ups/{uid}/batches", payload)

    def status(self, uid, kind, message=None):
        return self.call(f"/internal/ups/{uid}/worker-status",
                         {"kind": kind, "message": message})
