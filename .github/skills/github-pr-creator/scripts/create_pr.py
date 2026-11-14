#!/usr/bin/env python3
"""Create GitHub pull requests with safe UTF-8 encoding and conflict checking.

Handles multi-line Markdown descriptions without shell quotation or encoding
issues. Validates pre-merge conflicts against target branches and inspects
post-creation mergeability via gh CLI.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Optional


DEFAULT_ASSIGNEES = {
    "github.com": "cafeteru",
}

DEFAULT_REVIEWERS: dict[str, list[str]] = {
    "github.com": [],
}

RECOMMENDED_SECTIONS = (
    ("What", r"(?im)^#{1,3}\s*(?:1\.?\s*)?(?:what|qu[eé]|summary)\b"),
    ("Why", r"(?im)^#{1,3}\s*(?:2\.?\s*)?(?:why|por\s+qu[eé]|motivation|context)\b"),
    ("How It Was Tested", r"(?im)^#{1,3}\s*(?:3\.?\s*)?(?:how|test|validation|c[oó]mo\s+se\s+prob[oó])\b"),
    ("Follow-Up & Rollout", r"(?im)^#{1,3}\s*(?:4\.?\s*)?(?:follow[- ]?up|rollout|pending|pendiente|impact)\b"),
)

AUTH_ERROR_PATTERNS = (
    r"authentication failed",
    r"401\s+Unauthorized",
    r"token.*expired",
    r"not logged into any GitHub hosts",
    r"gh auth login",
    r"HTTP 40[13]",
)

ALREADY_EXISTS_PATTERN = r"already exists:\s*(https?://[^\s]+/pull/(\d+))"


def check_auth_error(output: str) -> bool:
    """Detect authentication or token expiration errors from gh CLI output."""
    return any(re.search(pat, output, re.IGNORECASE) for pat in AUTH_ERROR_PATTERNS)


def check_description_sections(description: str) -> list[str]:
    """Return any of the 4 recommended reviewable PR sections missing from description."""
    if not description or not description.strip():
        return [name for name, _ in RECOMMENDED_SECTIONS]
    return [
        name
        for name, pattern in RECOMMENDED_SECTIONS
        if not re.search(pattern, description)
    ]


def run_cmd(
    cmd: list[str],
    cwd: Optional[str] = None,
    env: Optional[dict[str, str]] = None,
    capture: bool = True,
    check: bool = False,
    timeout: Optional[int] = 60,
) -> subprocess.CompletedProcess:
    """Run a command using subprocess with safe non-interactive defaults and no shell interpolation."""
    run_env = os.environ.copy()
    run_env.setdefault("PAGER", "cat")
    run_env.setdefault("GIT_PAGER", "cat")
    run_env.setdefault("GH_PAGER", "cat")
    run_env.setdefault("GH_PROMPT_DISABLED", "1")
    run_env.setdefault("NO_COLOR", "1")
    run_env.setdefault("TERM", "dumb")
    if env:
        run_env.update(env)
    return subprocess.run(
        cmd,
        cwd=cwd,
        env=run_env,
        capture_output=capture,
        text=True,
        check=check,
        stdin=subprocess.DEVNULL,
        timeout=timeout,
        encoding="utf-8",
        errors="replace",
    )


def format_repo_arg(hostname: str, repo: str) -> str:
    """Format repository identifier for gh --repo flag."""
    if hostname and hostname != "github.com":
        return f"{hostname}/{repo}"
    return repo


def get_current_github_user(hostname: str) -> Optional[str]:
    """Detect current GitHub user using gh api user."""
    proc = run_cmd(
        ["gh", "api", "user", "--hostname", hostname, "--jq", ".login"],
        cwd=tempfile.gettempdir(),
    )
    if proc.returncode == 0 and proc.stdout.strip():
        return proc.stdout.strip()
    return None


def detect_git_remote_info() -> tuple[Optional[str], Optional[str]]:
    """Detect hostname and repository path from current git remote."""
    proc = run_cmd(["git", "remote", "-v"])
    if proc.returncode != 0 or not proc.stdout:
        return None, None

    for line in proc.stdout.splitlines():
        if "(push)" not in line and "(fetch)" not in line:
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        url = parts[1]
        ssh_match = re.match(r"^git@([^:]+):(.+?)(?:\.git)?$", url)
        if ssh_match:
            return ssh_match.group(1), ssh_match.group(2)
        http_match = re.match(r"^https?://([^/]+)/(.+?)(?:\.git)?$", url)
        if http_match:
            return http_match.group(1), http_match.group(2)
    return None, None


def get_current_branch() -> Optional[str]:
    """Get the current branch name."""
    proc = run_cmd(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    if proc.returncode == 0 and proc.stdout.strip():
        branch = proc.stdout.strip()
        if branch != "HEAD":
            return branch
    return None


def check_merge_conflicts(target_branch: str) -> tuple[bool, str]:
    """Check for merge conflicts against the target branch using git merge-tree."""
    run_cmd(["git", "fetch", "origin", target_branch])

    target_ref = f"origin/{target_branch}"
    base_proc = run_cmd(["git", "merge-base", "HEAD", target_ref])
    if base_proc.returncode != 0 or not base_proc.stdout.strip():
        return False, f"Could not determine merge-base between HEAD and {target_ref}."

    base_commit = base_proc.stdout.strip()
    tree_proc = run_cmd(["git", "merge-tree", base_commit, "HEAD", target_ref])
    if tree_proc.returncode != 0:
        return False, f"git merge-tree failed: {tree_proc.stderr}"

    output = tree_proc.stdout
    if "<<<<<<<" in output or "CONFLICT" in output:
        conflict_lines = [
            line for line in output.splitlines()
            if "CONFLICT" in line or "changed in both" in line
        ]
        return False, "Merge conflicts detected:\n" + "\n".join(conflict_lines[:15])

    return True, "No merge conflicts detected."


def inspect_pr_status(hostname: str, repo: str, pr_ref: str | int) -> dict:
    """Inspect PR merge status via gh pr view."""
    repo_arg = format_repo_arg(hostname, repo)
    proc = run_cmd(
        [
            "gh",
            "pr",
            "view",
            str(pr_ref),
            "--repo",
            repo_arg,
            "--json",
            "number,url,mergeable,mergeStateStatus,isDraft,state",
        ],
        cwd=tempfile.gettempdir(),
    )
    if proc.returncode != 0:
        return {"error": proc.stderr.strip() or f"gh pr view returned code {proc.returncode}"}

    try:
        data = json.loads(proc.stdout)
        mergeable_val = data.get("mergeable")
        merge_state = data.get("mergeStateStatus")
        has_conflicts = mergeable_val == "CONFLICTING" or merge_state == "DIRTY"
        return {
            "number": data.get("number"),
            "has_conflicts": has_conflicts,
            "detailed_merge_status": f"{mergeable_val} ({merge_state})",
            "mergeable": mergeable_val == "MERGEABLE",
            "web_url": data.get("url"),
        }
    except json.JSONDecodeError:
        return {"error": "Invalid JSON response from gh pr view"}


def create_pull_request(
    title: str,
    description: str,
    repo: str,
    source_branch: str,
    target_branch: str,
    hostname: str,
    assignee: Optional[str] = None,
    reviewers: Optional[list[str]] = None,
    draft: bool = False,
    dry_run: bool = False,
) -> dict:
    """Create a GitHub pull request using gh CLI."""
    if not assignee:
        assignee = DEFAULT_ASSIGNEES.get(hostname)
        if assignee is None:
            assignee = get_current_github_user(hostname)
    if reviewers is None:
        reviewers = DEFAULT_REVIEWERS.get(hostname, [])

    missing_sections = check_description_sections(description)

    if dry_run:
        return {
            "dry_run": True,
            "title": title,
            "repo": repo,
            "hostname": hostname,
            "source_branch": source_branch,
            "target_branch": target_branch,
            "assignee": assignee,
            "reviewers": reviewers,
            "draft": draft,
            "description_length": len(description),
            "missing_recommended_sections": missing_sections,
        }

    gh_bin = shutil.which("gh")
    if not gh_bin:
        raise RuntimeError("gh CLI not found on PATH. Install or add gh to PATH.")

    temp_dir = tempfile.gettempdir()
    repo_arg = format_repo_arg(hostname, repo)

    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix=".md") as desc_file:
        desc_file.write(description)
        desc_path = desc_file.name

    try:
        cmd = [
            gh_bin,
            "pr",
            "create",
            "--repo",
            repo_arg,
            "--head",
            source_branch,
            "--base",
            target_branch,
            "--title",
            title,
            "--body-file",
            desc_path,
        ]
        if assignee:
            cmd.extend(["--assignee", assignee])
        for reviewer in reviewers:
            cmd.extend(["--reviewer", reviewer])
        if draft:
            cmd.append("--draft")

        proc = run_cmd(cmd, cwd=temp_dir)
        if proc.returncode != 0:
            error_output = f"{proc.stderr}\n{proc.stdout}".strip()
            if check_auth_error(error_output):
                raise RuntimeError(
                    f"GitHub authentication failed for '{hostname}'.\n"
                    f"Please re-authenticate by running:\n  gh auth login --hostname {hostname}"
                )

            match_exists = re.search(ALREADY_EXISTS_PATTERN, error_output)
            if match_exists:
                existing_url = match_exists.group(1)
                pr_number = int(match_exists.group(2))
                status_info = inspect_pr_status(hostname, repo, pr_number)
                result = {
                    "status": "already_exists",
                    "url": status_info.get("web_url") or existing_url,
                    "number": pr_number,
                    "repo": repo,
                    "hostname": hostname,
                    "source_branch": source_branch,
                    "target_branch": target_branch,
                }
                result.update(status_info)
                return result

            raise RuntimeError(f"gh pr create failed (code {proc.returncode}):\n{error_output}")

        pr_url = None
        pr_number = None
        for line in proc.stdout.splitlines():
            line = line.strip()
            url_match = re.search(r"https?://[^\s]+/pull/(\d+)", line)
            if url_match:
                pr_url = url_match.group(0)
                pr_number = int(url_match.group(1))
                break

        result = {
            "status": "created",
            "url": pr_url,
            "number": pr_number,
            "repo": repo,
            "hostname": hostname,
            "source_branch": source_branch,
            "target_branch": target_branch,
        }

        if pr_number:
            status_info = inspect_pr_status(hostname, repo, pr_number)
            result.update(status_info)

        return result
    finally:
        if os.path.exists(desc_path):
            os.remove(desc_path)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(
        description="Create GitHub pull requests cleanly without encoding or shell-escaping issues."
    )
    parser.add_argument("--title", required=True, help="Pull request title")
    parser.add_argument("--description", help="Markdown description text")
    parser.add_argument("--description-file", help="Path to Markdown file containing the description")
    parser.add_argument("--repo", help="Target GitHub repository path (e.g. cafeteru/termux-agent-app)")
    parser.add_argument("--source-branch", help="Source/head branch (defaults to current branch)")
    parser.add_argument("--target-branch", default="main", help="Target/base branch (default: main)")
    parser.add_argument("--hostname", help="GitHub hostname (default: github.com)")
    parser.add_argument("--assignee", help="Assignee username")
    parser.add_argument("--reviewer", action="append", default=None, help="Reviewer username (repeatable)")
    parser.add_argument("--no-reviewer", action="store_true", help="Skip default reviewers for this host")
    parser.add_argument("--draft", action="store_true", help="Mark PR as draft")
    parser.add_argument("--no-check-conflicts", action="store_true", help="Skip pre-merge conflict check")
    parser.add_argument("--dry-run", action="store_true", help="Preview PR without creating it remotely")

    args = parser.parse_args()

    description = ""
    if args.description_file:
        if not os.path.isfile(args.description_file):
            print(f"Error: Description file not found: {args.description_file}", file=sys.stderr)
            return 1
        with open(args.description_file, "r", encoding="utf-8") as f:
            description = f.read()
    elif args.description:
        description = args.description

    detected_host, detected_repo = detect_git_remote_info()
    hostname = args.hostname or detected_host or "github.com"
    repo = args.repo or detected_repo
    if not repo:
        print("Error: Could not determine repository. Specify --repo <owner/repo>.", file=sys.stderr)
        return 1

    source_branch = args.source_branch or get_current_branch()
    if not source_branch:
        print("Error: Could not determine source branch. Specify --source-branch <name>.", file=sys.stderr)
        return 1

    target_branch = args.target_branch

    missing_sections = check_description_sections(description)
    if missing_sections:
        print(
            "Note: PR description is missing recommended 4-block sections: "
            + ", ".join(missing_sections),
            file=sys.stderr,
        )

    if not args.dry_run:
        unpushed = run_cmd(["git", "log", f"origin/{source_branch}..HEAD", "--oneline"])
        if unpushed.returncode == 0 and unpushed.stdout.strip():
            unpushed_lines = unpushed.stdout.strip().splitlines()
            print(
                f"Warning: Branch '{source_branch}' has {len(unpushed_lines)} unpushed commit(s) relative to origin/{source_branch}:\n"
                + "\n".join(f"  {line}" for line in unpushed_lines[:5])
                + "\nMake sure you have run 'git push' before opening the PR.",
                file=sys.stderr,
            )

    if not args.no_check_conflicts and not args.dry_run:
        print(f"Checking merge conflicts between '{source_branch}' and '{target_branch}'...")
        can_merge, msg = check_merge_conflicts(target_branch)
        if not can_merge:
            print(f"Error: {msg}", file=sys.stderr)
            print("Resolve conflicts locally before creating the pull request.", file=sys.stderr)
            return 1
        print(f"✓ {msg}")

    try:
        result = create_pull_request(
            title=args.title,
            description=description,
            repo=repo,
            source_branch=source_branch,
            target_branch=target_branch,
            hostname=hostname,
            assignee=args.assignee,
            reviewers=[] if args.no_reviewer else args.reviewer,
            draft=args.draft,
            dry_run=args.dry_run,
        )

        if args.dry_run:
            print("--- DRY RUN PREVIEW ---")
            print(json.dumps(result, indent=2))
            return 0

        status_header = (
            "=== Existing Pull Request Found ==="
            if result.get("status") == "already_exists"
            else "=== Pull Request Created Successfully ==="
        )
        print(f"\n{status_header}")
        print(f"URL: {result.get('url')}")
        print(f"Number: #{result.get('number')}")
        print(f"Repository: {result.get('repo')}")
        print(f"Target: {result.get('target_branch')}")
        print(f"Has conflicts: {result.get('has_conflicts')}")
        print(f"Detailed merge status: {result.get('detailed_merge_status')}")

        if result.get("has_conflicts"):
            print("\nWARNING: Pull request reports conflicts with target branch!", file=sys.stderr)
            return 2

        return 0
    except Exception as e:
        print(f"Error creating pull request: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
