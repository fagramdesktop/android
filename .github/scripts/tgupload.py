# -*- coding: utf-8 -*-
# @author: Burhanverse

import argparse
import asyncio
import hashlib
import html
import json
import os
import re
from os import environ

from pyrogram import Client, enums, types
from pyrogram.parser.html import HTML
from pyrogram.types import InputMediaDocument

API_HASH = environ.get("API_HASH") or environ.get("API_HASH_CONFIG") or environ.get("TELEGRAM_API_HASH")
API_ID = int(environ.get("API_ID") or environ.get("API_ID_CONFIG") or environ.get("TELEGRAM_API_ID") or 0)
BOT_TOKEN = environ.get("BOT_TOKEN") or environ.get("TELEGRAM_BOT_TOKEN")
SESSION_STRING = environ.get("SESSION_STRING") or environ.get("MTPROTO_SESSION")
MAX_CAPTION_LENGTH = 1024


def format_changelog(text: str, repo: str = None) -> str:
    escaped = html.escape(text.strip())
    # Convert markdown links [text](url) to HTML <a href="url">text</a>
    escaped = re.sub(r"\[([^\]]+)\]\((https?://[^\)]+)\)", r"""<a href="\2">\1</a>""", escaped)
    if repo:
        escaped = re.sub(r"(?<![\w/])#([0-9]+)\b", rf"""<a href="https://github.com/{repo}/pull/\1">#\1</a>""", escaped)
    return escaped


def resolve_caption(args) -> str:
    if getattr(args, "caption", None):
        return args.caption
    if getattr(args, "caption_file", None):
        with open(args.caption_file, "r", encoding="utf-8") as f:
            return f.read()

    changelog = getattr(args, "changelog", None)
    if not changelog and getattr(args, "changelog_file", None):
        with open(args.changelog_file, "r", encoding="utf-8") as f:
            changelog = f.read()
    if not changelog and "CHANGELOG" in environ:
        changelog = environ["CHANGELOG"]
    if changelog:
        changelog = changelog.strip()

    title = getattr(args, "title", None) or environ.get("TITLE")
    base_version = getattr(args, "base_version", None) or getattr(args, "tgd_version", None) or environ.get("BASE_VERSION") or environ.get("TGD_VERSION")
    build_type = getattr(args, "build_type", None) or environ.get("BUILD_TYPE")
    branch = getattr(args, "branch", None) or environ.get("BRANCH")
    checksum = getattr(args, "checksum", None) or environ.get("CHECKSUM")
    repo = getattr(args, "repo", None) or environ.get("GITHUB_REPOSITORY")

    lines = []
    if title:
        lines.append(f"<b>{html.escape(title)}</b>")
    if base_version:
        lines.append(f"Base: <code>{html.escape(base_version)}</code>")
    if build_type:
        lines.append(f"Build Type: <code>{html.escape(build_type)}</code>")
    if branch:
        lines.append(f"Branch: <code>{html.escape(branch)}</code>")
    if checksum:
        lines.append(f"SHA256: <code>{html.escape(checksum)}</code>")
    if changelog:
        lines.append("")
        escaped_changelog = format_changelog(changelog, repo=repo)
        lines.append(f"<blockquote expandable>{escaped_changelog}</blockquote>")

    return "\n".join(lines)


async def prepare_caption(app: Client, caption: str) -> str:
    if not caption:
        return caption

    parsed = await app.parser.parse(caption, enums.ParseMode.HTML)
    text = parsed["message"]
    raw_entities = parsed["entities"] or []

    if len(text) <= MAX_CAPTION_LENGTH:
        return caption

    type_entities = [await types.MessageEntity._parse(app, e, {}) for e in raw_entities]
    text = text[:MAX_CAPTION_LENGTH - 3] + "..."
    truncated_entities = []
    for e in type_entities:
        if e.offset >= MAX_CAPTION_LENGTH:
            continue
        if e.offset + e.length > MAX_CAPTION_LENGTH:
            e.length = MAX_CAPTION_LENGTH - e.offset
        truncated_entities.append(e)

    return HTML.unparse(text, truncated_entities)


