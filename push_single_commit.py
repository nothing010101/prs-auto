"""Push PRS Auto ke GitHub sebagai SATU root commit via Git Data API
(blobs -> tree -> commit -> ref), memakai kredensial connector
custom.github. JANGAN commit berkali-kali (akun muse1 kena suspend
karena commit ribuan kali).

Usage:
  python3 push_single_commit.py [--include-apk]
APK hanya dimasukkan kalau diminta (fallback kalau upload release asset gagal).
"""
import base64
import json
import os
import sys

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, os.path.expanduser("~/workspace/custom-connectors/custom.github/bin"))
from github import call  # noqa: E402  (reuses add_surrogate_to_request)

OWNER = "nothing010101"
REPO = "prs-auto"
WORKDIR = os.path.expanduser("~/workspace/prs-auto")
MESSAGE = "PRS Auto v1.0.0 — otomasi tugas Follow Instagram pr.social (Accessibility + MediaProjection)"
APK_PATH = os.path.expanduser(
    "~/workspace/prs-auto/app/build/outputs/apk/debug/app-debug.apk"
)

SKIP_DIRS = {".git", ".gradle", "build", "captures", ".idea", ".cxx",
             ".externalNativeBuild", "build-manual"}
SKIP_FILES = {"local.properties"}


def collect_files(include_apk: bool):
    files = []
    for root, dirs, names in os.walk(WORKDIR):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in sorted(names):
            if name in SKIP_FILES:
                continue
            if name.endswith(".apk") and not include_apk:
                continue
            if name.endswith(".pyc"):
                continue
            full = os.path.join(root, name)
            rel = os.path.relpath(full, WORKDIR)
            files.append(rel)
    if include_apk:
        files.append(os.path.relpath(APK_PATH, WORKDIR))
    return sorted(files)


def main():
    include_apk = "--include-apk" in sys.argv
    files = collect_files(include_apk)
    print(f"{len(files)} file(s) to push (include_apk={include_apk})")
    tree_entries = []
    for path in files:
        full = os.path.join(WORKDIR, path)
        with open(full, "rb") as fh:
            raw = fh.read()
        try:
            content = raw.decode("utf-8")
            encoding = "utf-8"
        except UnicodeDecodeError:
            content = base64.b64encode(raw).decode("ascii")
            encoding = "base64"
        status, blob = call("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                            {"content": content, "encoding": encoding})
        assert status == 201, (path, status, blob)
        tree_entries.append({"path": path, "mode": "100644",
                             "type": "blob", "sha": blob["sha"]})
        print(f"blob {path}: {blob['sha'][:12]} ({encoding})")
    status, tree = call("POST", f"/repos/{OWNER}/{REPO}/git/trees",
                        {"tree": tree_entries})
    assert status == 201, (status, tree)
    print("tree:", tree["sha"])
    status, commit = call("POST", f"/repos/{OWNER}/{REPO}/git/commits",
                          {"message": MESSAGE, "tree": tree["sha"], "parents": []})
    assert status == 201, (status, commit)
    print("commit:", commit["sha"])
    status, ref = call("POST", f"/repos/{OWNER}/{REPO}/git/refs",
                       {"ref": "refs/heads/main", "sha": commit["sha"]})
    if status == 422:
        status, ref = call("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/main",
                           {"sha": commit["sha"], "force": True})
    assert status in (200, 201), (status, ref)
    print("ref refs/heads/main ->", commit["sha"])
    print(json.dumps({"repo": f"{OWNER}/{REPO}", "commit": commit["sha"],
                      "url": f"https://github.com/{OWNER}/{REPO}"}))


if __name__ == "__main__":
    main()
