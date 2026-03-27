#!/usr/bin/env python3
"""
Remata Review MCP Server

Exposes file-based code review comments (.review/comments/*.json) as MCP tools.
Works with the Remata IntelliJ plugin — the human writes comments in the IDE,
this server lets Claude Code read and respond to them.

Protocol: stdio (launched by Claude Code via .mcp.json)
Dependencies: mcp (pip install mcp)
"""

import glob
import json
import os
import random
import subprocess
import sys
from datetime import datetime, timezone

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

server = Server("remata")

# ── Helpers ──────────────────────────────────────────────────────────


def find_project_root() -> str | None:
    """Walk up from cwd to find a directory containing .git."""
    path = os.getcwd()
    while True:
        if os.path.isdir(os.path.join(path, ".git")):
            return path
        parent = os.path.dirname(path)
        if parent == path:
            return None
        path = parent


def review_comments_dir(root: str) -> str:
    return os.path.join(root, ".review", "comments")


def effective_status(comment: dict) -> str:
    """
    Replicate ReviewComment.effectiveStatus() from Kotlin:
    last thread entry with a non-null status wins, else root status.
    """
    thread = comment.get("thread", [])
    for entry in reversed(thread):
        s = entry.get("status")
        if s is not None:
            return s
    return comment.get("status", "open")


def load_comments(
    root: str,
    status_filter: str | None = None,
    file_filter: str | None = None,
) -> list[dict]:
    """Load all valid comments, optionally filtered."""
    comments_dir = review_comments_dir(root)
    if not os.path.isdir(comments_dir):
        return []

    comments = []
    for path in glob.glob(os.path.join(comments_dir, "*.json")):
        try:
            with open(path) as f:
                c = json.load(f)
            if c.get("schema_version") != 1:
                continue
            if status_filter and status_filter != "all":
                if effective_status(c) != status_filter:
                    continue
            if file_filter:
                anchor_file = c.get("anchor", {}).get("file", "")
                normalized = file_filter.replace("\\", "/")
                if anchor_file.replace("\\", "/") != normalized:
                    continue
            comments.append(c)
        except (json.JSONDecodeError, OSError):
            continue
    return comments


def load_comment(root: str, comment_id: str) -> dict | None:
    """Load a single comment by ID."""
    path = os.path.join(review_comments_dir(root), f"{comment_id}.json")
    if not os.path.isfile(path):
        return None
    try:
        with open(path) as f:
            c = json.load(f)
        if c.get("schema_version") != 1:
            return None
        return c
    except (json.JSONDecodeError, OSError):
        return None


def write_comment(root: str, comment: dict) -> None:
    """Atomic write: .json.tmp then rename (matches CommentStore protocol)."""
    comments_dir = review_comments_dir(root)
    os.makedirs(comments_dir, exist_ok=True)
    cid = comment["id"]
    tmp_path = os.path.join(comments_dir, f"{cid}.json.tmp")
    final_path = os.path.join(comments_dir, f"{cid}.json")
    with open(tmp_path, "w") as f:
        json.dump(comment, f, indent=2)
    os.rename(tmp_path, final_path)


def generate_id() -> str:
    """8-char hex ID (matches NewReviewCommentAction)."""
    return random.randbytes(4).hex()


def current_commit(root: str) -> str:
    try:
        return (
            subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=root, stderr=subprocess.DEVNULL
            )
            .decode()
            .strip()
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def format_comment_summary(c: dict) -> str:
    """One-line summary for list output."""
    cid = c["id"]
    status = effective_status(c)
    anchor = c.get("anchor", {})
    file = anchor.get("file", "?")
    line = anchor.get("line_hint", "?")
    body = c.get("body", "")
    preview = body[:80] + ("..." if len(body) > 80 else "")
    return f"{cid}  {status:<10}  {file}:{line}  {preview}"


