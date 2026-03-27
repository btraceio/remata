# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Claude Code plugin (`remata-review-plugin/`) with MCP server and slash command
- MCP tools: `list_reviews`, `show_review`, `reply_to_review`, `resolve_review`, `create_review`
- `/remata:review` skill for automated review comment triage
- Python MCP server using stdio transport (stdlib + `mcp` package)

## [0.1.0] - 2026-03-26

### Added

- Initial release of the Agent Review IntelliJ plugin
- Comment file format: `.review/comments/<id>.json` with schema version 1
- Data model: `ReviewComment`, `Anchor`, `Hunk`, `ThreadEntry`, `CommentStatus`
- `CommentStore` project service with in-memory cache, atomic file writes, change listeners
- `ReviewDirWatcher` for real-time `.review/` directory sync via `VirtualFileListener`
- Anchor resolution: exact match at line hint, git diff remap, fuzzy LCS search, drifted fallback
- Gutter icons: red dot (open), green check (resolved), grey dash (won't fix)
- Ghost hunk inlay: greyed-out block renderer showing original code for resolved comments
- Code Review tool window with comment list, thread view, reply/resolve/won't fix actions
- "Add Review Comment" editor action with keyboard shortcut (`Ctrl+Alt+R`)
- Git integration via `GeneralCommandLine` (no `git4idea` dependency)
- Support for Java, Kotlin, Python, JavaScript, TypeScript, C/C++, XML, and plain text files
- 53 unit tests across model serialization, anchor algorithms, and file I/O
- GitHub Actions CI: build, test, plugin verification
- Agent protocol documentation with Python examples
