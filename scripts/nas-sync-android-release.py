#!/usr/bin/env python3
"""Safely publish a 52nm Android GitHub Release to NAS Dufs."""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

REPOSITORY = "abxian/shenxianyun-android-52nm"
DEFAULT_DUFS_ROOT = Path("/vol1/dufs/data/52nm")
DEFAULT_WORK_ROOT = Path("/vol1/1000/docker-projects/shenxianyun-release-sync/work-android-52nm")
DEFAULT_BACKUP_ROOT = Path("/vol1/1000/docker-projects/backups")
ALIASES = {"wuaiyun.apk": "arm64-v8a", "wuaiyunall.apk": "universal"}
STATE_FILE = ".android-52nm-release-state.json"
API_ROOT = "https://api.github.com"
TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


class SyncError(RuntimeError):
    pass


def version_tuple(tag: str) -> tuple[int, int, int]:
    match = TAG_RE.fullmatch(tag)
    if not match:
        raise SyncError(f"not a stable Android tag: {tag!r}")
    return tuple(map(int, match.groups()))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_json(url: str, timeout: int) -> dict:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "shenxianyun-android-sync"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raise SyncError(f"GitHub API {error.code}: {error.read(300).decode(errors='replace')}") from error


def required_names(tag: str) -> set[str]:
    version = tag[1:]
    return {
        f"cmfa-{version}-meta-arm64-v8a-release.apk",
        f"cmfa-{version}-meta-armeabi-v7a-release.apk",
        f"cmfa-{version}-meta-universal-release.apk",
        f"cmfa-{version}-meta-x86-release.apk",
        f"cmfa-{version}-meta-x86_64-release.apk",
        "output-metadata.json",
        *ALIASES.keys(),
    }


def validate_release(release: dict, tag: str) -> dict[str, dict]:
    if release.get("tag_name") != tag or release.get("draft") or release.get("prerelease"):
        raise SyncError("release must match the tag and be neither draft nor prerelease")
    assets = {asset["name"]: asset for asset in release.get("assets", [])}
    missing = required_names(tag) - assets.keys()
    if missing:
        raise SyncError(f"release is missing required assets: {sorted(missing)}")
    for name in required_names(tag):
        asset = assets[name]
        if int(asset.get("size", 0)) <= 0:
            raise SyncError(f"empty release asset: {name}")
        digest = str(asset.get("digest") or "")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise SyncError(f"asset has no trusted SHA-256 digest: {name}")
    return {name: assets[name] for name in required_names(tag)}


def download(asset: dict, target: Path, mirror: str, timeout: int, retries: int) -> None:
    url = asset["browser_download_url"]
    urls = [f"{mirror.rstrip('/')}/{url}", url] if mirror else [url]
    last_error: Exception | None = None
    for _ in range(retries):
        for candidate in urls:
            part = target.with_suffix(target.suffix + ".part")
            part.unlink(missing_ok=True)
            try:
                request = urllib.request.Request(candidate, headers={"User-Agent": "shenxianyun-android-sync"})
                with urllib.request.urlopen(request, timeout=timeout) as response, part.open("wb") as output:
                    shutil.copyfileobj(response, output, 1024 * 1024)
                if part.stat().st_size != int(asset["size"]):
                    raise SyncError(f"size mismatch for {asset['name']}")
                expected = asset["digest"].split(":", 1)[1]
                if sha256(part) != expected:
                    raise SyncError(f"SHA-256 mismatch for {asset['name']}")
                os.replace(part, target)
                return
            except Exception as error:  # retry mirrors and transient failures
                last_error = error
                part.unlink(missing_ok=True)
    raise SyncError(f"failed to download {asset['name']}: {last_error}")


def validate_metadata(path: Path, tag: str) -> tuple[str, int]:
    metadata = json.loads(path.read_text())
    elements = metadata.get("elements") or []
    if not elements:
        raise SyncError("output-metadata.json has no elements")
    versions = {str(item.get("versionName")) for item in elements}
    codes = {int(item.get("versionCode", 0)) for item in elements}
    if versions != {tag[1:]} or len(codes) != 1 or min(codes) <= 0:
        raise SyncError(f"metadata version mismatch: versions={versions}, codes={codes}")
    return versions.pop(), codes.pop()