def format_comment_detail(c: dict) -> str:
    """Full detail view with thread and hunk context."""
    lines = []
    cid = c["id"]
    status = effective_status(c)
    anchor = c.get("anchor", {})
    hunk = anchor.get("hunk", {})

    lines.append(f"Comment {cid} [{status}]")
    lines.append(f"File: {anchor.get('file', '?')}:{anchor.get('line_hint', '?')}")
    lines.append(f"Author: {c.get('author', '?')} | Created: {c.get('created', '?')}")
    lines.append("")
    lines.append(f"  {c.get('body', '')}")
    lines.append("")

    # Hunk context
    ctx_before = hunk.get("context_before", [])
    target = hunk.get("target", [])
    ctx_after = hunk.get("context_after", [])
    if ctx_before or target or ctx_after:
        lines.append("Code context:")
        for line in ctx_before:
            lines.append(f"  | {line}")
        for line in target:
            lines.append(f"  > {line}")
        for line in ctx_after:
            lines.append(f"  | {line}")
        lines.append("")

    # Thread
    thread = c.get("thread", [])
    if thread:
        lines.append(f"Thread ({len(thread)} {'reply' if len(thread) == 1 else 'replies'}):")
        for entry in thread:
            status_tag = f" ({entry['status']})" if entry.get("status") else ""
            lines.append(f"  [{entry.get('author', '?')}] {entry.get('created', '?')}{status_tag}")
            lines.append(f"    {entry.get('body', '')}")
        lines.append("")

    return "\n".join(lines)


# ── MCP Tools ────────────────────────────────────────────────────────


