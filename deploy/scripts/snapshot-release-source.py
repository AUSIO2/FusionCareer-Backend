#!/usr/bin/env python3
"""Freeze release inputs, including local edits, without local secrets or data."""

import gzip
import hashlib
import io
import json
import re
import stat
import subprocess
import sys
import tarfile
import time
from pathlib import Path, PurePosixPath

SOURCE_ROOTS = {"fusioncareer-agent", "fusioncareer-api", "fusioncareer-biz", "deploy", "docs", ".mvn"}
ROOT_FILES = {"pom.xml", "mvnw", "mvnw.cmd", "README.md", "Dockerfile", ".dockerignore", ".gitignore", ".gitattributes"}
EXCLUDED_PARTS = {"outputs", ".git", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", "target", "node_modules"}
SECRET_CONTENT = re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----|\bsk-[A-Za-z0-9_-]{24,}\b")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def included(name):
    parts = PurePosixPath(name).parts
    if not parts or ".." in parts or parts[0] == "/":
        return False
    if parts[0] not in SOURCE_ROOTS and name not in ROOT_FILES:
        return False
    # The app/runtime package is executable source. Only the generated Agent
    # state directory at this exact location is excluded.
    if parts[0] == "runtime" or parts[:2] == ("fusioncareer-agent", "runtime"):
        return False
    if any(part in EXCLUDED_PARTS for part in parts):
        return False
    basename = parts[-1].lower()
    if basename.startswith("._") or basename == ".ds_store":
        return False
    if (basename.startswith(".env") or basename.endswith(".env")) and basename != ".env.example":
        return False
    if basename.endswith((".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".pyc")):
        return False
    return True


def source_names(root):
    return sorted({name.decode("utf-8") for name in git(root, "ls-files", "-c", "-o", "--exclude-standard", "-z").split(b"\0") if name})


def file_state(path):
    try:
        value = path.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(value.st_mode):
        raise ValueError(f"release input must be a regular file: {path.name}")
    return (value.st_dev, value.st_ino, value.st_size, value.st_mode, value.st_mtime_ns, value.st_ctime_ns)


def read_stable(path, expected_state, name):
    if file_state(path) != expected_state:
        raise RuntimeError(f"source changed during release capture: {name}")
    data = path.read_bytes()
    if file_state(path) != expected_state:
        raise RuntimeError(f"source changed during release capture: {name}")
    return data


def verify_capture(root, names, states, entries, base_commit, tracked_status):
    current_names = [name for name in source_names(root) if included(name)]
    if current_names != [name for name in names if included(name)]:
        raise RuntimeError("source file list changed during release capture; retry after editing stops")
    if git(root, "rev-parse", "HEAD").decode().strip() != base_commit:
        raise RuntimeError("HEAD changed during release capture; retry after editing stops")
    if git(root, "diff", "--name-status", "HEAD", "-z") != tracked_status:
        raise RuntimeError("tracked source status changed during release capture; retry after editing stops")
    digests = {entry["path"]: entry["sha256"] for entry in entries}
    for name, expected_state in states.items():
        path = root / name
        if file_state(path) != expected_state:
            raise RuntimeError(f"source changed during release capture: {name}")
        if expected_state is not None and hashlib.sha256(read_stable(path, expected_state, name)).hexdigest() != digests[name]:
            raise RuntimeError(f"source content changed during release capture: {name}")


def verify_python_sources(root, names, source, entries):
    """Fail closed if any original app Python source was filtered or omitted."""
    required = sorted(name for name in names
                      if name.startswith("fusioncareer-agent/app/") and name.endswith(".py")
                      and (root / name).is_file())
    hashes = {entry["path"]: entry["sha256"] for entry in entries}
    for name in required:
        if not included(name) or name not in hashes:
            raise RuntimeError(f"required Python source was excluded from release: {name}")
        captured = source / name
        if not captured.is_file() or captured.is_symlink():
            raise RuntimeError(f"required Python source missing from release: {name}")
        if hashlib.sha256(captured.read_bytes()).hexdigest() != hashes[name]:
            raise RuntimeError(f"required Python source hash mismatch: {name}")
    if not required:
        raise RuntimeError("release contains no original Agent Python sources")
    return required


def main():
    root, output = (Path(arg).resolve() for arg in sys.argv[1:])
    if output == root or root in output.parents:
        raise ValueError("release output must be outside the source repository")
    source = output / "source"
    source.mkdir(mode=0o700)
    capture_started = time.time_ns()
    base_commit = git(root, "rev-parse", "HEAD").decode().strip()
    tracked_status = git(root, "diff", "--name-status", "HEAD", "-z")
    names = source_names(root)
    states = {name: file_state(root / name) for name in names if included(name)}
    for name, value in states.items():
        if value is not None and max(value[-2:]) >= capture_started:
            raise RuntimeError(f"source edited during release inventory: {name}; retry after editing stops")
    entries = []
    excluded = []
    checksums = []
    with (output / "source-snapshot.tgz").open("xb") as archive:
        with gzip.GzipFile(filename="", mode="wb", fileobj=archive, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as bundle:
                for name in names:
                    path = root / name
                    if not included(name):
                        excluded.append(name)
                        continue
                    if states[name] is None:  # Deleted tracked files stay absent in the frozen tree.
                        continue
                    data = read_stable(path, states[name], name)
                    if SECRET_CONTENT.search(data):
                        raise ValueError(f"possible embedded credential in release input: {name}")
                    mode = 0o755 if states[name][3] & 0o111 else 0o644
                    destination = source / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(data)
                    destination.chmod(mode)
                    digest = hashlib.sha256(data).hexdigest()
                    entries.append({"path": name, "sha256": digest, "mode": oct(mode)})
                    checksums.append(f"{digest}  ./{name}\n")
                    member = tarfile.TarInfo(name)
                    member.size = len(data)
                    member.mode = mode
                    bundle.addfile(member, io.BytesIO(data))
    required_python = verify_python_sources(root, names, source, entries)
    provenance = json.loads((source / "fusioncareer-agent/app/vendor/fusioncareer_algorithm/PROVENANCE.json").read_text())
    algorithm_commit = provenance["commit"]
    if not re.fullmatch(r"[0-9a-f]{40}", algorithm_commit):
        raise ValueError("invalid algorithm commit in PROVENANCE.json")
    vendor = source / "fusioncareer-agent/app/vendor/fusioncareer_algorithm"
    for name, expected in provenance["sha256"].items():
        if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
            raise ValueError("invalid path in algorithm provenance")
        if hashlib.sha256((vendor / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"upstream provenance hash mismatch: {name}")
    changed = {name.decode("utf-8") for name in git(root, "diff", "--name-only", "HEAD", "-z").split(b"\0") if name}
    untracked = {name.decode("utf-8") for name in git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0") if name}
    dirty = sorted(name for name in changed | untracked if included(name))
    verify_capture(root, names, states, entries, base_commit, tracked_status)
    manifest = {
        "backendBaseCommit": base_commit,
        "algorithmCommit": algorithm_commit,
        "captureConsistency": "verified",
        "sourceDirty": bool(dirty),
        "dirtySourcePaths": dirty,
        "excludedPaths": excluded,
        "requiredPythonPaths": required_python,
        "files": entries,
    }
    (output / "SOURCE-SHA256SUMS").write_text("".join(checksums), encoding="utf-8")
    (output / "SOURCE-MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Frozen {len(entries)} release inputs; dirty={bool(dirty)}; algorithm={algorithm_commit}")


if __name__ == "__main__":
    main()
