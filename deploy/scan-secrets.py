#!/usr/bin/env python3
"""Report possible credential exposure without printing matching values."""
import argparse
import pathlib
import re
import subprocess
import sys


SECRET_NAMES = {
    "BILI_COOKIE", "MONITOR_API_TOKEN", "FEISHU_WEBHOOK_ENC_KEY",
    "FEISHU_APP_SECRET", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET",
    "MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD",
}
PATTERNS = (
    re.compile("SESS" + r"DATA=[^\s;\"']{8,}"),
    re.compile("bili_" + r"jct=[^\s;\"']{8,}"),
    re.compile("/open-apis/bot/v2/" + r"hook/[A-Za-z0-9_-]{12,}"),
    re.compile(r"\bLTAI[A-Za-z0-9]{12,}\b"),
)
TEST_WEBHOOK_SUFFIXES = {"http-test-only", "test-only-123", "replacement-only"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", action="append", type=pathlib.Path, default=[])
    parser.add_argument("--extra", action="append", type=pathlib.Path, default=[])
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parent.parent
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=repo
    ).split(b"\0")
    paths = [repo / n.decode("utf-8") for n in names if n]
    paths += args.extra
    secrets = []
    for env_path in args.env:
        for line in env_path.read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep and key in SECRET_NAMES and len(value) >= 12 and not value.startswith("REPLACE_"):
                secrets.append(value)
    failures = []
    for path in paths:
        if not path.is_file():
            continue
        content = path.read_bytes().decode("utf-8", errors="ignore")
        pattern_hits = any(p.search(content) for p in PATTERNS[:2] + PATTERNS[3:])
        webhook_hits = any(
            match.group().rsplit("/", 1)[-1] not in TEST_WEBHOOK_SUFFIXES
            for match in PATTERNS[2].finditer(content)
        )
        if any(s in content for s in secrets) or pattern_hits or webhook_hits:
            failures.append(path.relative_to(repo) if path.is_relative_to(repo) else path.name)
    print(f"scanned_files={len(paths)} potential_exposures={len(failures)}")
    for path in failures:
        print(f"review_file={path}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