@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="list_reviews",
            description=(
                "List code review comments from .review/comments/. "
                "Defaults to open comments. Use this to discover review feedback that needs attention."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["open", "resolved", "wontfix", "all"],
                        "default": "open",
                        "description": "Filter by status. Default: open.",
                    },
                    "file": {
                        "type": "string",
                        "description": "Filter by file path (project-relative, forward slashes).",
                    },
                },
            },
        ),
        Tool(
            name="show_review",
            description=(
                "Show a single review comment with its full thread and code context. "
                "Use after list_reviews to see the details of a specific comment."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "id": {
                        "type": "string",
                        "description": "The 8-char hex comment ID.",
                    },
                },
                "required": ["id"],
            },
        ),
        Tool(
            name="reply_to_review",
            description=(
                "Append an informational reply to a review comment. "
                "Does NOT change the comment's status. Use for questions or progress updates."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "id": {
                        "type": "string",
                        "description": "The comment ID to reply to.",
                    },
                    "body": {
                        "type": "string",
                        "description": "The reply text.",
                    },
                },
                "required": ["id", "body"],
            },
        ),
        Tool(
            name="resolve_review",
            description=(
                "Resolve a review comment. Sets status to resolved (or wontfix) and appends a thread entry. "
                "Use after you have addressed the issue raised in the review."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "id": {
                        "type": "string",
                        "description": "The comment ID to resolve.",
                    },
                    "body": {
                        "type": "string",
                        "description": "Explanation of what you did to address the review.",
                    },
                    "wontfix": {
                        "type": "boolean",
                        "default": False,
                        "description": "Set to true to mark as wontfix instead of resolved.",
                    },
                },
                "required": ["id", "body"],
            },
        ),
        Tool(
            name="create_review",
            description=(
                "Create a new review comment on a file. Extracts code context automatically. "
                "Use to flag issues, suggest improvements, or leave notes for the human reviewer."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "file": {
                        "type": "string",
                        "description": "Project-relative file path (forward slashes).",
                    },
                    "line": {
                        "type": "integer",
                        "description": "1-based line number to comment on.",
                    },
                    "body": {
                        "type": "string",
                        "description": "The comment text.",
                    },
                    "end_line": {
                        "type": "integer",
                        "description": "1-based end line for multi-line comments. Defaults to same as line.",
                    },
                    "context_lines": {
                        "type": "integer",
                        "default": 3,
                        "description": "Number of context lines before/after target. Default: 3.",
                    },
                },
                "required": ["file", "line", "body"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:
    root = find_project_root()
    if root is None:
        return [TextContent(type="text", text="Error: not inside a git repository.")]

    if name == "list_reviews":
        return _list_reviews(root, arguments)
    elif name == "show_review":
        return _show_review(root, arguments)
    elif name == "reply_to_review":
        return _reply_to_review(root, arguments)
    elif name == "resolve_review":
        return _resolve_review(root, arguments)
    elif name == "create_review":
        return _create_review(root, arguments)
    else:
        return [TextContent(type="text", text=f"Unknown tool: {name}")]


def _list_reviews(root: str, args: dict) -> list[TextContent]:
    status = args.get("status", "open")
    file_filter = args.get("file")
    comments = load_comments(root, status_filter=status, file_filter=file_filter)

    if not comments:
        return [TextContent(type="text", text=f"No {status} review comments found.")]

    comments.sort(key=lambda c: c.get("created", ""), reverse=True)
    lines = [f"{len(comments)} {status} review comment(s):", ""]
    for c in comments:
        lines.append(format_comment_summary(c))
    return [TextContent(type="text", text="\n".join(lines))]


def _show_review(root: str, args: dict) -> list[TextContent]:
    cid = args.get("id", "")
    comment = load_comment(root, cid)
    if comment is None:
        return [TextContent(type="text", text=f"Comment {cid} not found.")]
    return [TextContent(type="text", text=format_comment_detail(comment))]


def _reply_to_review(root: str, args: dict) -> list[TextContent]:
    cid = args.get("id", "")
    body = args.get("body", "")
    if not body:
        return [TextContent(type="text", text="Error: reply body is required.")]

    comment = load_comment(root, cid)
    if comment is None:
        return [TextContent(type="text", text=f"Comment {cid} not found.")]

    entry = {
        "id": generate_id(),
        "author": "agent",
        "created": iso_now(),
        "body": body,
        "status": None,
    }
    comment.setdefault("thread", []).append(entry)
    write_comment(root, comment)

    return [TextContent(type="text", text=f"Reply added to comment {cid}.")]


def _resolve_review(root: str, args: dict) -> list[TextContent]:
    cid = args.get("id", "")
    body = args.get("body", "")
    wontfix = args.get("wontfix", False)
    if not body:
        return [TextContent(type="text", text="Error: resolution body is required.")]

    comment = load_comment(root, cid)
    if comment is None:
        return [TextContent(type="text", text=f"Comment {cid} not found.")]

    new_status = "wontfix" if wontfix else "resolved"
    comment["status"] = new_status

    entry = {
        "id": generate_id(),
        "author": "agent",
        "created": iso_now(),
        "body": body,
        "status": new_status,
    }
    comment.setdefault("thread", []).append(entry)
    write_comment(root, comment)

    return [TextContent(type="text", text=f"Comment {cid} marked as {new_status}.")]


def _create_review(root: str, args: dict) -> list[TextContent]:
    file_rel = args.get("file", "")
    line = args.get("line", 1)
    body = args.get("body", "")
    end_line = args.get("end_line", line)
    context_n = args.get("context_lines", 3)

    if not file_rel or not body:
        return [TextContent(type="text", text="Error: file and body are required.")]

    # Normalize path
    file_rel = file_rel.replace("\\", "/")
    abs_path = os.path.join(root, file_rel)

    if not os.path.isfile(abs_path):
        return [TextContent(type="text", text=f"Error: file not found: {file_rel}")]

    # Read file and extract context (matching NewReviewCommentAction logic)
    with open(abs_path) as f:
        all_lines = f.read().splitlines()

    # Convert to 0-based indices
    start_idx = line - 1
    end_idx = end_line - 1

    if start_idx < 0 or start_idx >= len(all_lines):
        return [TextContent(type="text", text=f"Error: line {line} is out of range (file has {len(all_lines)} lines).")]

    end_idx = min(end_idx, len(all_lines) - 1)

    target_lines = all_lines[start_idx : end_idx + 1]

    ctx_before_start = max(0, start_idx - context_n)
    context_before = all_lines[ctx_before_start:start_idx]

    ctx_after_end = min(len(all_lines), end_idx + 1 + context_n)
    context_after = all_lines[end_idx + 1 : ctx_after_end]

    commit = current_commit(root)
    cid = generate_id()

    comment = {
        "id": cid,
        "schema_version": 1,
        "author": "agent",
        "created": iso_now(),
        "status": "open",
        "anchor": {
            "file": file_rel,
            "commit": commit,
            "line_hint": line,
            "hunk": {
                "context_before": context_before,
                "target": target_lines,
                "context_after": context_after,
            },
        },
        "resolved_anchor": None,
        "body": body,
        "thread": [],
    }

    write_comment(root, comment)
    return [TextContent(type="text", text=f"Review comment {cid} created on {file_rel}:{line}.")]


# ── Main ─────────────────────────────────────────────────────────────


async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    import asyncio

    asyncio.run(main())
