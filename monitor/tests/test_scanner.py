import copy
import json
import unittest

from bili_monitor.client import SourceError, SourceUnavailable
from bili_monitor.scanner import Scanner, now_iso


UID = "550494308"


def dynamic(number, *, charge=True, owner=UID):
    return {"id_str": str(number), "type": "DYNAMIC_TYPE_DRAW",
            "basic": {"comment_id_str": str(number + 1000), "comment_type": 11},
            "modules": {"module_author": {"mid": owner, "pub_ts": 1700000000,
                                          "icon_badge": {"text": "充电专属" if charge else ""}},
                        "module_dynamic": {"major": {"type": "MAJOR_TYPE_DRAW",
                                                     "draw": {"desc": "内容", "items": []}}}}}


def reply(number, author=UID, parent=0):
    return {"rpid_str": str(number), "parent": parent, "ctime": 1700000001,
            "member": {"mid": author, "uname": "作者", "level_info": {"current_level": 1}},
            "content": {"message": "评论", "pictures": []}, "like": 0, "rcount": 0}


class Source:
    def __init__(self, space=None, details=None, roots=None, nested=None):
        self.space = space or []
        self.details = details or {}
        self.root_data = roots or {}
        self.nested = nested or {}
        self.calls = []
        self.fail_roots = False
        self.fail_nested = False

    def latest_fifty(self, uid):
        self.calls.append(("space", uid))
        return self.space[:50]

    def detail(self, dynamic_id):
        self.calls.append(("detail", dynamic_id))
        result = self.details[dynamic_id]
        if isinstance(result, Exception):
            raise result
        return result

    def roots(self, oid, comment_type, *, full):
        self.calls.append(("roots", oid, full))
        if self.fail_roots:
            raise SourceError("分页失败")
        return self.root_data.get(oid, [])

    def replies(self, oid, comment_type, root):
        self.calls.append(("replies", root))
        if self.fail_nested:
            raise SourceError("楼中楼分页失败")
        return self.nested.get(root, [])


class Internal:
    def __init__(self, routes=None):
        self.routes = routes or []
        self.scans = {}
        self.payloads = []
        self.contents = {}
        self.comments = {}
        self.events = set()
        self.unavailable = set()
        self.enabled = True
        self.fail_batch = False

    def config(self, uid):
        return {"uid": uid, "enabled": self.enabled, "fixedRoutes": self.routes,
                "scans": copy.deepcopy(list(self.scans.values()))}

    def batch(self, uid, payload):
        if self.fail_batch:
            raise SourceError("批次失败")
        self.payloads.append(copy.deepcopy(payload))
        for item in payload["items"]:
            dyn = item["dynamic"]
            self.contents[dyn["dynamicId"]] = copy.deepcopy(dyn)
            for comment in item["comments"]:
                self.comments[(dyn["dynamicId"], comment["rpid"])] = copy.deepcopy(comment)
                self.unavailable.discard((dyn["dynamicId"], comment["rpid"]))
            for event in item["eventCandidates"]:
                self.events.add(event["dedupeKey"])
            state = item["scanState"]
            old = self.scans.get(dyn["dynamicId"], {})
            self.scans[dyn["dynamicId"]] = {
                "dynamicId": dyn["dynamicId"], "stateJson": json.dumps(state["state"]),
                "lastCompleteScanAt": state["lastCompleteScanAt"],
                "lastFullScanAt": state["lastFullScanAt"],
                "baselineCompletedAt": old.get("baselineCompletedAt")}
        for dynamic_id in payload["baselineCompletedDynamicIds"]:
            self.scans[dynamic_id]["baselineCompletedAt"] = "2024-01-01T00:00:00Z"
        for change in payload["availabilityChanges"]:
            key = (change["dynamicId"], change["rpid"])
            if change["unavailable"]:
                self.unavailable.add(key)
            else:
                self.unavailable.discard(key)


