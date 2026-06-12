"""
Sandbox for LLM-generated exercise solvers.

LLM-generated solver code is UNTRUSTED. Containment is two-layer:
1. AST whitelist — only arithmetic/control-flow/comprehensions and calls into
   math.* / itertools.* / safe builtins. No imports, no attribute access except
   on math/itertools, no exec/eval/open, no dunders.
2. Subprocess execution with a wall-clock timeout and (on Linux) an address-space
   rlimit. The parent never exec()s solver code in-process.

This blocks accidents and casual escapes, not a determined attacker — acceptable
for a single-user app where the "attacker" is a small model being dumb.
Do not loosen the whitelist before reading FLASHCARDS_ARCH Technology Notes.
"""
import ast
import json
import subprocess
import sys

TIMEOUT_S = 2
MEM_MB = 128

ALLOWED_MODULES = {"math", "itertools"}

ALLOWED_BUILTINS = {
    "abs", "min", "max", "sum", "len", "range", "round", "int", "float",
    "str", "bool", "list", "tuple", "dict", "set", "sorted", "reversed",
    "enumerate", "zip", "map", "filter", "pow", "divmod", "all", "any",
}

# Statically rejected names — also absent from the runtime builtins, but the
# AST check should fail fast with a clear error instead of a child NameError.
DENIED_NAMES = {
    "eval", "exec", "open", "compile", "__import__", "globals", "locals",
    "vars", "getattr", "setattr", "delattr", "input", "breakpoint",
    "exit", "quit", "type", "super", "object", "memoryview",
}

_ALLOWED_NODES = (
    ast.Module, ast.FunctionDef, ast.arguments, ast.arg, ast.Return,
    ast.Assign, ast.AugAssign, ast.AnnAssign, ast.Expr,
    ast.Name, ast.Load, ast.Store, ast.Constant,
    ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare, ast.IfExp,
    ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow,
    ast.LShift, ast.RShift, ast.BitOr, ast.BitXor, ast.BitAnd,
    ast.USub, ast.UAdd, ast.Not, ast.Invert,
    ast.And, ast.Or,
    ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn,
    ast.Call, ast.keyword,
    ast.If, ast.For, ast.While, ast.Break, ast.Continue, ast.Pass,
    ast.List, ast.Tuple, ast.Dict, ast.Set,
    ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp, ast.comprehension,
    ast.Subscript, ast.Slice, ast.Index if hasattr(ast, "Index") else ast.Slice,
    ast.Attribute,
)


class SandboxError(Exception):
    pass


def check_solver(code: str) -> None:
    """Raises SandboxError if the code is not whitelisted. Must define solve()."""
    try:
        tree = ast.parse(code)
    except SyntaxError as e:
        raise SandboxError(f"syntax error: {e}") from e

    has_solve = False
    for node in ast.walk(tree):
        if not isinstance(node, _ALLOWED_NODES):
            raise SandboxError(f"disallowed construct: {type(node).__name__}")
        if isinstance(node, ast.FunctionDef):
            if node.name == "solve":
                has_solve = True
            if node.decorator_list:
                raise SandboxError("decorators not allowed")
        if isinstance(node, ast.Attribute):
            # attribute access only on math.* / itertools.*
            if not (isinstance(node.value, ast.Name) and node.value.id in ALLOWED_MODULES):
                raise SandboxError("attribute access only allowed on math/itertools")
            if node.attr.startswith("_"):
                raise SandboxError("dunder/private attribute access not allowed")
        if isinstance(node, ast.Name):
            if node.id.startswith("__"):
                raise SandboxError(f"dunder name not allowed: {node.id}")
            if node.id in DENIED_NAMES:
                raise SandboxError(f"denied name: {node.id}")

    if not has_solve:
        raise SandboxError("code must define solve()")


# Child-process harness. Reads {"code", "params"} JSON on stdin, prints result JSON.
_HARNESS = r"""
import builtins, json, sys, math, itertools
payload = json.loads(sys.stdin.read())
allowed = {name: getattr(builtins, name) for name in %s}
g = {"__builtins__": allowed, "math": math, "itertools": itertools}
try:
    exec(payload["code"], g)
    result = g["solve"](**payload["params"])
    print(json.dumps({"ok": True, "result": result}))
except Exception as e:
    print(json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"}))
""" % sorted(ALLOWED_BUILTINS)


def run_solver(code: str, params: dict):
    """check_solver() must have passed already. Runs solve(**params) in a subprocess."""
    preexec = None
    if sys.platform != "win32":
        import resource

        def preexec():  # pragma: no cover - exercised only inside the container
            resource.setrlimit(resource.RLIMIT_AS, (MEM_MB * 1024 * 1024,) * 2)

    try:
        proc = subprocess.run(
            [sys.executable, "-c", _HARNESS],
            input=json.dumps({"code": code, "params": params}),
            capture_output=True, text=True, timeout=TIMEOUT_S, preexec_fn=preexec,
        )
    except subprocess.TimeoutExpired:
        raise SandboxError(f"solver timed out after {TIMEOUT_S}s")

    try:
        out = json.loads(proc.stdout)
    except json.JSONDecodeError:
        raise SandboxError(f"solver crashed: {proc.stderr[:300]}")
    if not out.get("ok"):
        raise SandboxError(f"solver raised: {out.get('error')}")
    return out["result"]
