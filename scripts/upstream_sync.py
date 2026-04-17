#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]

HOTSPOT_PATHS = [
    "app/build.gradle.kts",
    "app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/components/ai/LedgerGenerationDialog.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt",
    "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
    "app/src/main/java/me/rerere/rikkahub/service/KnowledgeBaseService.kt",
    "app/src/main/java/me/rerere/rikkahub/service/KnowledgeBaseIndexForegroundService.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantKnowledgeBasePage.kt",
    "search/src/main/java/me/rerere/search/SearchService.kt",
    "search/src/main/java/me/rerere/search/TavilySearchService.kt",
    "app/src/main/java/me/rerere/rikkahub/service/WebServerService.kt",
    "app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt",
]

CATEGORY_RULES = [
    ("chat-ui", [
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/",
        "app/src/main/java/me/rerere/rikkahub/ui/components/ai/",
        "app/src/main/java/me/rerere/rikkahub/ui/components/message/",
    ]),
    ("chat-service", [
        "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
        "app/src/main/java/me/rerere/rikkahub/service/ConversationRuntimeService.kt",
        "app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt",
    ]),
    ("provider", [
        "ai/src/main/java/me/rerere/ai/provider/",
        "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt",
    ]),
    ("search", [
        "search/src/main/java/me/rerere/search/",
        "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt",
    ]),
    ("workflow", [
        "app/src/main/java/me/rerere/rikkahub/ui/pages/workflow/",
        "app/src/main/java/me/rerere/rikkahub/data/ai/tools/workflow/",
        "app/src/main/java/me/rerere/rikkahub/ui/components/workflow/",
    ]),
    ("compression-memory", [
        "app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt",
        "app/src/main/java/me/rerere/rikkahub/ui/components/ai/LedgerGenerationDialog.kt",
        "app/src/main/java/me/rerere/rikkahub/data/model/RollingSummary.kt",
        "app/src/main/java/me/rerere/rikkahub/service/KnowledgeBaseService.kt",
    ]),
    ("knowledge-base", [
        "app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantKnowledgeBasePage.kt",
        "app/src/main/java/me/rerere/rikkahub/service/KnowledgeBase",
        "app/src/main/java/me/rerere/rikkahub/data/knowledge/",
    ]),
    ("build-release", [
        "app/build.gradle.kts",
        "build.gradle.kts",
        "gradle/",
        ".github/workflows/",
    ]),
    ("web", [
        "web/",
        "web-ui/",
        "app/src/main/java/me/rerere/rikkahub/service/WebServerService.kt",
        "app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt",
    ]),
]

PERF_KEYWORDS = ("perf", "performance", "optimiz", "smooth", "lighter", "latency")
FIX_KEYWORDS = ("fix", "bug", "regress", "restore", "correct")
REFACTOR_KEYWORDS = ("refactor", "cleanup", "clean", "reorg", "reorganize")


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        check=check,
        text=True,
        capture_output=True,
    )
    return result.stdout.strip()


def short_sha(commit: str) -> str:
    return git("rev-parse", "--short", commit)


def exact_tag(commit: str) -> str | None:
    try:
        return git("describe", "--tags", "--exact-match", commit)
    except subprocess.CalledProcessError:
        return None


def nearest_tag(commit: str) -> str | None:
    try:
        return git("describe", "--tags", "--abbrev=0", commit)
    except subprocess.CalledProcessError:
        return None


def describe_ref(commit: str) -> str | None:
    try:
        return git("describe", "--tags", commit)
    except subprocess.CalledProcessError:
        return None


def parse_trailers(body: str) -> dict[str, str]:
    trailers: dict[str, str] = {}
    for line in body.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip()
        if not key or not value:
            continue
        trailers[key] = value
    return trailers


@dataclass
class SyncMarker:
    commit: str
    subject: str
    trailers: dict[str, str]


@dataclass
class CommitInfo:
    commit: str
    author: str
    subject: str
    files: list[str]
    categories: list[str]
    is_perf: bool
    is_fix: bool
    is_refactor: bool
    hotspot_hits: list[str]
    fork_overlap: list[str]


def find_last_sync_marker(branch: str) -> SyncMarker | None:
    log_output = git("log", "--first-parent", branch, "--format=%H%x1f%s%x1f%b%x1e", "-n", "200")
    for raw in log_output.split("\x1e"):
        raw = raw.strip()
        if not raw:
            continue
        parts = raw.split("\x1f", 2)
        if len(parts) == 2:
            commit, subject = parts
            body = ""
        elif len(parts) == 3:
            commit, subject, body = parts
        else:
            continue
        trailers = parse_trailers(body)
        if (
            "Upstream-Head" in trailers
            or "Upstream-Base" in trailers
            or "Upstream-Tag" in trailers
            or re.search(r"sync upstream|Merge branch 'main'", subject, re.IGNORECASE)
        ):
            return SyncMarker(commit=commit, subject=subject, trailers=trailers)
    return None


def list_changed_files(rev_range: str) -> list[str]:
    return [
        entry.strip()
        for entry in git("diff", "--name-only", rev_range).splitlines()
        if entry.strip()
    ]


