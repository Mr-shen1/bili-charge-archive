"""按 UP 扫描内容；每次持久化都走 Spring 的原子批次接口。"""

from __future__ import annotations

import json
from datetime import datetime, timezone

from .client import SourceError, SourceUnavailable


CONTENT_TYPES = {"DYNAMIC_TYPE_WORD", "DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_ARTICLE"}
MAJOR_TYPES = {"MAJOR_TYPE_OPUS", "MAJOR_TYPE_DRAW"}


def iso(timestamp):
    value = datetime.fromtimestamp(int(timestamp), timezone.utc)
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def utc_from_api(value):
    if value is None:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def numeric(value) -> str:
    text = str(value)
    if not text.isdigit() or not 1 <= len(text) <= 32 or int(text) == 0:
        raise SourceError("来源 ID 无效")
    return text


def parse_dynamic(item: dict, uid: str, *, strict=False) -> dict | None:
    modules = item.get("modules") or {}
    author = modules.get("module_author") or {}
    major = (modules.get("module_dynamic") or {}).get("major") or {}
    charge = (author.get("icon_badge") or {}).get("text") == "充电专属"
    eligible = charge and item.get("type") in CONTENT_TYPES and major.get("type") in MAJOR_TYPES
    if not eligible:
        if strict:
            raise SourceError("固定动态不是充电专属文字或图片")
        return None
    if str(author.get("mid")) != uid:
        if strict:
            raise SourceError("动态作者与 UP 不符")
        return None
    dynamic_id = numeric(item.get("id_str"))
    basic = item.get("basic") or {}
    oid = numeric(basic.get("comment_id_str"))
    comment_type = int(basic.get("comment_type") or 0)
    if not 1 <= comment_type <= 65535:
        raise SourceError("评论目标类型无效")
    opus = major.get("opus") or {}
    draw = major.get("draw") or {}
    summary = opus.get("summary") or {}
    nodes = summary.get("rich_text_nodes") or []
    text = "".join(str(node.get("text") or "") for node in nodes)
    if not text:
        text = str(draw.get("desc") or summary.get("text") or "")
    pictures = opus.get("pics") or draw.get("items") or []
    images = [str(p.get("url") or p.get("src")) for p in pictures if p.get("url") or p.get("src")]
    return {"dynamicId": dynamic_id, "upUid": uid, "title": opus.get("title") or None,
            "text": text, "publishedAt": iso(author.get("pub_ts")),
            "commentOid": oid, "commentType": comment_type, "images": images}


def parse_comment(reply: dict, root_id: str | None) -> dict:
    rpid = numeric(reply.get("rpid_str") or reply.get("rpid"))
    member = reply.get("member") or {}
    author = numeric(member.get("mid"))
    level = (member.get("level_info") or {}).get("current_level") or 0
    content = reply.get("content") or {}
    pictures = content.get("pictures") or content.get("picture") or []
    if isinstance(pictures, dict):
        pictures = [pictures]
    parent = None
    if root_id is not None:
        raw_parent = reply.get("parent")
        parent = numeric(raw_parent) if raw_parent and str(raw_parent) != "0" else root_id
    return {"rpid": rpid, "rootRpid": root_id, "parentRpid": parent,
            "authorMid": author, "authorName": str(member.get("uname") or author),
            "authorAvatarUrl": member.get("avatar") or None, "authorLevel": int(level),
            "text": str(content.get("message") or ""), "publishedAt": iso(reply.get("ctime")),
            "likeCount": int(reply.get("like") or 0), "replyCount": int(reply.get("rcount") or 0),
            "images": [str(p.get("img_src") or p.get("url")) for p in pictures
                       if p.get("img_src") or p.get("url")]}


def candidate(dynamic_id, content, *, comment=False):
    rpid = content["rpid"] if comment else None
    return {"dedupeKey": f"comment:{dynamic_id}:{rpid}" if comment else f"dynamic:{dynamic_id}",
            "type": "COMMENT" if comment else "DYNAMIC", "commentRpid": rpid,
            "text": content["text"], "imageSourceUrls": content["images"]}


