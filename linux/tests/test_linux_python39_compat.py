import ast
import unittest
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parents[1] / "raofflineproxy"


def _has_future_annotations(tree: ast.Module) -> bool:
    return any(
        isinstance(node, ast.ImportFrom)
        and node.module == "__future__"
        and any(alias.name == "annotations" for alias in node.names)
        for node in tree.body
    )


def _annotation_nodes(tree: ast.Module):
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            args = node.args
            candidates = [
                node.returns,
                *(
                    arg.annotation
                    for arg in (
                        *args.posonlyargs,
                        *args.args,
                        *args.kwonlyargs,
                        args.vararg,
                        args.kwarg,
                    )
                    if arg is not None
                ),
            ]
        elif isinstance(node, ast.AnnAssign):
            candidates = [node.annotation]
        else:
            continue
        for candidate in candidates:
            if candidate is not None:
                yield candidate


def _union_lines(annotation: ast.AST):
    for node in ast.walk(annotation):
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
            yield node.lineno


class LinuxPython39CompatTests(unittest.TestCase):
    """The Onion bundle ships CPython 3.9, so the package must stay 3.9-safe.

    CI runs the suite on a modern interpreter where `X | None` annotations and
    `match` statements are valid, which is exactly how a 3.10-only construct can
    reach a device and crash at import time.
    """

    def _sources(self):
        for path in sorted(PACKAGE_DIR.rglob("*.py")):
            yield path, ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

    def test_pep604_annotations_require_future_import(self) -> None:
        offenders = []
        for path, tree in self._sources():
            if _has_future_annotations(tree):
                continue
            for annotation in _annotation_nodes(tree):
                offenders.extend(
                    f"{path.name}:{line}" for line in _union_lines(annotation)
                )

        self.assertEqual(
            offenders,
            [],
            "PEP 604 unions are evaluated at runtime on Python 3.9; add "
            "'from __future__ import annotations' to these files: "
            f"{offenders}",
        )

    def test_no_match_statements(self) -> None:
        offenders = [
            f"{path.name}:{node.lineno}"
            for path, tree in self._sources()
            for node in ast.walk(tree)
            if isinstance(node, ast.Match)
        ]

        self.assertEqual(
            offenders, [], f"match statements require Python 3.10: {offenders}"
        )
