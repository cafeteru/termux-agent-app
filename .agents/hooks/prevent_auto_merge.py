#!/usr/bin/env python3
"""Antigravity PreToolUse hook that prevents automatic PR merges and direct pushes to main/master."""

import json
import re
import sys

DIRECT_MAIN_PUSH_RE = re.compile(
    r"\bgit\s+push\b.*(?:\s|:)(?:refs/heads/)?(?:main|master)(?:\s|$|;|&|\|)"
)
GH_PR_MERGE_RE = re.compile(
    r"\bgh\s+pr\s+merge\b|\bgh\s+api\b.*(?:/pulls/\d+/merge|mergePullRequest)"
)
NEGATED_MERGE_RE = re.compile(
    r"\b(?:no|not|never|nunca|sin|evita(?:r)?|don't|do\s+not|dont)\b(?:\s+\w+){0,3}\s+(?:merge\w*|fusion\w*)"
)
EXPLICIT_MERGE_RE = re.compile(r"\b(?:mergea\w*|mergear|merge|fusiona\w*|fusionar)\b")


def user_explicitly_requested_merge(last_user_input: str) -> bool:
    """Return True only when the user explicitly asked to merge without negation."""
    lower = last_user_input.lower()
    if NEGATED_MERGE_RE.search(lower):
        return False
    return bool(EXPLICIT_MERGE_RE.search(lower))


def evaluate_hook(payload: dict) -> dict:
    """Evaluate a PreToolUse hook payload and return the decision JSON object."""
    tool_call = payload.get("toolCall") or {}
    args = tool_call.get("args") or {}
    command_line = str(args.get("CommandLine") or "")
    last_user_input = str(payload.get("lastUserInput") or "")

    if DIRECT_MAIN_PUSH_RE.search(command_line):
        return {
            "decision": "deny",
            "reason": (
                "Bloqueado por hook: no se permite hacer git push directo a "
                "main/master. Usa una rama y Pull Request."
            ),
        }

    if GH_PR_MERGE_RE.search(command_line):
        if user_explicitly_requested_merge(last_user_input):
            return {
                "decision": "force_ask",
                "reason": "Confirmación requerida antes de mergear el Pull Request.",
            }
        return {
            "decision": "deny",
            "reason": (
                "Bloqueado por hook: no está permitido mergear PRs automáticamente. "
                "Deja el PR abierto para revisión del usuario."
            ),
        }

    return {"decision": "ask"}


def run_self_tests() -> None:
    """Verify hook decisions across allowed, denied, and force_ask scenarios."""
    assert evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "gradle test"}},
            "lastUserInput": "run tests",
        }
    ) == {"decision": "ask"}

    assert evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "git push -u origin feat/my-branch"}},
            "lastUserInput": "push branch",
        }
    ) == {"decision": "ask"}

    res_no_request = evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "gh pr merge 3 --merge"}},
            "lastUserInput": "crea el menu de ssh",
        }
    )
    assert res_no_request["decision"] == "deny"

    res_negated = evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "gh pr merge 3 --merge"}},
            "lastUserInput": "no mergear automaticamente",
        }
    )
    assert res_negated["decision"] == "deny"

    res_explicit = evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "gh pr merge 3 --merge"}},
            "lastUserInput": "mergea el PR 3",
        }
    )
    assert res_explicit["decision"] == "force_ask"

    res_main_push = evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "git push origin main"}},
            "lastUserInput": "push changes",
        }
    )
    assert res_main_push["decision"] == "deny"

    res_head_main_push = evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "git push origin HEAD:main"}},
            "lastUserInput": "push changes",
        }
    )
    assert res_head_main_push["decision"] == "deny"


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        run_self_tests()
        print("prevent_auto_merge self-tests passed.")
        return 0

    raw = sys.stdin.read().strip()
    payload = json.loads(raw) if raw else {}
    print(json.dumps(evaluate_hook(payload)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
