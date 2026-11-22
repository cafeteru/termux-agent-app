---
name: github-pr-creator
description: Create GitHub Pull Requests via Python and gh CLI with automatic git merge-tree conflict checks, 4-block template validation, and post-creation verification.
---

# GitHub PR Creator (Python + `gh`)

Create Pull Requests on `github.com` via `create_pr.py` to avoid shell quoting/encoding issues and enforce pre-merge conflict checks.

## 1. Guardrails & 4-Block Description Template

1. **Write gate:** Never push directly to `main`/`master` or auto-merge without explicit user direction. Run `git push` separately before running `create_pr.py`.
   - When the user later explicitly says **merge**, **mergea**, **fusiona**, **integra**, **land it**, or similar, treat it as approval to merge the relevant PR. For an ordinary standalone PR, use squash merge and complete cleanup.
   - After merge: verify `MERGED`, delete the remote branch, fetch/prune, switch to `main`, fast-forward `main`, delete the local feature branch, and verify the worktree is clean.
2. **Conflict check:** `create_pr.py` automatically runs `git merge-tree` against `origin/<target-branch>` and aborts on conflicts; after creation it verifies `mergeable` and `mergeStateStatus` via `gh pr view`.
3. **Execution safety & idempotency:**
   - Runs `gh` with `stdin=DEVNULL`, `GH_PAGER=cat`, `GH_PROMPT_DISABLED=1`, and `--body-file` to prevent interactive hangs and shell escaping issues.
   - Handles existing open PRs for the source branch gracefully, returning existing PR status without failing.
   - Detects expired tokens / auth errors and gives actionable `gh auth login` instructions.
4. **Assignee:** Assigned to `cafeteru` by default on `github.com` (`DEFAULT_ASSIGNEES` in `create_pr.py`). Use `--assignee` to override.
5. **4-Block PR Description Template:**
   ```markdown
   ## What
   - Summary of changes and protagonist files modified.

   ## Why
   - Root cause, architectural motivation, or roadmap step.

   ## How It Was Tested
   - Local checks and CI verification (`git diff --check`, unit tests, `--dry-run`, `git merge-tree`).

   ## Follow-Up & Rollout
   - Next roadmap steps or `None`.
   ```

## 2. Usage & CLI Options

```bash
# 1. Dry-run (previews the payload without checking conflicts or calling GitHub):
python3 .github/skills/github-pr-creator/scripts/create_pr.py --title "feat: title" --description-file scratch/pr_description.md --dry-run

# 2. Create PR:
python3 .github/skills/github-pr-creator/scripts/create_pr.py --title "feat: title" --description-file scratch/pr_description.md
```

| Option | Default | Description |
|---|---|---|
| `--title` | *(Required)* | PR title. |
| `--description-file` / `--description` | `""` | UTF-8 Markdown file path or inline string. |
| `--repo` / `--hostname` | Auto-detected | `owner/repo` and host (`github.com`) from `git remote -v`. |
| `--source-branch` / `--target-branch` | `HEAD` / `main` | Head (`--head`) and base (`--base`) branches. |
| `--assignee` | `cafeteru` | Assignee username on `github.com`. |
| `--reviewer` / `--no-reviewer` | `[]` | Repeatable reviewer usernames / skip default reviewers. |
| `--draft` | `false` | Create PR as draft (`--draft`). |
| `--dry-run` / `--no-check-conflicts` | `false` | Preview payload without API call / skip `git merge-tree` check. |
