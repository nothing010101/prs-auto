"""Buat GitHub Release v1.0.0 untuk prs-auto dan upload APK debug sebagai
asset, memakai kredensial connector custom.github.

Usage:
  python3 release-apk.py [apk-path]

Output JSON: release html_url + asset browser_download_url.
"""
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, os.path.expanduser("~/workspace/custom-connectors/custom.github/bin"))
from github import call  # noqa: E402  (Git Data API helper)
from dynamic_credentials import add_surrogate_to_request  # noqa: E402

OWNER = "nothing010101"
REPO = "prs-auto"
TAG = "v1.0.0"
APK_NAME = "prs-auto-v1.0.0-debug.apk"
DEFAULT_APK = os.path.expanduser(
    "~/workspace/prs-auto/app/build/outputs/apk/debug/app-debug.apk"
)

RELEASE_BODY = """PRS Auto v1.0.0 — otomasi tugas Follow Instagram di pr.social (tablet Android, tanpa root).

**Install:** download APK di bawah, buka di tablet, izinkan "Install aplikasi yang tidak dikenal", install. Lalu di aplikasi ikuti checklist: aktifkan Aksesibilitas, atur Baterai ke Tanpa batasan, izinkan Autostart (wajib di Xiaomi/HyperOS).

**Jalankan:** login dulu ke pr.social + Instagram di Chrome, buka PRS Auto, tekan MULAI, setujui dialog screenshot.

**Status: BELUM DITES di tablet asli.** Alur dibuat dari rekaman layar user. Lihat README di repo untuk batasan yang diketahui (risiko action block IG, hanya Chrome, pencocokan item pertama di picker).
"""


def upload_asset(upload_url: str, apk_path: str) -> dict:
    url = upload_url.split("{")[0] + "?name=" + APK_NAME
    with open(apk_path, "rb") as fh:
        data = fh.read()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    req.add_header("Content-Length", str(len(data)))
    add_surrogate_to_request(req, "custom.github", allowed_hosts=["uploads.github.com"])
    try:
        with urllib.request.build_opener().open(req, timeout=120) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(body)
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", "replace")}


def main():
    apk_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    if not os.path.exists(apk_path):
        print(f"APK tidak ketemu: {apk_path}")
        return 1
    print(f"APK: {apk_path} ({os.path.getsize(apk_path)} byte)")

    status, rel = call("POST", f"/repos/{OWNER}/{REPO}/releases", {
        "tag_name": TAG,
        "name": "PRS Auto v1.0.0",
        "body": RELEASE_BODY,
        "draft": False,
        "prerelease": False,
    })
    if status == 422:
        # Release/tag sudah ada — ambil release yang ada berdasarkan tag.
        status, rel = call("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{TAG}")
    assert status in (200, 201), (status, rel)
    print("release:", rel.get("html_url"), "id:", rel.get("id"))
    upload_url = rel.get("upload_url", "")
    assert upload_url, rel
    status, asset = upload_asset(upload_url, apk_path)
    assert status == 201, (status, asset)
    print(json.dumps({
        "release_url": rel.get("html_url"),
        "asset_name": asset.get("name"),
        "download_url": asset.get("browser_download_url"),
        "size": asset.get("size"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