def current_version(root: Path) -> tuple[int, int, int] | None:
    state = root / STATE_FILE
    if state.exists():
        return version_tuple(json.loads(state.read_text())["tag"])
    metadata = root / "output-metadata.json"
    if metadata.exists():
        data = json.loads(metadata.read_text())
        elements = data.get("elements") or []
        if elements:
            return version_tuple("v" + str(elements[0]["versionName"]))
    return None


def managed_existing(root: Path) -> list[Path]:
    files = [root / name for name in (*ALIASES.keys(), "output-metadata.json", STATE_FILE)]
    files.extend(root.glob("cmfa-*-meta-*-release.apk"))
    return sorted({path for path in files if path.exists()})


def atomic_copy(source: Path, destination: Path) -> None:
    temporary = destination.with_name(f".{destination.name}.new")
    shutil.copy2(source, temporary)
    with temporary.open("rb") as stream:
        os.fsync(stream.fileno())
    os.replace(temporary, destination)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--allow-downgrade", action="store_true")
    parser.add_argument("--dufs-root", type=Path, default=DEFAULT_DUFS_ROOT)
    parser.add_argument("--work-root", type=Path, default=DEFAULT_WORK_ROOT)
    parser.add_argument("--backup-root", type=Path, default=DEFAULT_BACKUP_ROOT)
    parser.add_argument("--download-mirror", default="https://gh-proxy.com")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--retries", type=int, default=3)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    requested = version_tuple(args.tag)
    args.work_root.mkdir(parents=True, exist_ok=True)
    with (args.work_root / "sync.lock").open("w") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        release = fetch_json(f"{API_ROOT}/repos/{REPOSITORY}/releases/tags/{args.tag}", args.timeout)
        assets = validate_release(release, args.tag)
        installed = current_version(args.dufs_root)
        if installed and requested <= installed and not args.allow_downgrade:
            raise SyncError(f"refusing non-upgrade {args.tag}; installed is v{'.'.join(map(str, installed))}")
        with tempfile.TemporaryDirectory(prefix="android-sync-", dir=args.work_root) as temporary:
            stage = Path(temporary)
            metadata_asset = assets["output-metadata.json"]
            download(metadata_asset, stage / "output-metadata.json", args.download_mirror, args.timeout, args.retries)
            version, version_code = validate_metadata(stage / "output-metadata.json", args.tag)
            if args.dry_run:
                print(f"DRY-RUN OK: {REPOSITORY} {args.tag} ({len(assets)} assets, versionCode={version_code}) -> {args.dufs_root}")
                return 0
            args.dufs_root.mkdir(parents=True, exist_ok=True)
            for name, asset in assets.items():
                if name != "output-metadata.json":
                    download(asset, stage / name, args.download_mirror, args.timeout, args.retries)
            arm64 = assets[f"cmfa-{version}-meta-arm64-v8a-release.apk"]["digest"]
            universal = assets[f"cmfa-{version}-meta-universal-release.apk"]["digest"]
            if assets["wuaiyun.apk"]["digest"] != arm64 or assets["wuaiyunall.apk"]["digest"] != universal:
                raise SyncError("fixed APK aliases do not match arm64/universal assets")
            timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
            backup = args.backup_root / f"52nm-android-sync-{args.tag}-{timestamp}"
            backup.mkdir(parents=True, mode=0o700)
            previous = managed_existing(args.dufs_root)
            for path in previous:
                shutil.copy2(path, backup / path.name)
            published: list[Path] = []
            try:
                for name in sorted(assets):
                    destination = args.dufs_root / name
                    atomic_copy(stage / name, destination)
                    published.append(destination)
                state = {"tag": args.tag, "version": version, "versionCode": version_code, "repository": REPOSITORY}
                state_stage = stage / STATE_FILE
                state_stage.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
                state_destination = args.dufs_root / STATE_FILE
                atomic_copy(state_stage, state_destination)
                published.append(state_destination)
                for old in previous:
                    if old.name.startswith("cmfa-") and old.name not in assets:
                        old.unlink(missing_ok=True)
            except Exception:
                for path in published:
                    path.unlink(missing_ok=True)
                for saved in backup.iterdir():
                    atomic_copy(saved, args.dufs_root / saved.name)
                raise
            sums = backup / "PUBLISHED_SHA256SUMS"
            sums.write_text("".join(f"{sha256(args.dufs_root / name)}  {name}\n" for name in sorted(assets)))
            print(f"PUBLISHED OK: {REPOSITORY} {args.tag} -> {args.dufs_root}; backup={backup}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SyncError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