class ScannerTest(unittest.TestCase):
    def make(self, source, internal):
        return Scanner(source, internal, UID, clock=lambda: "2024-01-01T00:00:00Z")

    def test_raw_fifty_boundary_and_fixed_target_deduplicate(self):
        space = [dynamic(i, charge=i == 100) for i in range(100, 49, -1)]
        space[1]["modules"]["module_author"]["icon_badge"] = None
        source = Source(space, {"50": dynamic(50)})
        internal = Internal([{"dynamicId": "50"}, {"dynamicId": "100"}])
        self.make(source, internal).round()
        self.assertEqual(set(internal.contents), {"100", "50"})
        self.assertEqual([call for call in source.calls if call[0] == "detail"], [("detail", "50")])

    def test_baseline_up_root_and_nested_large_ids(self):
        big = "12345678901234567890123456789012"
        source = Source([dynamic(100)], roots={"1100": [reply(big)]},
                        nested={big: [reply("98765432109876543210", UID, int(big))]})
        internal = Internal()
        self.make(source, internal).round()
        self.assertEqual(len(internal.comments), 2)
        self.assertEqual(internal.comments[("100", big)]["authorMid"], UID)
        self.assertEqual(internal.comments[("100", "98765432109876543210")]["rootRpid"], big)
        self.assertIn("100", internal.payloads[-1]["baselineCompletedDynamicIds"])

    def test_failure_does_not_advance_or_mark_unavailable(self):
        source = Source([dynamic(100)], roots={"1100": [reply(1)]})
        internal = Internal()
        self.make(source, internal).round()
        previous = copy.deepcopy(internal.scans)
        source.fail_nested = True
        with self.assertRaises(SourceError):
            self.make(source, internal).round()
        self.assertEqual(internal.scans, previous)
        self.assertFalse(internal.unavailable)

    def test_full_success_marks_absent_comment_and_recovery(self):
        source = Source([dynamic(100)], roots={"1100": [reply(1)]})
        internal = Internal()
        self.make(source, internal).round()
        source.root_data["1100"] = []
        internal.scans["100"]["lastFullScanAt"] = "2020-01-01T00:00:00Z"
        self.make(source, internal).round()
        self.assertIn(("100", "1"), internal.unavailable)
        source.root_data["1100"] = [reply(1)]
        internal.scans["100"]["lastFullScanAt"] = "2020-01-01T00:00:00Z"
        self.make(source, internal).round()
        self.assertNotIn(("100", "1"), internal.unavailable)

    def test_edit_does_not_create_new_candidate_identity(self):
        source = Source([dynamic(100)], roots={"1100": [reply(1)]})
        internal = Internal()
        self.make(source, internal).round()
        first_events = set(internal.events)
        source.root_data["1100"][0]["content"]["message"] = "修改后"
        self.make(source, internal).round()
        self.assertEqual(internal.events, first_events)
        self.assertEqual(internal.comments[("100", "1")]["text"], "修改后")

    def test_incremental_reads_nested_only_when_reply_count_changes(self):
        source = Source([dynamic(100)], roots={"1100": [reply(1)]}, nested={"1": []})
        internal = Internal()
        self.make(source, internal).round()
        internal.scans["100"]["lastFullScanAt"] = now_iso()
        source.calls.clear()
        self.make(source, internal).round()
        self.assertEqual([call for call in source.calls if call[0] == "replies"], [])
        source.root_data["1100"][0]["rcount"] = 1
        source.nested["1"] = [reply(2, "999", 1)]
        source.calls.clear()
        self.make(source, internal).round()
        self.assertIn(("replies", "1"), source.calls)
        self.assertIn(("100", "2"), internal.comments)

    def test_spring_local_timestamp_is_sent_back_as_utc_in_intermediate_batch(self):
        roots = [reply(i) for i in range(1, 102)]
        source = Source([dynamic(100)], roots={"1100": roots})
        internal = Internal()
        local_time = now_iso().removesuffix("Z")
        internal.scans["100"] = {"dynamicId": "100", "stateJson": json.dumps({
            "knownIds": [], "rootIds": [], "rootReplyCounts": {}}),
            "lastCompleteScanAt": local_time, "lastFullScanAt": local_time,
            "baselineCompletedAt": local_time}
        self.make(source, internal).round()
        first_state = internal.payloads[0]["items"][0]["scanState"]
        self.assertTrue(first_state["lastCompleteScanAt"].endswith("Z"))
        self.assertTrue(first_state["lastFullScanAt"].endswith("Z"))

    def test_disable_resume_no_history_backfill(self):
        source = Source([dynamic(100)])
        internal = Internal()
        self.make(source, internal).round()
        internal.enabled = False
        source.space = [dynamic(i) for i in range(200, 149, -1)]
        self.assertFalse(self.make(source, internal).round())
        internal.enabled = True
        self.make(source, internal).round()
        self.assertNotIn("150", internal.contents)
        self.assertIn("151", internal.contents)

    def test_explicit_fixed_unavailable_only(self):
        source = Source([], {"100": SourceUnavailable("删除")})
        internal = Internal([{"dynamicId": "100"}])
        internal.scans["100"] = {"dynamicId": "100", "stateJson": "{}",
                                  "lastFullScanAt": None, "lastCompleteScanAt": None,
                                  "baselineCompletedAt": None}
        self.make(source, internal).round()
        self.assertIn(("100", None), internal.unavailable)


if __name__ == "__main__":
    unittest.main()
