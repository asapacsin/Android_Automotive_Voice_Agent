"""Path globbing, path safety and git helpers (no shell, arg lists only)."""
import hashlib
import re
import subprocess
from pathlib import Path, PurePosixPath, PureWindowsPath

EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
BINARY_EXT = {
    ".jar", ".aar", ".so", ".a", ".o", ".dll", ".dylib", ".exe", ".bin", ".apk", ".aab", ".dex", ".class",
    ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".png", ".jpg", ".jpeg", ".gif", ".webp",
    ".ico", ".pdf", ".mp3", ".wav", ".ogg", ".mp4", ".onnx", ".tflite", ".pyc", ".jks", ".keystore",
    ".p12", ".pfx",
}
SECRET_GLOBS = [
    ".env", ".env.*", "*.env", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore", "id_rsa*",
    "id_ed25519*", "credentials*", "*credentials.json", "*secret*", "*.secrets", ".netrc", ".npmrc",
    ".pypirc", "local.properties",
]
_GLOB_CACHE = {}


def glob_regex(pat):
    if pat in _GLOB_CACHE:
        return _GLOB_CACHE[pat]
    p = pat.replace("\\", "/").lstrip("/")
    if p.endswith("/"):
        p += "**"
    out, i = [], 0
    while i < len(p):
        c = p[i]
        if p.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif p.startswith("**", i):
            out.append(".*")
            i += 2
        elif c == "*":
            out.append("[^/]*")
            i += 1
        elif c == "?":
            out.append("[^/]")
            i += 1
        elif c == "[" and "]" in p[i + 1:]:
            j = p.index("]", i + 1)
            out.append("[" + p[i + 1:j].replace("\\", "\\\\") + "]")
            i = j + 1
        else:
            out.append(re.escape(c))
            i += 1
    rx = re.compile("^" + "".join(out) + "$")
    _GLOB_CACHE[pat] = rx
    return rx


def match_glob(path, pat):
    """gitignore-like: a pattern without '/' matches the basename anywhere."""
    path = path.replace("\\", "/")
    rx = glob_regex(pat)
    if rx.match(path):
        return True
    if "/" not in pat.strip("/"):
        return bool(rx.match(path.rsplit("/", 1)[-1]))
    return False


def match_any(path, pats):
    return any(match_glob(path, g) for g in pats or ())


def normalize_rel(path):
    """Return a clean repo-relative posix path or raise ValueError."""
    if not isinstance(path, str) or not path.strip():
        raise ValueError("empty path")
    if PureWindowsPath(path).is_absolute() or PureWindowsPath(path).drive or PurePosixPath(path).is_absolute():
        raise ValueError("absolute path")
    parts = [x for x in path.replace("\\", "/").split("/") if x not in ("", ".")]
    if not parts or ".." in parts:
        raise ValueError("path escapes the project")
    return "/".join(parts)


def inside(root, rel):
    try:
        (Path(root) / rel).resolve().relative_to(Path(root).resolve())
        return True
    except ValueError:
        return False


def is_binary(path):
    p = Path(path)
    if p.suffix.lower() in BINARY_EXT:
        return True
    try:
        with open(p, "rb") as fh:
            return b"\0" in fh.read(8192)
    except OSError:
        return False


def git(root, *args, check=True, text=True):
    r = subprocess.run(["git", "-C", str(root)] + list(args), capture_output=True, text=text)
    if check and r.returncode != 0:
        raise RuntimeError("git %s failed: %s" % (args[0], (r.stderr or "").strip()[:300]))
    return r


def is_git_repo(root):
    return git(root, "rev-parse", "--is-inside-work-tree", check=False).returncode == 0


def is_ignored(root, rel):
    if not is_git_repo(root):
        return False
    return git(root, "check-ignore", "-q", "--", rel, check=False).returncode == 0


def head(root):
    r = git(root, "rev-parse", "--verify", "-q", "HEAD", check=False)
    return r.stdout.strip() if r.returncode == 0 else EMPTY_TREE


def changed_paths(root, base, paths=None):
    spec = ["--"] + (list(paths) if paths else [])
    tracked = [p for p in git(root, "diff", "--name-only", "-z", base, *spec).stdout.split("\0") if p]
    untracked = [p for p in git(root, "ls-files", "--others", "--exclude-standard", "-z", *spec).stdout.split("\0") if p]
    return sorted(set(tracked) | set(untracked)), set(untracked)


def diff_text(root, base, paths):
    """Text diff of `paths` vs base, including untracked files as additions."""
    if not paths:
        return ""
    _, untracked = changed_paths(root, base, paths)
    out = [git(root, "diff", "--no-color", "--no-ext-diff", base, "--", *paths).stdout]
    for rel in sorted(untracked):
        f = Path(root) / rel
        if is_binary(f):
            out.append("diff --git a/%s b/%s\nnew binary file sha256 %s\n" % (rel, rel, sha256_file(f)))
            continue
        body = f.read_text(encoding="utf-8", errors="replace")
        lines = "".join("+" + ln + ("\n" if not ln.endswith("\n") else "") for ln in body.splitlines(True))
        out.append("diff --git a/%s b/%s\nnew file mode 100644\n--- /dev/null\n+++ b/%s\n%s" % (rel, rel, rel, lines))
    return "".join(out)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