def infer_areas(files: list[str]) -> list[str]:
    areas: Counter[str] = Counter()
    for path in files:
        parts = path.split("/")
        if len(parts) >= 4 and parts[0] == "app" and parts[1] == "src" and parts[2] == "main":
            if parts[3] == "java" and len(parts) >= 9:
                areas["/".join(parts[7:9])] += 1
            elif parts[3] == "res" and len(parts) >= 5:
                areas[f"res/{parts[4]}"] += 1
            else:
                areas["/".join(parts[:4])] += 1
        elif len(parts) >= 2:
            areas["/".join(parts[:2])] += 1
        else:
            areas[path] += 1
    return [area for area, _ in areas.most_common(12)]


def list_commits(base: str, head: str, fork_owned_files: set[str]) -> list[CommitInfo]:
    log_output = git("log", "--reverse", "--format=%H%x1f%an%x1f%s", f"{base}..{head}")
    commits: list[CommitInfo] = []
    for line in log_output.splitlines():
        if not line.strip():
            continue
        commit, author, subject = line.split("\x1f", 2)
        files = [
            entry.strip()
            for entry in git("show", "--format=", "--name-only", commit).splitlines()
            if entry.strip()
        ]
        categories = classify_categories(files)
        lower_subject = subject.lower()
        hotspot_hits = [path for path in files if path in HOTSPOT_PATHS]
        fork_overlap = [path for path in files if path in fork_owned_files]
        commits.append(
            CommitInfo(
                commit=commit,
                author=author,
                subject=subject,
                files=files,
                categories=categories,
                is_perf=any(keyword in lower_subject for keyword in PERF_KEYWORDS),
                is_fix=any(keyword in lower_subject for keyword in FIX_KEYWORDS),
                is_refactor=any(keyword in lower_subject for keyword in REFACTOR_KEYWORDS),
                hotspot_hits=hotspot_hits,
                fork_overlap=fork_overlap,
            )
        )
    return commits


def classify_categories(files: list[str]) -> list[str]:
    categories: list[str] = []
    for category, prefixes in CATEGORY_RULES:
        if any(any(path == prefix or path.startswith(prefix) for prefix in prefixes) for path in files):
            categories.append(category)
    return categories


def describe_commit(commit: str) -> str:
    tag = exact_tag(commit) or nearest_tag(commit)
    if tag:
        return f"{short_sha(commit)} ({tag})"
    return short_sha(commit)


def choose_review_bucket(commit: CommitInfo) -> str:
    if commit.fork_overlap:
        return "mod-overlap"
    if commit.hotspot_hits or commit.is_perf:
        return "priority"
    if commit.is_fix:
        return "important"
    return "normal"


