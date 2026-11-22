# Agent Guidelines

## Read First

- Read [README.md](README.md) for the current architecture, project layout, build commands, and test coverage.
- Follow [docs/termux-development.md](docs/termux-development.md) for Termux-specific builds and on-device testing.
- Inspect the code before editing. The repository must remain the source of truth when this document differs from implementation.

## Architecture Guardrails

1. Treat `agy` and `copilot` as external black-box processes. Never patch their binaries, wrap their executables, or inject required configuration, hooks, or plugins into their installations.
2. Keep integration adapters inside the Android APK. Do not install a custom daemon or bridge in Termux.
3. Use Termux's standard OpenSSH server on `127.0.0.1:8022`.
4. Keep SSH and PTY lifecycle ownership in the Android `ForegroundService`.
5. Preserve actionable permission and turn-completion notifications, including direct **Allow** and **Deny** actions.

## Adapter Contract

All agent adapters must implement the shared `AgentAdapter` port and normalize agent-specific launch, session discovery, transcript parsing, screen detection, prompt submission, permission resolution, and lifecycle behavior.

Keep package dependencies directed inward:

- `domain` contains framework-free models and must not import other app layers.
- `application` owns ports, configuration, and orchestration contracts.
- `infrastructure` implements ports for agents, SSH, terminal, and Android.
- `presentation` renders UI and coordinates calls into application/infrastructure facades.
- Agent-specific `agy`/`copilot` branches belong inside concrete infrastructure strategies, not in UI or SSH orchestration.

For the current contract and agent-specific behavior, use:

- [README.md](README.md#adapter-flow)
- `app/src/main/java/com/termux/agent/application/port/`
- `app/src/main/java/com/termux/agent/infrastructure/agent/`

## Engineering Workflow

1. Keep changes small, direct, and free of speculative abstractions (YAGNI).
2. Develop features, fixes, and documentation on descriptive branches and merge them into `main` through pull requests.
3. Create a pull request only when the user explicitly requests one. Use the `github-pr-creator` skill and its four-section template:
   - **What**
   - **Why**
   - **How It Was Tested**
   - **Follow-Up & Rollout**
4. Push the branch before running `.github/skills/github-pr-creator/scripts/create_pr.py`.
5. Check conflicts with the target branch, and assign the pull request to `cafeteru`.
6. Never create a pull request or merge automatically after implementing a change.
7. Never merge a pull request without explicit user approval.
8. After approval, always squash-merge and delete the remote branch and local branch:
   ```bash
   gh pr merge <pr-number> --squash --delete-branch
   git fetch origin --prune
   git switch main
   git pull --ff-only origin main
   git branch -d <branch>  # if Git requires force after squash, verify merge then use -D
   git status --short --branch
   ```
   Treat “mergea”, “fusiona”, “integra esto”, “merge this PR”, “land it”, or similar
   wording as explicit approval to execute this complete flow. Before switching
   branches, verify the worktree owner and preserve unrelated uncommitted work.

The repository hooks in `.agents/hooks.json` enforce the pull-request and merge guardrails.

## Verification

- Preserve existing behavior unless the task explicitly changes it.
- Run the narrowest relevant tests before expanding to the full suite.
- Keep unit tests platform-neutral. Tests for Termux shell commands must detect the required runtime capability and skip with a clear reason when unavailable; do not assert host-specific `sh`, Python, path, or symlink behavior.
- For Android features or fixes, follow the Termux build and on-device verification steps in [docs/termux-development.md](docs/termux-development.md).
- Keep `versionName = "0.0.1"` and `versionCode = 1` until the initial functional MVP is ready.

## Specialized Agent Team

The repository exposes isolated Copilot agent profiles under `.github/agents/`:

- `platform-orchestrator`: architecture, routing, and concise synthesis.
- `android-codebase-specialist`: Kotlin, Compose, SSH/PTTY, adapters, transcripts, and notifications.
- `android-ui-ux-specialist`: mobile hierarchy, touch ergonomics, accessibility, and UX regression coverage.
- `android-build-release-specialist`: Gradle tests, ARM64 builds, APK delivery, and build failure triage.

Use the orchestrator for multi-area work. Route UI/a11y work explicitly to `android-ui-ux-specialist`; route adapter, SSH, or session behavior to `android-codebase-specialist`; route full validation and APK delivery to `android-build-release-specialist`.
