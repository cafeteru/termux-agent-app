#!/usr/bin/env python3
"""Antigravity PreToolUse hook that blocks creating Pull Requests unless explicitly requested."""

import json
import re
import sys
import unicodedata

GH_PR_CREATE_RE = re.compile(r"\bgh\s+pr\s+create\b")
CREATE_PR_SCRIPT_RE = re.compile(r"\bcreate_pr\.py\b")
DRY_RUN_OR_HELP_RE = re.compile(r"(?:--dry-run|--help|(?:^|\s)-h(?:\s|$))")
GH_API_RE = re.compile(r"\bgh\s+api\b")
GH_API_POST_RE = re.compile(r"(?:-X\s*POST|--method(?:\s+|=)POST)")
GH_API_PULLS_RE = re.compile(r"/pulls\b")
SEGMENT_SPLIT_RE = re.compile(r"\s*(?:&&|\|\||;|\n)\s*")

PR_NOUN_PATTERN = r"(?:prs?|mrs?|(?:pull|merge)[\s-]+requests?)"

BLOCKED_PR_INTENT_RE = re.compile(
    rf"\b(?:no|not|never|nunca|sin|evita(?:r)?|impid[ae]|bloquea(?:r)?|don't|do\s+not|dont|hook\s+(?:para|de|que|oa))\b"
    rf"(?:\s+[^\s.,;!?]+){{0,5}}\s+\b{PR_NOUN_PATTERN}\b"
    rf"|\b{PR_NOUN_PATTERN}\s+sin\s+avisar\b"
)
EXPLICIT_PR_RE = re.compile(
    r"\b(?:crea(?:r|me|la|lo|las|los)?|abre(?:me|la|lo|las|los)?|abrir|"
    r"sube(?:me|la|lo|las|los)?|subir|haz(?:me|la|lo|las|los)?|hacer|"
    r"genera(?:r|me|la|lo)?|lanza(?:r|me|la|lo)?|manda(?:r|me|la|lo)?|"
    r"envia(?:r|me|la|lo)?|publica(?:r|me|la|lo)?|monta(?:r|me|la|lo)?|"
    r"create|open|submit|raise|make|send|publish)\b"
    rf"(?:\s+[\w'-]+){{0,4}}\s+\b{PR_NOUN_PATTERN}\b"
)


def normalize_text(text: str) -> str:
    """Lowercase text and strip combining diacritics for accent-insensitive matching."""
    decomposed = unicodedata.normalize("NFD", text.lower())
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def is_pr_creation_command(command_line: str) -> bool:
    """Return True if any shell segment executes a real PR creation command."""
    for segment in SEGMENT_SPLIT_RE.split(command_line):
        if GH_PR_CREATE_RE.search(segment):
            return True
        if CREATE_PR_SCRIPT_RE.search(segment) and not DRY_RUN_OR_HELP_RE.search(segment):
            return True
        if (
            GH_API_RE.search(segment)
            and GH_API_POST_RE.search(segment)
            and GH_API_PULLS_RE.search(segment)
        ):
            return True
    return False


def user_explicitly_requested_pr(last_user_input: str) -> bool:
    """Return True only when the user explicitly asked to create/open a PR without negation."""
    normalized = normalize_text(last_user_input)
    if BLOCKED_PR_INTENT_RE.search(normalized):
        return False
    return bool(EXPLICIT_PR_RE.search(normalized))


def evaluate_hook(payload: dict) -> dict:
    """Evaluate a PreToolUse hook payload and return the decision JSON object."""
    tool_call = payload.get("toolCall") or {}
    args = tool_call.get("args") or {}
    command_line = str(args.get("CommandLine") or "")
    last_user_input = str(payload.get("lastUserInput") or "")

    if is_pr_creation_command(command_line):
        if user_explicitly_requested_pr(last_user_input):
            return {
                "decision": "force_ask",
                "reason": "Confirmación requerida antes de crear el Pull Request.",
            }
        return {
            "decision": "deny",
            "reason": (
                "Bloqueado por hook: no está permitido crear un Pull Request "
                "automáticamente sin que el usuario lo pida."
            ),
        }

    return {"decision": "ask"}


def run_self_tests() -> None:
    """Verify hook decisions across allowed, denied, and force_ask PR scenarios."""
    assert evaluate_hook(
        {
            "toolCall": {"name": "run_command", "args": {"CommandLine": "gradle test"}},
            "lastUserInput": "run tests",
        }
    ) == {"decision": "ask"}

    assert evaluate_hook(
        {
            "toolCall": {
                "name": "run_command",
                "args": {
                    "CommandLine": "python3 .github/skills/github-pr-creator/scripts/create_pr.py --title x --dry-run"
                },
            },
            "lastUserInput": "crea el menu de ssh",
        }
    ) == {"decision": "ask"}

    res_unrequested = evaluate_hook(
        {
            "toolCall": {
                "name": "run_command",
                "args": {
                    "CommandLine": "python3 .github/skills/github-pr-creator/scripts/create_pr.py --title x"
                },
            },
            "lastUserInput": "crea el menu de ssh",
        }
    )
    assert res_unrequested["decision"] == "deny"

    res_hook_prompt = evaluate_hook(
        {
            "toolCall": {
                "name": "run_command",
                "args": {"CommandLine": "gh pr create --title x"},
            },
            "lastUserInput": "y otro hook oa crear pr sin avisar",
        }
    )
    assert res_hook_prompt["decision"] == "deny"

    res_explicit = evaluate_hook(
        {
            "toolCall": {
                "name": "run_command",
                "args": {
                    "CommandLine": "python3 .github/skills/github-pr-creator/scripts/create_pr.py --title x"
                },
            },
            "lastUserInput": "crea el PR para esta rama",
        }
    )
    assert res_explicit["decision"] == "force_ask"

    res_api_post = evaluate_hook(
        {
            "toolCall": {
                "name": "run_command",
                "args": {"CommandLine": "gh api -X POST repos/o/r/pulls -f title=x"},
            },
            "lastUserInput": "haz cambios",
        }
    )
    assert res_api_post["decision"] == "deny"

    for prompt in (
        "crea la mr",
        "ábreme una mr",
        "hazme el merge request",
        "crea la pr y mejora el hook",
        "mejora el hook y crea la pr",
        "no pasa nada, crea la mr",
    ):
        res = evaluate_hook(
            {
                "toolCall": {
                    "name": "run_command",
                    "args": {
                        "CommandLine": "python3 .github/skills/github-pr-creator/scripts/create_pr.py --title x"
                    },
                },
                "lastUserInput": prompt,
            }
        )
        assert res["decision"] == "force_ask", f"Expected force_ask for {prompt!r}, got {res}"

    for prompt in (
        "no crees la mr",
        "revisa la mr 12",
        "falla los actions en las ultimas prs",
        "añade un hook para crear pr",
    ):
        res = evaluate_hook(
            {
                "toolCall": {
                    "name": "run_command",
                    "args": {
                        "CommandLine": "python3 .github/skills/github-pr-creator/scripts/create_pr.py --title x"
                    },
                },
                "lastUserInput": prompt,
            }
        )
        assert res["decision"] == "deny", f"Expected deny for {prompt!r}, got {res}"


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        run_self_tests()
        print("prevent_auto_pr self-tests passed.")
        return 0

    raw = sys.stdin.read().strip()
    payload = json.loads(raw) if raw else {}
    print(json.dumps(evaluate_hook(payload)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