async def upload_single(app, file, chat_id_list, caption, message_thread_id=None):
    caption = await prepare_caption(app, caption)
    for chat_id in chat_id_list:
        await app.send_document(
            chat_id=chat_id,
            document=file,
            caption=caption,
            parse_mode=enums.ParseMode.HTML,
            message_thread_id=message_thread_id,
        )
    print("Upload Successful!")


async def upload_group(app, files, chat_id_list, caption, message_thread_id=None, caption_position="last"):
    media = []
    caption = await prepare_caption(app, caption)
    caption_index = (len(files) - 1) if caption_position == "last" else 0
    for i, file in enumerate(files):
        has_caption = (i == caption_index)
        media.append(InputMediaDocument(
            media=file,
            caption=caption if has_caption else "",
            parse_mode=enums.ParseMode.HTML if has_caption else None,
        ))
    for chat_id in chat_id_list:
        await app.send_media_group(
            chat_id=chat_id,
            media=media,
            message_thread_id=message_thread_id,
        )
    print("Group Upload Successful!")


async def upload_rich(app: Client, args, chat_id_list, message_thread_id=None):
    build_info_path = getattr(args, "build_info", None)
    artifact_dir = None
    if getattr(args, "file", None):
        if os.path.isdir(args.file):
            artifact_dir = args.file
            if not build_info_path:
                candidate = os.path.join(artifact_dir, "build-info.json")
                if os.path.isfile(candidate):
                    build_info_path = candidate
        elif os.path.isfile(args.file) and args.file.endswith(".json"):
            build_info_path = args.file
            artifact_dir = os.path.dirname(args.file) or "."

    if not build_info_path and os.path.isfile("out/build-info.json"):
        build_info_path = "out/build-info.json"
        artifact_dir = "out"

    info = {}
    if build_info_path and os.path.isfile(build_info_path):
        with open(build_info_path, "r", encoding="utf-8") as f:
            info = json.load(f)
        if not artifact_dir:
            artifact_dir = os.path.dirname(build_info_path) or "."

    if not artifact_dir:
        artifact_dir = "out" if os.path.isdir("out") else "."

    ver_name = getattr(args, "ver_name", None) or info.get("verName") or getattr(args, "base_version", None) or environ.get("VER_NAME")
    build_num = getattr(args, "build_num", None) or info.get("buildNum") or environ.get("FA_BUILD") or environ.get("INU_BUILD")
    app_ver_code = getattr(args, "app_ver_code", None) or info.get("appVerCode") or environ.get("APP_VER_CODE")
    base_tag = getattr(args, "base_tag", None) or info.get("baseTag") or environ.get("BASE_TAG")
    commit_sha = getattr(args, "commit_sha", None) or info.get("commitSha") or environ.get("GITHUB_SHA", "")
    repo = getattr(args, "repo", None) or info.get("repo") or environ.get("GITHUB_REPOSITORY") or "fagramdesktop/android"
    app_name = getattr(args, "app_name", None) or environ.get("APP_NAME") or "FAgram"

    apk_files = info.get("apkFiles", {})
    apk_full = getattr(args, "apk_full", None) or apk_files.get("full")
    apk_pluginless = getattr(args, "apk_pluginless", None) or apk_files.get("pluginless")

    if getattr(args, "files", None):
        for f in args.files:
            if "pluginless" in f.lower():
                apk_pluginless = f
            elif "full" in f.lower():
                apk_full = f

    variants = []
    apk_paths = {}
    apk_names = {}

    if apk_full:
        full_path = apk_full if os.path.isfile(apk_full) else os.path.join(artifact_dir, apk_full)
        if os.path.isfile(full_path):
            variants.append("full")
            apk_paths["full"] = full_path
            apk_names["full"] = os.path.basename(full_path)

    if apk_pluginless:
        pluginless_path = apk_pluginless if os.path.isfile(apk_pluginless) else os.path.join(artifact_dir, apk_pluginless)
        if os.path.isfile(pluginless_path):
            variants.append("pluginless")
            apk_paths["pluginless"] = pluginless_path
            apk_names["pluginless"] = os.path.basename(pluginless_path)

    if not variants:
        raise ValueError(f"No APK files found for rich message upload in '{artifact_dir}' (expected full and/or pluginless)")

    checksums = {}
    for v in variants:
        with open(apk_paths[v], "rb") as f:
            checksums[v] = hashlib.sha256(f.read()).hexdigest()

    notes = ""
    if build_num:
        changelog_file = os.path.join("changelogs", f"{build_num}.md")
        if os.path.isfile(changelog_file):
            with open(changelog_file, "r", encoding="utf-8") as f:
                notes = f.read().strip()

    if not notes:
        if getattr(args, "changelog_file", None) and os.path.isfile(args.changelog_file):
            with open(args.changelog_file, "r", encoding="utf-8") as f:
                notes = f.read().strip()
        elif getattr(args, "changelog", None):
            notes = args.changelog.strip()
        elif "CHANGELOG" in environ:
            notes = environ["CHANGELOG"].strip()

    full_changelog = ""
    if repo and commit_sha:
        if base_tag:
            full_changelog = f"[Full changelog: {base_tag}...{commit_sha[:7]}](https://github.com/{repo}/compare/{base_tag}...{commit_sha})"
        else:
            full_changelog = f"[Full changelog](https://github.com/{repo}/commits/{commit_sha})"

    footer_parts = ["#release"]
    if ver_name:
        footer_parts.append(f"v{ver_name}")
    details = []
    if build_num:
        details.append(f"build {build_num}")
    if app_ver_code:
        details.append(f"based on {app_ver_code}")
    if details:
        footer_parts.append(f"({', '.join(details)})")
    footer = " ".join(footer_parts)

    title_header = f"## {app_name}" + (f" v{build_num}" if build_num else "")

    content = [
        title_header,
        f"<footer>{footer}</footer>",
    ]
    for v in variants:
        content.append(f'![](tg://document?id={v} "{v}")')

    if notes or full_changelog:
        content.append("<details><summary>Changelog</summary>")
        if notes:
            content.append(notes)
        if full_changelog:
            content.append(full_changelog)
        content.append("</details>")

    checksum_lines = []
    for v in variants:
        checksum_lines.append(f"• {v}: SHA256 ({v}): `{checksums[v]}`")
    content.append("<details><summary>Checksums</summary>")
    content.extend(checksum_lines)
    content.append("</details>")

    attachments = []
    for v in variants:
        attachments.append(
            types.InputRichMessageMedia(
                id=v,
                media=types.InputMediaDocument(
                    media=apk_paths[v],
                    file_name=apk_names[v],
                ),
            )
        )

    rich_message = types.InputRichMessage(
        markdown="\n\n".join(filter(None, content)),
        media=attachments,
    )

    for chat_id in chat_id_list:
        await app.send_rich_message(
            chat_id=chat_id,
            rich_message=rich_message,
            message_thread_id=message_thread_id,
        )
    print("Rich Message Upload Successful!")


