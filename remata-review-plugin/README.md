# Remata Review Plugin for Claude Code

A Claude Code plugin that lets Claude discover and respond to code review comments
left by a human in the [Remata IntelliJ plugin](../review-plugin/README.md).

No server to run. No accounts. Install the plugin, and Claude can see your review
comments in any project.

## Prerequisites

- Python 3.10+
- Claude Code CLI
- `pip install mcp`

## Installation

```bash
# Install the Python dependency
pip install mcp

# Install the plugin into Claude Code
claude plugin add /path/to/remata-review-plugin
```

## Usage

### Slash command

```
/remata:review
```

Claude will list all open review comments, read the referenced code, address each
issue, and resolve the comments with explanations.

### MCP tools (available automatically)

| Tool | Description |
|---|---|
| `list_reviews` | List comments — filter by `status` (open/resolved/wontfix/all) or `file` |
| `show_review` | Show a comment with full thread and code context |
| `reply_to_review` | Append a reply without changing status |
| `resolve_review` | Mark resolved (or wontfix) with an explanation |
| `create_review` | Create a new comment on a file and line |

Claude discovers these tools via Tool Search and can use them directly in any
conversation.

## How it works

```
Human in IntelliJ                          Claude Code
┌──────────────┐                          ┌──────────────┐
│ Right-click   │                          │ /remata:review│
│ Add Review    │──► .review/comments/ ◄──│ list_reviews  │
│ Comment       │    ├── a3f1c2d4.json     │ show_review   │
│               │    └── b7e902f1.json     │ resolve_review│
└──────────────┘                          └──────────────┘
```

Both sides read and write the same JSON files. The IntelliJ plugin watches the
directory for changes in real time. Claude Code uses the MCP tools to interact
with the files structurally.

## File format

Each comment is a JSON file in `.review/comments/`:

```json
{
  "id": "a3f1c2d4",
  "schema_version": 1,
  "author": "human",
  "created": "2026-03-25T10:14:00Z",
  "status": "open",
  "anchor": {
    "file": "src/Main.java",
    "commit": "f3a9b1c",
    "line_hint": 142,
    "hunk": {
      "context_before": ["previous line 1", "previous line 2"],
      "target": ["the commented line"],
      "context_after": ["next line 1"]
    }
  },
  "body": "This should handle the null case.",
  "thread": []
}
```

See the [full file format spec](../review-plugin/README.md#file-format) for details.

## Development

```bash
# Run the MCP server directly (for testing)
cd remata-review-plugin
python3 server/review-server.py

# The server uses stdio transport — it reads JSON-RPC from stdin
# and writes responses to stdout.
```

## License

[Apache License 2.0](../LICENSE)
