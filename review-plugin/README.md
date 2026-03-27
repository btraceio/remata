# Agent Review — IntelliJ Plugin

A Kotlin/IntelliJ plugin that renders file-based code review annotations written by a human
or a coding agent. No server. No network. The protocol is a directory of JSON files. The
plugin reads and writes them. The agent reads and writes them. Both parties see each other's
comments in real time.

## Requirements

- **JDK 17+** (required for IntelliJ Platform 2024.1)
- **IntelliJ IDEA 2024.1+** (Community or Ultimate), or any JetBrains IDE based on the
  same platform (CLion, WebStorm, PyCharm, etc.)
- **Git** on PATH (for anchor resolution via `git diff`)

## Building

```bash
cd review-plugin

# Build the plugin distribution zip
./gradlew buildPlugin

# The installable zip is at:
#   build/distributions/review-plugin-0.1.0.zip
```

To also run the tests:

```bash
./gradlew test
```

To verify plugin descriptor compatibility:

```bash
./gradlew verifyPluginConfiguration
```

## Installing in IntelliJ

### Option A: Install from disk (built zip)

1. Build the plugin: `./gradlew buildPlugin`
2. Open IntelliJ IDEA
3. Go to **Settings** → **Plugins** → gear icon (⚙) → **Install Plugin from Disk...**
4. Select `review-plugin/build/distributions/review-plugin-0.1.0.zip`
5. Restart the IDE

### Option B: Run a sandboxed IDE instance (for development)

This launches a fresh IntelliJ instance with the plugin pre-installed — your main IDE
settings are not affected:

```bash
cd review-plugin
./gradlew runIde
```

This is the best way to test during development. It downloads IntelliJ Community 2024.1
automatically on first run.

### Option C: Run in a specific IDE

```bash
# Run in a specific IntelliJ installation
./gradlew runIde -PalternativeIdePath="/path/to/IntelliJIDEA"
```

## Using the Plugin

### Creating a comment (human → agent)

1. Open any file in the editor
2. Select the lines you want to comment on (or place your cursor on a single line)
3. Right-click → **Add Review Comment** (or press **Ctrl+Alt+R**)
4. Type your comment and click OK

This writes a JSON file to `.review/comments/<id>.json` in the project root.

### Viewing comments

- **Gutter icons**: Red dot = open, green check = resolved, grey dash = won't fix.
  Hover for a tooltip with the comment body.
- **Tool window**: Click **Code Review** in the bottom tool window bar. Use the
  filter buttons (All / Open / Resolved) to narrow the list.
- **Navigation**: Click a gutter icon or a comment in the tool window to jump to
  the annotated code.

### Replying / resolving

In the Code Review tool window, select a comment, then:
- **Reply**: Type in the reply box and click "Reply"
- **Resolve**: Click "Resolve" (optionally add a reply message)
- **Won't Fix**: Click "Won't Fix"

### Ghost hunks

When a comment is resolved and the code has changed, the plugin renders the
original code as a greyed-out block inlay above the current position, so you can
see what was there before.

## Agent Integration

### Claude Code (recommended)

Install the [Remata Claude Code plugin](../remata-review-plugin/README.md) for
structured MCP tool access:

```bash
pip install mcp
claude plugin add ./remata-review-plugin
```

Then use `/remata:review` to list and address open comments, or call tools like
`list_reviews`, `show_review`, `resolve_review` directly.

### Any agent (manual)

An agent (Claude Code, Cursor, or any script) can also participate by reading and writing
JSON files in `.review/comments/` directly. No special library required.

### Reading open comments (Python example)

```python
import json, glob, os

def get_open_comments(project_root):
    pattern = os.path.join(project_root, ".review", "comments", "*.json")
    comments = []
    for path in glob.glob(pattern):
        with open(path) as f:
            c = json.load(f)
            if c["status"] == "open":
                comments.append(c)
    return comments
```

### Writing a new comment

