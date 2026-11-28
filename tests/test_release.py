"""Privacy, signing, and one-click release guards without calling GitHub or Gradle."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("pixel_release", Path(__file__).parents[1] / "scripts/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseGuards(unittest.TestCase):
    def test_existing_signing_identity_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            signing_dir = root / ".signing"
            signing_dir.mkdir()
            key = signing_dir / "release.jks"
            key.write_bytes(b"existing-identity")
            with patch.object(release, "ROOT", root), patch.object(release, "run") as run:
                with self.assertRaises(SystemExit):
                    release.init_signing()
            run.assert_not_called()
            self.assertEqual(key.read_bytes(), b"existing-identity")

    def publish_with(self, tree, existing=None):
        repo = {"private": False, "fork": False, "default_branch": "main"}
        answers = [
            "logged in",
            json.dumps(repo),
            json.dumps([{"commit": {"tree": {"sha": "tree"}}}]),
            json.dumps(tree),
        ]
        if existing is not None:
            answers.append(json.dumps(existing))
        with patch.object(release.shutil, "which", return_value="gh"), \
             patch.object(release, "gh", side_effect=answers) as gh, \
             patch.object(
                 release,
                 "build",
                 return_value=({"VERSION_NAME": "2.7.0"}, Path("gray-2.7.0.apk"), Path("SHA256SUMS")),
             ) as build:
            with self.assertRaises(SystemExit):
                release.publish_release(
                    SimpleNamespace(repo="owner/pixel-app-releases", create_repo=False, notes_file=None)
                )
            return gh.call_args_list, build.called

    def test_private_source_in_history_stops_before_build_and_upload(self):
        calls, built = self.publish_with({"tree": [{"path": "README.md"}, {"path": "server/.env"}]})
        self.assertFalse(built)
        self.assertFalse(any(call.args[0] == "release" for call in calls))

    def test_truncated_repository_tree_is_rejected(self):
        _, built = self.publish_with({"truncated": True, "tree": []})
        self.assertFalse(built)

    def test_published_version_is_never_overwritten(self):
        calls, _ = self.publish_with(
            {"tree": [{"path": "README.md"}]},
            [{"tag_name": "v2.7.0", "draft": False}],
        )
        self.assertFalse(any(call.args[0] == "release" for call in calls))

    def test_unexpected_draft_asset_is_never_published(self):
        calls, _ = self.publish_with(
            {"tree": [{"path": "README.md"}]},
            [{"tag_name": "v2.7.0", "draft": True, "assets": [{"name": "source.zip"}]}],
        )
        self.assertFalse(any(call.args[0] == "release" for call in calls))


class OneClickRelease(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        app_dir = self.root / "app"
        app_dir.mkdir()
        self.version = app_dir / "version.properties"
        self.version.write_text("VERSION_CODE=33\nVERSION_NAME=2.7.0\n")
        self.git("init")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release@example.invalid")
        self.git("add", "app/version.properties")
        self.git("commit", "-m", "Initial")
        self.remote = self.root / "origin.git"
        self.other_remote = self.root / "github.git"
        self.git("init", "--bare", str(self.remote))
        self.git("init", "--bare", str(self.other_remote))
        self.git("remote", "add", "origin", str(self.remote))
        self.git("remote", "add", "github", str(self.other_remote))
        self.git("push", "origin", "HEAD:refs/heads/release-source")
        self.git("branch", "--set-upstream-to=origin/release-source")
        patcher = patch.object(release, "ROOT", self.root)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = patch.object(release.shutil, "which", return_value="gh")
        patcher.start()
        self.addCleanup(patcher.stop)
        self.args = SimpleNamespace(repo="owner/pixel-app-releases", create_repo=False, notes_file=None)

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout.strip()

    def test_success_bumps_patch_and_code_and_commits_only_version(self):
        (self.root / "other.txt").write_text("unrelated staged work")
        self.git("add", "other.txt")
        with patch.object(release, "gh", return_value=""), patch.object(release, "publish_release"):
            release.publish(self.args)
        self.assertEqual(self.version.read_text(), "VERSION_CODE=34\nVERSION_NAME=2.7.1\n")
        self.assertEqual(self.git("log", "-1", "--format=%s"), "Release Android v2.7.1")
        self.assertEqual(
            self.git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"),
            "app/version.properties",
        )
        self.assertEqual(self.git("diff", "--cached", "--name-only"), "other.txt")
        self.assertFalse((self.root / "release-state.json").exists())
        remote_head = self.git("ls-remote", "origin", "refs/heads/release-source").split()[0]
        self.assertEqual(remote_head, self.git("rev-parse", "HEAD"))
        self.assertEqual(self.git("ls-remote", "github"), "")
        self.assertEqual(self.git("ls-remote", "origin", "refs/heads/main"), "")

    def test_missing_upstream_stops_before_bump_or_release(self):
        self.git("branch", "--unset-upstream")
        with patch.object(release, "gh", return_value=""), patch.object(release, "publish_release") as publish:
            with self.assertRaises(SystemExit):
                release.publish(self.args)
        publish.assert_not_called()
        self.assertEqual(release.properties(self.version)["VERSION_NAME"], "2.7.0")
        self.assertFalse((self.root / "release-state.json").exists())

    def test_failed_push_retries_without_rebuild_or_duplicate_commit(self):
        real_git = release.git

        def failing_push(*args):
            if args[0] == "push":
                raise subprocess.CalledProcessError(1, "git push")
            return real_git(*args)

        with patch.object(release, "gh", return_value=""), \
             patch.object(release, "publish_release"), \
             patch.object(release, "git", side_effect=failing_push):
            with self.assertRaises(subprocess.CalledProcessError):
                release.publish(self.args)
        committed = self.git("rev-parse", "HEAD")
        self.assertEqual(json.loads((self.root / "release-state.json").read_text())["phase"], "committed")
        with patch.object(release, "gh", return_value=""), patch.object(release, "publish_release") as publish:
            release.publish(self.args)
        publish.assert_not_called()
        self.assertEqual(self.git("rev-parse", "HEAD"), committed)
        self.assertEqual(self.git("ls-remote", "origin", "refs/heads/release-source").split()[0], committed)
        self.assertFalse((self.root / "release-state.json").exists())

    def test_changed_upstream_rejects_resume(self):
        with patch.object(release, "gh", return_value=""), \
             patch.object(release, "publish_release", side_effect=RuntimeError("upload failed")):
            with self.assertRaises(RuntimeError):
                release.publish(self.args)
        branch = self.git("symbolic-ref", "--short", "HEAD")
        self.git("config", f"branch.{branch}.remote", "github")
        with patch.object(release, "gh", return_value=""), patch.object(release, "publish_release") as publish:
            with self.assertRaises(SystemExit):
                release.publish(self.args)
        publish.assert_not_called()
        self.assertEqual(self.git("ls-remote", "github"), "")

    def test_failed_upload_resumes_same_version_without_committing_early(self):
        with patch.object(release, "gh", return_value=""), \
             patch.object(release, "publish_release", side_effect=RuntimeError("upload failed")):
            with self.assertRaises(RuntimeError):
                release.publish(self.args)
        self.assertEqual(self.git("log", "-1", "--format=%s"), "Initial")
        with patch.object(release, "gh", side_effect=["", '{"draft": true}']), \
             patch.object(release, "publish_release") as publish:
            release.publish(self.args)
        publish.assert_called_once()
        self.assertEqual(release.properties(self.version)["VERSION_NAME"], "2.7.1")

    def test_commit_failure_retries_commit_without_republishing(self):
        real_git = release.git

        def failing_git(*args):
            if args[0] == "commit":
                raise subprocess.CalledProcessError(1, "git commit")
            return real_git(*args)

        with patch.object(release, "gh", return_value=""), \
             patch.object(release, "publish_release"), \
             patch.object(release, "git", side_effect=failing_git):
            with self.assertRaises(subprocess.CalledProcessError):
                release.publish(self.args)
        with patch.object(release, "gh", return_value=""), patch.object(release, "publish_release") as publish:
            release.publish(self.args)
        publish.assert_not_called()
        self.assertEqual(self.git("log", "-1", "--format=%s"), "Release Android v2.7.1")

    def test_unknown_remote_result_recovers_published_version(self):
        with patch.object(release, "gh", return_value=""), \
             patch.object(release, "publish_release", side_effect=RuntimeError("connection lost")):
            with self.assertRaises(RuntimeError):
                release.publish(self.args)
        with patch.object(release, "gh", side_effect=["", '{"draft": false}']), \
             patch.object(release, "publish_release") as publish:
            release.publish(self.args)
        publish.assert_not_called()
        self.assertEqual(release.properties(self.version)["VERSION_CODE"], "34")

    def test_dirty_version_is_rejected_before_publishing(self):
        self.version.write_text("VERSION_CODE=40\nVERSION_NAME=2.8.0\n")
        with patch.object(release, "gh", return_value=""), patch.object(release, "publish_release") as publish:
            with self.assertRaises(SystemExit):
                release.publish(self.args)
        publish.assert_not_called()
        self.assertFalse((self.root / "release-state.json").exists())


if __name__ == "__main__":
    unittest.main()