def parse_chat_id(val: str):
    val = val.strip()
    try:
        return int(val)
    except ValueError:
        return val


async def main():
    parser = argparse.ArgumentParser(prog="Uploader", description="Telegram file uploader")
    parser.add_argument("file", type=str, nargs="?", help="File or directory to upload (single file mode or artifact directory)")
    parser.add_argument("--files", type=str, nargs="+", help="Files to upload as a grouped post")
    parser.add_argument("--rich", "--rich-message", action="store_true", dest="rich", help="Send as rich message (Instant View / Telegram Rich Message)")
    parser.add_argument("--build-info", type=str, help="Path to build-info.json or directory containing it")
    parser.add_argument("--app-name", type=str, help="Application name for title (default: FAgram)")
    parser.add_argument("--build-num", type=int, help="Build number (e.g. 30)")
    parser.add_argument("--ver-name", type=str, help="Version name (e.g. 12.10.5-abc1234)")
    parser.add_argument("--app-ver-code", type=int, help="Base app version code (e.g. 72379)")
    parser.add_argument("--apk-full", type=str, help="Path to full variant APK")
    parser.add_argument("--apk-pluginless", type=str, help="Path to pluginless variant APK")
    parser.add_argument("--base-tag", type=str, help="Base git release tag for compare link")
    parser.add_argument("--commit-sha", type=str, help="Current commit SHA")
    parser.add_argument("--chat-id", type=parse_chat_id, help="Chat ID(s) to upload the file to", nargs="+")
    parser.add_argument("--topic-id", "--message-thread-id", type=int, dest="message_thread_id", help="Forum topic/thread ID")
    parser.add_argument("--caption", type=str, help="Caption for the file")
    parser.add_argument("--caption-file", type=str, help="File containing caption")
    parser.add_argument("--caption-position", choices=["first", "last"], default="last", help="Position of caption in media group (default: last)")
    parser.add_argument("--title", type=str, help="Title for the post")
    parser.add_argument("--base-version", "--tgd-version", type=str, dest="base_version", help="Telegram Base version")
    parser.add_argument("--build-type", type=str, help="Build type (ci or rel)")
    parser.add_argument("--branch", type=str, help="Git branch name")
    parser.add_argument("--checksum", type=str, help="SHA256 Checksum")
    parser.add_argument("--repo", type=str, help="GitHub repository (owner/repo)")
    parser.add_argument("--changelog", type=str, help="Changelog text")
    parser.add_argument("--changelog-file", type=str, help="File containing changelog text")
    args = parser.parse_args()

    chat_id_list = args.chat_id
    if not chat_id_list:
        env_chat = environ.get("CHAT_ID") or environ.get("TELEGRAM_CHAT_ID") or environ.get("TELEGRAM_CHANNEL")
        if env_chat:
            chat_id_list = [parse_chat_id(x) for x in env_chat.split() if x.strip()]
    if not chat_id_list:
        parser.error("Chat ID must be provided via --chat-id or CHAT_ID / TELEGRAM_CHAT_ID / TELEGRAM_CHANNEL env var")

    message_thread_id = args.message_thread_id
    if message_thread_id is None:
        env_thread = environ.get("TOPIC_ID") or environ.get("MESSAGE_THREAD_ID") or environ.get("TELEGRAM_THREAD_ID")
        if env_thread:
            message_thread_id = int(env_thread)

    if not (API_ID and API_HASH and (BOT_TOKEN or SESSION_STRING)):
        parser.error("API_ID, API_HASH, and BOT_TOKEN (or SESSION_STRING/MTPROTO_SESSION) must be set via env vars")

    if SESSION_STRING:
        app = Client("Uploader", api_id=API_ID, api_hash=API_HASH, session_string=SESSION_STRING)
    else:
        app = Client("Uploader", api_id=API_ID, api_hash=API_HASH, bot_token=BOT_TOKEN)

    if args.rich:
        async with app:
            await upload_rich(app, args, chat_id_list, message_thread_id=message_thread_id)
        return

    if not args.files and not args.file:
        parser.error("Either 'file' or '--files' must be provided")

    caption = resolve_caption(args)
    if not caption:
        parser.error("A caption, changelog, or structured fields (--title, --caption, etc.) must be provided")

    async with app:
        if args.files:
            valid_files = [f for f in args.files if f and f.strip()]
            if not valid_files:
                parser.error("No valid files provided to --files")
            if len(valid_files) == 1:
                await upload_single(app, valid_files[0], chat_id_list, caption, message_thread_id=message_thread_id)
            else:
                await upload_group(
                    app,
                    valid_files,
                    chat_id_list,
                    caption,
                    message_thread_id=message_thread_id,
                    caption_position=args.caption_position,
                )
        else:
            await upload_single(app, args.file, chat_id_list, caption, message_thread_id=message_thread_id)


if __name__ == "__main__":
    asyncio.run(main())