class Scanner:
    def __init__(self, source, internal, uid: str, clock=now_iso, should_stop=lambda: False):
        self.source = source
        self.internal = internal
        self.uid = numeric(uid)
        self.clock = clock
        self.should_stop = should_stop

    def round(self):
        config = self.internal.config(self.uid)
        if not config["enabled"] or self.should_stop():
            return False
        scans = {row["dynamicId"]: row for row in config["scans"]}
        raw = self.source.latest_fifty(self.uid)
        dynamics = {}
        for item in raw:
            if self.should_stop():
                return False
            parsed = parse_dynamic(item, self.uid)
            if parsed is not None:
                dynamics[parsed["dynamicId"]] = parsed
        for route in config["fixedRoutes"]:
            if self.should_stop():
                return False
            dynamic_id = numeric(route["dynamicId"])
            if dynamic_id in dynamics:
                continue
            try:
                detail = self.source.detail(dynamic_id)
            except SourceUnavailable:
                if dynamic_id in scans:
                    self.internal.batch(self.uid, {"items": [], "baselineCompletedDynamicIds": [],
                        "availabilityChanges": [{"dynamicId": dynamic_id, "rpid": None,
                                                  "unavailable": True}]})
                continue
            if str(detail.get("id_str")) != dynamic_id:
                raise SourceError("固定动态详情 ID 不符")
            dynamics[dynamic_id] = parse_dynamic(detail, self.uid, strict=True)
        for dynamic in dynamics.values():
            if self.should_stop():
                return False
            self._scan_dynamic(dynamic, scans.get(dynamic["dynamicId"]))
        return True

    def _scan_dynamic(self, dynamic, previous):
        dynamic_id = dynamic["dynamicId"]
        old_state = json.loads(previous["stateJson"]) if previous else {}
        known = set(old_state.get("knownIds") or [])
        known_roots = set(old_state.get("rootIds") or [])
        old_counts = {str(key): int(value) for key, value in
                      (old_state.get("rootReplyCounts") or {}).items()}
        old_full = utc_from_api(previous.get("lastFullScanAt")) if previous else None
        prior_complete = utc_from_api(previous.get("lastCompleteScanAt")) if previous else None
        full = not previous or not previous.get("baselineCompletedAt") or not old_full
        if old_full and not full:
            last = datetime.fromisoformat(old_full.replace("Z", "+00:00"))
            if last.tzinfo is None:
                last = last.replace(tzinfo=timezone.utc)
            full = (datetime.now(timezone.utc) - last).total_seconds() >= 86400
        # Root pages are cheap enough to revisit; nested pages are fetched on changed counts.
        roots = self.source.roots(dynamic["commentOid"], dynamic["commentType"], full=True)
        comments = {}
        root_ids = set()
        root_counts = {}
        for root in roots:
            if self.should_stop():
                return
            parsed = parse_comment(root, None)
            comments[parsed["rpid"]] = parsed
            root_ids.add(parsed["rpid"])
            root_counts[parsed["rpid"]] = parsed["replyCount"]
        for root_id in sorted(root_ids):
            if self.should_stop():
                return
            if not full and old_counts.get(root_id) == root_counts[root_id]:
                continue
            for reply in self.source.replies(dynamic["commentOid"], dynamic["commentType"], root_id):
                parsed = parse_comment(reply, root_id)
                comments[parsed["rpid"]] = parsed
        if full and hasattr(self.source, "validate_root_snapshot"):
            self.source.validate_root_snapshot(dynamic["commentOid"], dynamic["commentType"])
        observed = set(comments)
        changes = []
        if full and previous and previous.get("baselineCompletedAt"):
            changes = [{"dynamicId": dynamic_id, "rpid": rpid, "unavailable": True}
                       for rpid in sorted(known - observed)]
        comment_list = list(comments.values())
        chunks = [comment_list[i:i + 100] for i in range(0, len(comment_list), 100)] or [[]]
        committed = set(known)
        committed_roots = set(known_roots)
        for index, chunk in enumerate(chunks):
            if self.should_stop():
                return
            final = index == len(chunks) - 1
            committed.update(c["rpid"] for c in chunk)
            committed_roots.update(c["rpid"] for c in chunk if c["rootRpid"] is None)
            if final and full:
                committed = observed
                committed_roots = root_ids
            state = {"knownIds": sorted(committed), "rootIds": sorted(committed_roots),
                     "rootReplyCounts": root_counts if final and full else
                     {**old_counts, **root_counts} if final else old_counts}
            stamp = self.clock() if final else None
            scan_state = {"state": state,
                          "lastCompleteScanAt": stamp if final else prior_complete,
                          "lastFullScanAt": stamp if final and full else old_full,
                          "fullScanRetryAt": None}
            item = {"dynamic": dynamic, "comments": chunk, "scanState": scan_state,
                    "eventCandidates": [candidate(dynamic_id, dynamic)] +
                    [candidate(dynamic_id, comment, comment=True) for comment in chunk]}
            self.internal.batch(self.uid, {"items": [item],
                "baselineCompletedDynamicIds": [dynamic_id] if final else [],
                # The final calibration timestamp and absence decisions must commit together.
                "availabilityChanges": changes if final else []})