def render_report(
    fork_branch: str,
    upstream_branch: str,
    base: str,
    head: str,
    commits: list[CommitInfo],
    marker: SyncMarker | None,
    fork_owned_files: list[str],
) -> str:
    lines: list[str] = []
    lines.append("# Upstream Audit")
    lines.append("")
    lines.append(f"- Fork branch: `{fork_branch}`")
    lines.append(f"- Upstream mirror branch: `{upstream_branch}`")
    lines.append(f"- Current shared base: `{describe_commit(base)}`")
    lines.append(f"- Upstream head: `{describe_commit(head)}`")
    if marker:
        lines.append(f"- Last recorded sync marker: `{short_sha(marker.commit)}` {marker.subject}")
        if marker.trailers:
            for key in ("Upstream-Base", "Upstream-Head", "Upstream-Tag"):
                if key in marker.trailers:
                    lines.append(f"  {key}: `{marker.trailers[key]}`")
    else:
        lines.append("- Last recorded sync marker: none")
    lines.append(f"- Upstream commits since base: `{len(commits)}`")
    lines.append(f"- Fork-owned files since base: `{len(fork_owned_files)}`")
    lines.append("")

    if not commits:
        lines.append("No upstream delta is pending. `master` already contains the current `main` base.")
        return "\n".join(lines)

    category_counter = Counter()
    hotspot_counter = Counter()
    overlap_counter = Counter()
    perf_commits = 0
    fix_commits = 0
    refactor_commits = 0

    for commit in commits:
        category_counter.update(commit.categories)
        hotspot_counter.update(commit.hotspot_hits)
        overlap_counter.update(commit.fork_overlap)
        perf_commits += int(commit.is_perf)
        fix_commits += int(commit.is_fix)
        refactor_commits += int(commit.is_refactor)

    fork_owned_areas = infer_areas(fork_owned_files)

    lines.append("## Review Summary")
    lines.append("")
    lines.append(f"- Perf-like subjects: `{perf_commits}`")
    lines.append(f"- Fix-like subjects: `{fix_commits}`")
    lines.append(f"- Refactor-like subjects: `{refactor_commits}`")
    lines.append(f"- Upstream commits overlapping fork-owned files: `{sum(1 for commit in commits if commit.fork_overlap)}`")
    if fork_owned_areas:
        lines.append("- Fork-owned areas:")
        for area in fork_owned_areas[:8]:
            lines.append(f"  - `{area}`")
    if category_counter:
        lines.append("- Category hits:")
        for category, count in category_counter.most_common():
            lines.append(f"  - `{category}`: {count}")
    if overlap_counter:
        lines.append("- Fork-owned files touched by upstream:")
        for path, count in overlap_counter.most_common(12):
            lines.append(f"  - `{path}`: {count}")
    if hotspot_counter:
        lines.append("- Static hotspot files touched:")
        for path, count in hotspot_counter.most_common(8):
            lines.append(f"  - `{path}`: {count}")
    lines.append("")

    overlap_commits = [commit for commit in commits if choose_review_bucket(commit) == "mod-overlap"]
    priority_commits = [commit for commit in commits if choose_review_bucket(commit) == "priority"]
    important_commits = [commit for commit in commits if choose_review_bucket(commit) == "important"]
    normal_commits = [commit for commit in commits if choose_review_bucket(commit) == "normal"]

    lines.append("## Suggested Review Order")
    lines.append("")
    lines.append(f"- Fork-overlap commits first: `{len(overlap_commits)}`")
    lines.append("  These touch files that the fork has modified since the last upstream base.")
    lines.append(f"- Priority commits next: `{len(priority_commits)}`")
    lines.append("  These either look like perf work or hit the static hotspot list.")
    lines.append(f"- Important fixes next: `{len(important_commits)}`")
    lines.append(f"- Safe/no-overlap commits last: `{len(normal_commits)}`")
    lines.append("")

    lines.append("## Commit Checklist")
    lines.append("")
    for index, commit in enumerate(commits, start=1):
        category_suffix = f" [{', '.join(commit.categories)}]" if commit.categories else ""
        keyword_flags = []
        if commit.is_perf:
            keyword_flags.append("perf")
        if commit.is_fix:
            keyword_flags.append("fix")
        if commit.is_refactor:
            keyword_flags.append("refactor")
        if commit.fork_overlap:
            keyword_flags.append("fork-overlap")
        if commit.hotspot_hits:
            keyword_flags.append("hotspot")
        flag_suffix = f" ({', '.join(keyword_flags)})" if keyword_flags else ""
        lines.append(f"{index}. `{short_sha(commit.commit)}` {commit.subject}{category_suffix}{flag_suffix}")
        lines.append(f"   Author: {commit.author}")
        if commit.fork_overlap:
            lines.append(f"   Fork overlap: {', '.join(f'`{path}`' for path in commit.fork_overlap[:5])}")
        if commit.hotspot_hits:
            lines.append(f"   Hotspots: {', '.join(f'`{path}`' for path in commit.hotspot_hits[:5])}")
        if commit.files:
            preview = ", ".join(f"`{path}`" for path in commit.files[:5])
            if len(commit.files) > 5:
                preview += f", and {len(commit.files) - 5} more"
            lines.append(f"   Files: {preview}")
    lines.append("")

    return "\n".join(lines)


def cmd_report(args: argparse.Namespace) -> int:
    base = args.base or git("merge-base", args.fork, args.upstream)
    head = git("rev-parse", args.upstream)
    marker = find_last_sync_marker(args.fork)
    fork_owned_files = list_changed_files(f"{base}..{args.fork}")
    commits = list_commits(base, head, set(fork_owned_files))
    report = render_report(args.fork, args.upstream, base, head, commits, marker, fork_owned_files)
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(report + "\n", encoding="utf-8")
    else:
        sys.stdout.write(report + "\n")
    return 0


def cmd_trailers(args: argparse.Namespace) -> int:
    head = git("rev-parse", args.upstream)
    upstream_tag = args.upstream_tag or exact_tag(head) or nearest_tag(head) or "unknown"
    anchor_tag = build_sync_anchor_tag(head, upstream_tag)
    lines = [
        f"Upstream-Base: {args.base}",
        f"Upstream-Head: {head}",
        f"Upstream-Tag: {upstream_tag}",
        f"Upstream-Describe: {describe_ref(head) or short_sha(head)}",
        f"Sync-Anchor-Tag: {anchor_tag}",
    ]
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


def build_sync_anchor_tag(head: str, upstream_tag: str) -> str:
    if exact_tag(head) == upstream_tag:
        return f"sync-upstream-{upstream_tag}"
    return f"sync-upstream-{upstream_tag}-{short_sha(head)}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Deterministic upstream audit helpers for the master/main port workflow."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    report = subparsers.add_parser("report", help="render an upstream delta audit report")
    report.add_argument("--fork", default="master", help="fork branch to audit from")
    report.add_argument("--upstream", default="main", help="upstream mirror branch to audit against")
    report.add_argument("--base", help="explicit old upstream base commit")
    report.add_argument("--output", help="write markdown report to a file")
    report.set_defaults(func=cmd_report)

    trailers = subparsers.add_parser("trailers", help="print sync commit trailers for the current upstream head")
    trailers.add_argument("--base", required=True, help="old upstream base commit being superseded")
    trailers.add_argument("--upstream", default="main", help="upstream mirror branch to record")
    trailers.add_argument("--upstream-tag", help="explicit upstream tag override")
    trailers.set_defaults(func=cmd_trailers)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
