#!/usr/bin/env python3
"""Open or update the single RequestHandoff compatibility issue.

Reads verifier and test reports from the workspace. The GitHub token stays in
the environment for `gh` and is never written to the issue or to stdout.
"""

from __future__ import annotations

import os
import pathlib
import re
import subprocess
import sys

TITLE = "RequestHandoff IDE compatibility needs attention"
LABEL = "needs-attention"
ASSIGNEE = "WillCraig"
BODY_LIMIT = 50000
REPO = os.environ["GITHUB_REPOSITORY"]
RUN_URL = (
    f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}"
    f"/{REPO}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
)
REASON = os.environ.get("NEEDS_ATTENTION_REASON", "A compatibility check failed.")

REPORT_ROOTS = [
    pathlib.Path("releases.txt"),
    pathlib.Path("build/reports/pluginVerifier"),
    pathlib.Path("build/reports/tests"),
    pathlib.Path("core/build/reports/tests"),
    pathlib.Path("out/ide-tests"),
]


def gh(*args: str, input_text: str | None = None) -> str:
    completed = subprocess.run(
        ["gh", *args],
        input=input_text,
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        message = redact(completed.stderr.strip() or completed.stdout.strip() or "gh failed")
        raise SystemExit(message)
    return completed.stdout


def redact(text: str) -> str:
    return re.sub(r"(ghp_|gho_|github_pat_|glpat-)[A-Za-z0-9_]+", "[redacted]", text)


def excerpt() -> str:
    chunks: list[str] = []
    for root in REPORT_ROOTS:
        if not root.exists():
            continue
        files = [root] if root.is_file() else sorted(path for path in root.rglob("*") if path.is_file())
        for path in files:
            if path.suffix.lower() not in {".txt", ".md", ".log"} and path.name != "releases.txt":
                continue
            if path.stat().st_size > 1_000_000:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if not text.strip():
                continue
            chunks.append(f"### {path.as_posix()}\n\n```\n{text[-8000:]}\n```")
            if sum(len(chunk) for chunk in chunks) > BODY_LIMIT:
                break
        if sum(len(chunk) for chunk in chunks) > BODY_LIMIT:
            break
    if not chunks:
        return "No report files were found in the workspace. The workflow artifacts on the run page have the logs."
    body = "\n\n".join(chunks)
    if len(body) > 40000:
        body = body[-40000:]
        body = "…(truncated)\n\n" + body
    return body


def ensure_label() -> None:
    listed = gh(
        "label",
        "list",
        "--repo",
        REPO,
        "--search",
        LABEL,
        "--json",
        "name",
        "--jq",
        f'.[] | select(.name == "{LABEL}") | .name',
    ).strip()
    if listed == LABEL:
        return
    gh(
        "label",
        "create",
        LABEL,
        "--repo",
        REPO,
        "--color",
        "D93F0B",
        "--description",
        "Verifier or release-watch failure",
    )


def find_open_issue() -> str | None:
    numbers = gh(
        "issue",
        "list",
        "--repo",
        REPO,
        "--state",
        "open",
        "--label",
        LABEL,
        "--limit",
        "50",
        "--json",
        "number,title",
        "--jq",
        f'.[] | select(.title == "{TITLE}") | .number',
    ).split()
    return numbers[0] if numbers else None


def main() -> None:
    ensure_label()
    body = f"""{REASON}

Workflow run: {RUN_URL}

Full verifier reports, test reports, and IDE logs are uploaded as artifacts on that run.

{excerpt()}
"""
    if len(body) > BODY_LIMIT:
        body = body[: BODY_LIMIT - 20] + "\n…(truncated)\n"
    number = find_open_issue()
    if number is None:
        created = gh(
            "issue",
            "create",
            "--repo",
            REPO,
            "--title",
            TITLE,
            "--body-file",
            "-",
            "--label",
            LABEL,
            "--assignee",
            ASSIGNEE,
            input_text=body,
        )
        print(created.strip())
        return
    gh(
        "issue",
        "edit",
        number,
        "--repo",
        REPO,
        "--body-file",
        "-",
        "--add-label",
        LABEL,
        "--add-assignee",
        ASSIGNEE,
        input_text=body,
    )
    gh(
        "issue",
        "comment",
        number,
        "--repo",
        REPO,
        "--body",
        f"Updated from {RUN_URL}. {REASON}",
    )
    print(f"updated #{number}")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as error:
        print(redact(str(error)), file=sys.stderr)
        raise SystemExit(1) from None
