# Remata — Agent Review for IntelliJ

An IntelliJ plugin that renders file-based code review annotations written by a human or a
coding agent. No server. No network. The protocol is a directory of JSON files.

```
Human writes comment in IDE  ──►  .review/comments/a3f1c2d4.json  ◄──  Agent reads & replies
```

## How It Works

1. A human highlights code in IntelliJ, right-clicks **Add Review Comment**
2. The plugin writes a JSON file to `.review/comments/` in the project
3. An agent (Claude Code, Cursor, any script) reads the file, acts on it, and writes a reply
4. The plugin picks up the reply in real time — gutter icons, tool window, and ghost hunks update

No server. No accounts. The `.review/` directory is the entire protocol. Commit it to git and
the review state travels with the code.

## Quick Start

### Build & install

```bash
cd review-plugin
./gradlew buildPlugin
# Output: build/distributions/review-plugin-0.1.0.zip
```

Install in IntelliJ: **Settings > Plugins > gear icon > Install Plugin from Disk...**

Or run a sandboxed IDE for testing:

```bash
./gradlew runIde
```

### Use in the IDE

| Action | How |
|---|---|
| Add comment | Select code, right-click > **Add Review Comment** (or `Ctrl+Alt+R`) |
| View comments | **Code Review** tool window (bottom bar) |
| Reply / Resolve | Select comment in tool window, use Reply / Resolve / Won't Fix buttons |

### Agent side (Python example)

```python
import json, os, glob

# Read open comments
for path in glob.glob(".review/comments/*.json"):
    comment = json.load(open(path))
    if comment["status"] == "open":
        print(f"{comment['anchor']['file']}:{comment['anchor']['line_hint']} — {comment['body']}")
```

See [`review-plugin/README.md`](review-plugin/README.md) for the full agent integration API
with write and resolve examples.

## Documentation

| Document | Description |
|---|---|
| [`review-plugin/README.md`](review-plugin/README.md) | Build instructions, plugin usage, agent API, file format spec |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute: setup, testing, PR guidelines |
| [`CHANGELOG.md`](CHANGELOG.md) | Release history |

## Project Structure

```
remata/
├── review-plugin/           # IntelliJ plugin (Kotlin)
│   ├── src/main/kotlin/     # Plugin source
│   ├── src/test/kotlin/     # 53 unit tests
│   ├── build.gradle.kts     # IntelliJ Platform Gradle Plugin 2.3.0
│   └── README.md            # Detailed plugin docs
├── .github/
│   ├── workflows/ci.yml     # Build, test, verify on every push
│   ├── ISSUE_TEMPLATE/      # Bug report & feature request templates
│   └── PULL_REQUEST_TEMPLATE.md
├── CONTRIBUTING.md
├── CHANGELOG.md
└── LICENSE                   # Apache 2.0
```

## Requirements

- JDK 17+
- IntelliJ IDEA 2024.1+ (or any JetBrains IDE on the same platform)
- Git on PATH

## License

[Apache License 2.0](LICENSE)
