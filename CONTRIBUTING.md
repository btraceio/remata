# Contributing to Remata

Thank you for your interest in contributing to the Agent Review plugin.

## Development Setup

### Prerequisites

- JDK 17+
- Git
- IntelliJ IDEA (recommended for plugin development, but not required to build)

### Clone and build

```bash
git clone https://github.com/btraceio/remata.git
cd remata/review-plugin
./gradlew buildPlugin
```

### Run tests

```bash
./gradlew test
```

### Run a sandboxed IDE with the plugin

```bash
./gradlew runIde
```

This launches a clean IntelliJ instance with the plugin loaded. Your main IDE settings
are unaffected.

## Project Layout

```
review-plugin/src/
├── main/kotlin/com/reviewplugin/
│   ├── model/       # Data classes — ReviewComment, Anchor, Hunk, ThreadEntry
│   ├── store/       # CommentStore (project service), ReviewDirWatcher (VFS listener)
│   ├── anchor/      # HunkMatcher (pure algorithms), AnchorResolver, GitRunner
│   ├── ui/
│   │   ├── gutter/     # Gutter icon line markers
│   │   ├── inlay/      # Ghost hunk block inlay renderer
│   │   └── toolwindow/ # Comment list + thread panel
│   └── actions/     # New comment editor action
└── test/kotlin/com/reviewplugin/
    ├── model/       # Serialization & model tests
    ├── anchor/      # HunkMatcher algorithm tests
    └── store/       # File I/O tests
```

## Making Changes

### Branching

- Create a feature branch from `main`: `git checkout -b feature/my-change`
- Keep commits focused — one logical change per commit
- Write descriptive commit messages

### Code Style

- Follow existing Kotlin conventions in the codebase
- No wildcard imports
- Prefer `val` over `var`
- Keep IntelliJ platform dependencies out of `model/` and `anchor/HunkMatcher.kt` —
  these must remain pure Kotlin for testability

### Testing

All changes should include tests. The test strategy:

| Layer | Test approach | IntelliJ deps? |
|---|---|---|
| `model/` | JSON round-trip, field semantics | No |
| `anchor/HunkMatcher` | Algorithm correctness (exact match, diff remap, fuzzy search) | No |
| `store/` | File I/O with temp directories | No |
| UI / actions | Manual testing via `./gradlew runIde` | Yes |

Run the full suite before submitting:

```bash
./gradlew test
```

### Updating the comment file format

If you change the JSON schema:

1. Bump `schema_version` in the data model
2. Add backward-compat handling in `CommentStore.reload()` for the old version
3. Update the file format table in `review-plugin/README.md`
4. Update the agent examples in both READMEs

## Pull Request Process

1. Ensure `./gradlew test` passes
2. Ensure `./gradlew verifyPluginConfiguration` passes
3. Update `CHANGELOG.md` with your changes under the `[Unreleased]` section
4. Open a PR against `main` with a clear description
5. CI will run automatically — all checks must pass before merge

## Reporting Issues

Use the [GitHub issue tracker](https://github.com/btraceio/remata/issues).
Please include:

- IntelliJ version and OS
- Steps to reproduce
- Expected vs actual behavior
- Relevant `.review/comments/*.json` files (if applicable)

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
