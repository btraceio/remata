---
name: review
description: Check for open code review comments and address them. Use when a human has left review feedback via the Remata IntelliJ plugin.
allowed-tools: mcp__remata__list_reviews, mcp__remata__show_review, mcp__remata__reply_to_review, mcp__remata__resolve_review, mcp__remata__create_review, Read, Edit, Grep, Glob, Bash, Agent
---

# Review Workflow

You are responding to code review comments left by a human in the Remata review system.

## Steps

1. **List open reviews** — call `list_reviews` with status "open"
2. **For each open comment:**
   a. Call `show_review` to see the full comment, code context, and thread
   b. Read the referenced file to understand the current state of the code
   c. Decide how to address the feedback:
      - If it's a bug or improvement you can fix: make the code change, then resolve the review
      - If you need clarification: reply with a question (do not resolve)
      - If the comment is already addressed or no longer applicable: resolve with an explanation
      - If you disagree with the feedback: reply explaining your reasoning (do not resolve)
   d. After fixing code, call `resolve_review` with a clear explanation of what you changed
3. **Summarize** what you did for each comment

## Rules

- Always read the referenced file before acting on a comment
- Fix the actual issue, not just the symptom
- When resolving, explain specifically what changed (file, line, what was done)
- Do not silently ignore comments — every open comment needs a reply or resolution
- If a comment spans multiple files or requires a large refactor, reply with your plan before acting
