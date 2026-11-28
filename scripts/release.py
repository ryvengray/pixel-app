#!/usr/bin/env python3
"""Build locally and distribute only signed APKs to a separate GitHub repository."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPO = "ryvengray/pixel-app-releases"
VERSION_REL = "app/version.properties"
SIGNING_DIR_REL = ".signing"


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def gh(*args):
    return run("gh", *args, capture_output=True, text=True).stdout.strip()


def properties(path):
    return dict(
        line.strip().split("=", 1)
        for line in path.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    )


def has_signing_config():
    env_keys = ("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
    if all(os.environ.get(k) for k in env_keys):
        return True
    return (ROOT / SIGNING_DIR_REL / "signing.properties").exists()


def init_signing():
    signing_dir = ROOT / SIGNING_DIR_REL
    config = signing_dir / "signing.properties"
    key = signing_dir / "release.jks"
    if config.exists() or key.exists():
        raise SystemExit("Signing files already exist; refusing to replace the release identity.")
    signing_dir.mkdir(parents=True, exist_ok=True)
    signing_dir.chmod(0o700)
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, PIXEL_SIGNING_PASSWORD=password)
    run(
        "keytool", "-genkeypair",
        "-keystore", str(key),
        "-storetype", "PKCS12",
        "-alias", "pixel-sync",
        "-keyalg", "RSA",
        "-keysize", "3072",
        "-validity", "10000",
        "-dname", "CN=Pixel Obsidian Sync",
        "-storepass:env", "PIXEL_SIGNING_PASSWORD",
        "-keypass:env", "PIXEL_SIGNING_PASSWORD",
        env=env,
    )
    key.chmod(0o600)
    fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(
            f"RELEASE_STORE_FILE={SIGNING_DIR_REL}/{key.name}\n"
            f"RELEASE_STORE_PASSWORD={password}\n"
            "RELEASE_KEY_ALIAS=pixel-sync\n"
            f"RELEASE_KEY_PASSWORD={password}\n"
        )
    print(f"Signing identity created. Back up {SIGNING_DIR_REL}/ securely.")


def find_sdk():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (ROOT / "local.properties").exists():
        sdk = properties(ROOT / "local.properties").get("sdk.dir")
    if not sdk:
        raise SystemExit("Set ANDROID_HOME or configure sdk.dir in local.properties.")
    return Path(sdk)


def build():
    if not has_signing_config():
        raise SystemExit("Missing signing config. Run init-signing or set RELEASE_* environment variables.")
    if not (ROOT / "app/google-services.json").exists():
        raise SystemExit("Missing app/google-services.json required for prodRelease.")
    version = properties(ROOT / VERSION_REL)
    version_name = version["VERSION_NAME"]
    version_code = int(version["VERSION_CODE"])
    if not re.fullmatch(r"\d+\.\d+\.\d+", version_name):
        raise SystemExit("VERSION_NAME must use major.minor.patch.")
    if not 0 < version_code <= 2100000000:
        raise SystemExit("Invalid VERSION_CODE.")
    sdk = find_sdk()
    verifiers = sorted((sdk / "build-tools").glob("*/apksigner"))
    if not verifiers:
        raise SystemExit("Android SDK apksigner not found.")
    run("bash", str(ROOT / "tests/sync-test.sh"))
    run("bash", str(ROOT / "gradlew"), "--no-daemon", "assembleProdRelease")
    apk = ROOT / "app/build/outputs/apk/prod/release/app-prod-release.apk"
    run(str(verifiers[-1]), "verify", "--verbose", str(apk))
    output = ROOT / "dist"
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"gray-{version_name}.apk"
    shutil.copyfile(apk, target)
    checksum = output / "SHA256SUMS"
    checksum.write_text(f"{hashlib.sha256(target.read_bytes()).hexdigest()}  {target.name}\n")
    print(f"Verified APK: {target}")
    return version, target, checksum


def publish_release(args):
    if not shutil.which("gh"):
        raise SystemExit("GitHub CLI is required: install gh, then run gh auth login.")
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", args.repo):
        raise SystemExit("--repo must be owner/repository.")
    if not args.repo.endswith("-releases"):
        raise SystemExit("Use a dedicated repository ending in -releases.")
    gh("auth", "status")
    if args.create_repo:
        gh("repo", "create", args.repo, "--public", "--description", "Gray Android binary releases", "--add-readme")
        readme = (ROOT / "distribution/README.md").read_text().replace("OWNER/REPOSITORY", args.repo)
        current = json.loads(gh("api", f"repos/{args.repo}/contents/README.md"))
        gh(
            "api", "--method", "PUT", f"repos/{args.repo}/contents/README.md",
            "-f", "message=Add Android installation instructions",
            "-f", f"sha={current['sha']}",
            "-f", "content=" + base64.b64encode(readme.encode()).decode(),
        )
    repo = json.loads(gh("api", f"repos/{args.repo}"))
    if repo["private"] or repo["fork"]:
        raise SystemExit("Expected an independent public distribution repository.")
    # Never publish into a repository containing application source or old private history.
    commits = json.loads(gh("api", f"repos/{args.repo}/commits?per_page=100"))
    for commit in commits:
        tree = json.loads(gh("api", f"repos/{args.repo}/git/trees/{commit['commit']['tree']['sha']}?recursive=1"))
        if tree.get("truncated") or any(item["path"] not in {"README.md"} for item in tree["tree"]):
            raise SystemExit("Distribution repository history must contain only README.md.")
    if len(commits) >= 100:
        raise SystemExit("Repository history too large to verify safely.")
    version, apk, checksum = build()
    version_name = version["VERSION_NAME"]
    tag = "v" + version_name
    notes = (
        args.notes_file.read_text()
        if args.notes_file
        else f"Gray Android {version_name}\n\nAndroid 12 or later. Update source: https://github.com/{args.repo}"
    )
    # Draft first: interrupted uploads remain invisible to Obtainium until retried.
    existing = json.loads(gh("api", f"repos/{args.repo}/releases?per_page=100"))
    release = next((item for item in existing if item["tag_name"] == tag), None)
    if release and not release["draft"]:
        raise SystemExit("Version already published. Increment VERSION_CODE and VERSION_NAME.")
    if release and any(asset["name"] not in {apk.name, checksum.name} for asset in release["assets"]):
        raise SystemExit("Draft contains unexpected attachments; inspect it before publishing.")
    if not release:
        gh(
            "release", "create", tag,
            "--repo", args.repo,
            "--target", repo["default_branch"],
            "--title", tag,
            "--notes", notes,
            "--draft",
        )
    gh("release", "upload", tag, str(apk), str(checksum), "--repo", args.repo, "--clobber")
    gh("release", "edit", tag, "--repo", args.repo, "--draft=false", "--latest")
    print(f"Published: https://github.com/{args.repo}/releases/tag/{tag}")
    print(f"Add to Obtainium: https://github.com/{args.repo}")


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
    ).stdout.strip()


def next_version(content):
    values = dict(
        line.split("=", 1)
        for line in content.splitlines()
        if line and not line.startswith("#")
    )
    if not re.fullmatch(r"\d+\.\d+\.\d+", values["VERSION_NAME"]):
        raise SystemExit("VERSION_NAME must use major.minor.patch.")
    major, minor, patch = map(int, values["VERSION_NAME"].split("."))
    code = int(values["VERSION_CODE"]) + 1
    if not 1 < code <= 2100000000:
        raise SystemExit("VERSION_CODE has reached the Android limit or is invalid.")
    name = f"{major}.{minor}.{patch + 1}"
    content = re.sub(r"^VERSION_CODE=.*$", f"VERSION_CODE={code}", content, flags=re.M)
    content = re.sub(r"^VERSION_NAME=.*$", f"VERSION_NAME={name}", content, flags=re.M)
    return name, content


def publish(args):
    # Lock covers version changes, remote publication and the local commit.
    with (ROOT / "release.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit("Another Android release is already running.")
        publish_and_commit(args)


def publish_and_commit(args):
    version_file = ROOT / VERSION_REL
    state_file = ROOT / "release-state.json"
    # Fail before creating a release if Git cannot create the later local commit.
    git("var", "GIT_AUTHOR_IDENT")
    git("var", "GIT_COMMITTER_IDENT")
    if git("diff", "--name-only", "--diff-filter=U"):
        raise SystemExit("Resolve Git conflicts before publishing.")
    branch = git("symbolic-ref", "--quiet", "--short", "HEAD")
    try:
        push_remote = git("config", "--get", f"branch.{branch}.remote")
        push_ref = git("config", "--get", f"branch.{branch}.merge")
        git("remote", "get-url", "--push", push_remote)
    except subprocess.CalledProcessError:
        raise SystemExit("Configure the current branch's upstream before publishing.")
    if not push_ref.startswith("refs/heads/"):
        raise SystemExit("The upstream must be a remote branch.")
    print(f"Version commit will be pushed to {push_remote}/{push_ref.removeprefix('refs/heads/')}", flush=True)
    if not shutil.which("gh"):
        raise SystemExit("Install GitHub CLI and run gh auth login first.")
    gh("auth", "status")
    resuming = state_file.exists()
    if resuming:
        state = json.loads(state_file.read_text())
        if state["repo"] != args.repo:
            raise SystemExit("An unfinished release exists for a different repository.")
        if version_file.read_text() not in {state["original"], state["updated"]}:
            raise SystemExit("Version file changed during an unfinished release; inspect release-state.json.")
        if args.create_repo:
            raise SystemExit("Retry the unfinished release without --create-repo.")
        if (
            state.get("branch", branch) != branch
            or state.get("push_remote", push_remote) != push_remote
            or state.get("push_ref", push_ref) != push_ref
        ):
            raise SystemExit("Return to the original release branch and upstream before retrying.")
    else:
        status = git("status", "--porcelain", "--", VERSION_REL)
        if status and not status.startswith("?? "):
            raise SystemExit(f"Commit existing {VERSION_REL} edits before starting a release.")
        original = version_file.read_text()
        name, updated = next_version(original)
        state = {
            "repo": args.repo,
            "name": name,
            "original": original,
            "updated": updated,
            "phase": "publishing",
        }
        state_file.write_text(json.dumps(state, indent=2) + "\n")
    state.update(branch=branch, push_remote=push_remote, push_ref=push_ref)
    state_file.write_text(json.dumps(state, indent=2) + "\n")
    version_file.write_text(state["updated"])
    tag = "v" + state["name"]
    if resuming and state["phase"] == "publishing":
        # An upload can succeed even if the connection drops before its response.
        try:
            remote = json.loads(gh("api", f"repos/{args.repo}/releases/tags/{tag}"))
        except subprocess.CalledProcessError as error:
            if "HTTP 404" not in (error.stderr or ""):
                raise
        else:
            if not remote["draft"]:
                state["phase"] = "published"
    if state["phase"] == "publishing":
        print(f"Releasing {tag}; VERSION_CODE={properties(version_file)['VERSION_CODE']}", flush=True)
        publish_release(args)
        state["phase"] = "published"
    else:
        print(f"Release {tag} is already published; finishing the local commit and push (no rebuild).", flush=True)
    # Persist remote success before committing; a retry only finishes this commit.
    state_file.write_text(json.dumps(state, indent=2) + "\n")
    tracked = git("ls-files", "--", VERSION_REL)
    if not tracked or git("diff", "HEAD", "--", VERSION_REL):
        git("add", "--", VERSION_REL)
        git("commit", "--only", "-m", f"Release Android {tag}", "--", VERSION_REL)
    state["phase"] = "committed"
    state_file.write_text(json.dumps(state, indent=2) + "\n")
    print(f"Pushing {branch} to {push_remote}/{push_ref.removeprefix('refs/heads/')}…", flush=True)
    git("push", "--", push_remote, f"HEAD:{push_ref}")
    state_file.unlink()
    print(f"Completed Android {tag}: release published, version committed and pushed.", flush=True)


def main():
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    current_java = subprocess.run([java, "-version"], capture_output=True, text=True)
    match = re.search(r'version "(\d+)', current_java.stderr)
    if sys.platform == "darwin" and (not match or int(match[1]) < 17):
        java_home = subprocess.run(["/usr/libexec/java_home", "-v", "17"], capture_output=True, text=True)
        if java_home.returncode == 0:
            os.environ["JAVA_HOME"] = java_home.stdout.strip()
            os.environ["PATH"] = java_home.stdout.strip() + "/bin:" + os.environ.get("PATH", "")
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init-signing", help="Create a permanent local signing identity once")
    commands.add_parser("build", help="Build and verify a signed APK locally")
    publish_parser = commands.add_parser("publish", help="Build and publish binary assets only")
    publish_parser.add_argument("--repo", default=DEFAULT_REPO)
    publish_parser.add_argument("--create-repo", action="store_true")
    publish_parser.add_argument("--notes-file", type=Path)
    args = parser.parse_args()
    if args.command == "init-signing":
        init_signing()
    elif args.command == "build":
        build()
    else:
        publish(args)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr or str(error), file=sys.stderr)
        sys.exit(1)