```python
import json, os, random, subprocess
from datetime import datetime, timezone

def post_comment(project_root, file_rel, line, target_lines,
                 context_before, context_after, body):
    cid = random.randbytes(4).hex()
    commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"],
        cwd=project_root).decode().strip()

    comment = {
        "id": cid,
        "schema_version": 1,
        "author": "agent",
        "created": datetime.now(timezone.utc).isoformat(),
        "status": "open",
        "anchor": {
            "file": file_rel,
            "commit": commit,
            "line_hint": line,
            "hunk": {
                "context_before": context_before,
                "target": target_lines,
                "context_after": context_after
            }
        },
        "resolved_anchor": None,
        "body": body,
        "thread": []
    }

    dir_path = os.path.join(project_root, ".review", "comments")
    os.makedirs(dir_path, exist_ok=True)
    tmp = os.path.join(dir_path, f"{cid}.json.tmp")
    final = os.path.join(dir_path, f"{cid}.json")
    with open(tmp, "w") as f:
        json.dump(comment, f, indent=2)
    os.rename(tmp, final)  # atomic write
```

### Resolving a comment

```python
def resolve_comment(project_root, comment_id, reply_body):
    path = os.path.join(project_root, ".review", "comments", f"{comment_id}.json")
    with open(path) as f:
        comment = json.load(f)

    comment["status"] = "resolved"
    comment["thread"].append({
        "id": random.randbytes(4).hex(),
        "author": "agent",
        "created": datetime.now(timezone.utc).isoformat(),
        "body": reply_body,
        "status": "resolved"
    })

    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(comment, f, indent=2)
    os.rename(tmp, path)
```

## File Format

Each comment is a single JSON file in `.review/comments/`:

```
<project-root>/
└── .review/
    └── comments/
        ├── a3f1c2d4.json
        └── b7e902f1.json
```

| Field | Type | Description |
|---|---|---|
| `id` | string | 8-char hex, unique per file |
| `schema_version` | int | Always `1`. Plugin skips unknown versions |
| `author` | string | Free string — conventionally `"human"` or `"agent"` |
| `status` | enum | `"open"` / `"resolved"` / `"wontfix"` |
| `anchor.file` | string | Project-relative path, forward slashes |
| `anchor.commit` | string | Git SHA at comment creation time |
| `anchor.line_hint` | int | 1-based line number (hint, not authoritative) |
| `anchor.hunk` | object | `context_before`, `target`, `context_after` (string arrays) |
| `resolved_anchor` | object? | Same shape as `anchor`, written when code changes during resolution |
| `body` | string | The comment text |
| `thread` | array | Append-only reply list |

### Rules for writers

- Never modify `id`, `created`, `anchor`, or `author` after initial write
- To resolve: set root `status` to `"resolved"` and append a thread entry
- To reply: append to `thread[]` — never rewrite existing entries
- Write atomically: write to `<id>.json.tmp` then rename to `<id>.json`
- One comment per file, filename is always `<id>.json`

## Anchor Resolution

When the file has changed since the comment was created, the plugin resolves
the comment position through a 3-step algorithm:

1. **Exact match** — Check if lines at `line_hint` still match `hunk.target` (trimmed)
2. **Git diff remap** — Parse `git diff <commit>` to compute line offset
3. **Fuzzy search** — Sliding-window LCS over the full file (threshold: 0.75)
4. **Drifted** — If all steps fail, the comment is shown only in the tool window

## Project Structure

```
review-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/
    ├── main/kotlin/com/reviewplugin/
    │   ├── model/          # ReviewComment, Anchor, Hunk, ThreadEntry
    │   ├── store/          # CommentStore (project service), ReviewDirWatcher
    │   ├── anchor/         # HunkMatcher (pure algorithms), AnchorResolver, GitRunner
    │   ├── ui/
    │   │   ├── gutter/     # ReviewGutterIconProvider (line markers)
    │   │   ├── inlay/      # GhostHunkRenderer, GhostHunkInlayManager
    │   │   └── toolwindow/ # ReviewToolWindowFactory, ReviewToolWindowPanel
    │   └── actions/        # NewReviewCommentAction
    ├── main/resources/
    │   ├── META-INF/plugin.xml
    │   └── icons/review.svg
    └── test/kotlin/com/reviewplugin/
        ├── model/          # ReviewCommentTest (18 tests)
        ├── anchor/         # HunkMatcherTest (22 tests)
        └── store/          # CommentStoreFileIOTest (13 tests)
```

## CI

GitHub Actions runs on every push to `main` and `claude/**` branches:

- **build-and-test**: Compiles the plugin, runs all 53 unit tests
- **verify-plugin**: Validates the plugin descriptor, produces the installable zip as an artifact

## Sharing Review State

The `.review/` directory is designed to be committed to git. This way:
- Comments persist across IDE restarts
- Agent and human share annotation state across sessions
- Review history is preserved in version control

Add `.review/` to `.gitignore` only if you want annotations to be local-only.
