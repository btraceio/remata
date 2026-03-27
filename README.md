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

### Claude Code integration

Install the Claude Code plugin so Claude can discover and respond to your reviews:

```bash
pip install mcp
claude plugin add ./remata-review-plugin
```

Then in any project with review comments, run `/remata:review` — Claude will list open
comments, fix the issues, and resolve them with explanations.

See [`remata-review-plugin/README.md`](remata-review-plugin/README.md) for details.

## Documentation

| Document | Description |
|---|---|
| [`review-plugin/README.md`](review-plugin/README.md) | Build instructions, plugin usage, agent API, file format spec |
| [`remata-review-plugin/README.md`](remata-review-plugin/README.md) | Claude Code plugin: installation, MCP tools, slash command |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute: setup, testing, PR guidelines |
| [`CHANGELOG.md`](CHANGELOG.md) | Release history |

## Project Structure

```
remata/
├── review-plugin/               # IntelliJ plugin (Kotlin)
│   ├── src/main/kotlin/         # Plugin source
│   ├── src/test/kotlin/         # 53 unit tests
│   ├── build.gradle.kts         # IntelliJ Platform Gradle Plugin 2.3.0
│   └── README.md                # Detailed plugin docs
├── remata-review-plugin/        # Claude Code plugin
│   ├── .claude-plugin/          # Plugin manifest
│   ├── server/review-server.py  # MCP server (5 tools)
│   ├── skills/review/SKILL.md   # /remata:review slash command
│   └── README.md                # Installation & usage
├── .github/
│   ├── workflows/ci.yml         # Build, test, verify on every push
│   ├── ISSUE_TEMPLATE/          # Bug report & feature request templates
│   └── PULL_REQUEST_TEMPLATE.md
├── CONTRIBUTING.md
├── CHANGELOG.md
└── LICENSE                       # Apache 2.0
```

## Requirements

- JDK 17+
- IntelliJ IDEA 2024.1+ (or any JetBrains IDE on the same platform)
- Git on PATH

## License

[Apache License 2.0](LICENSE)
