#!/usr/bin/env python3
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("nas-sync-android-release.py")
SPEC = importlib.util.spec_from_file_location("nas_sync_android_release_52nm", SCRIPT)
sync = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(sync)


class ReleaseSyncTests(unittest.TestCase):
    def test_52nm_has_an_independent_target(self):
        self.assertEqual(str(sync.DEFAULT_DUFS_ROOT), "/vol1/dufs/data/52nm")
        self.assertNotIn("jcjc", str(sync.DEFAULT_DUFS_ROOT))
        self.assertEqual(sync.DUFS_ALIASES["wuaiyun.apk"], "wuaiyun.apk")

    def test_only_stable_tags_are_accepted(self):
        self.assertEqual(sync.version_tuple("v2.11.50"), (2, 11, 50))
        for tag in ("2.11.50", "v2.11.50-rc.1", "latest"):
            with self.assertRaises(sync.SyncError):
                sync.version_tuple(tag)

    def test_release_requires_all_assets_and_trusted_digests(self):
        names = sync.required_names("v2.11.50")
        assets = [{"name": name, "size": 1, "digest": "sha256:" + "a" * 64} for name in names]
        release = {"tag_name": "v2.11.50", "draft": False, "prerelease": False, "assets": assets}
        self.assertEqual(set(sync.validate_release(release, "v2.11.50")), names)
        release["assets"][0]["digest"] = None
        with self.assertRaises(sync.SyncError):
            sync.validate_release(release, "v2.11.50")

    def test_metadata_and_download_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / "output-metadata.json"
            metadata.write_text(json.dumps({"elements": [{"versionName": "2.11.50", "versionCode": 211050}]}))
            self.assertEqual(sync.validate_metadata(metadata, "v2.11.50"), ("2.11.50", 211050))
            source = root / "source.apk"
            source.write_bytes(b"verified 52nm apk bytes")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            asset = {"name": "test.apk", "size": source.stat().st_size, "digest": "sha256:" + digest, "browser_download_url": source.as_uri()}
            target = root / "target.apk"
            sync.download(asset, target, "", 5, 1)
            self.assertEqual(target.read_bytes(), source.read_bytes())
            asset["size"] += 1
            with self.assertRaises(sync.SyncError):
                sync.download(asset, root / "bad.apk", "", 5, 1)

    def test_other_products_are_never_managed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            managed = root / "wuaiyun.apk"
            unrelated = root / "agent-install.ps1"
            managed.write_bytes(b"apk")
            unrelated.write_text("agent")
            self.assertEqual(sync.managed_existing(root), [managed])


if __name__ == "__main__":
    unittest.main()
