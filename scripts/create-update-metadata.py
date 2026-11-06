"""Generate the public update manifest from the signed APK, never from a guessed version."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True, encoding="utf-8")
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.M)
    if not package or not minimum:
        raise SystemExit("无法读取 APK 的真实包信息")
    name, code, version = package.groups()
    notes = args.notes.read_text(encoding="utf-8")
    if name != "io.github.tbeimu.gongxiaozhu" or args.tag != "v" + version or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.tag):
        raise SystemExit("标签与 APK 的版本或包名不匹配")
    if int(code) <= 0 or int(minimum[1]) < 31 or len(notes) > 32768:
        raise SystemExit("版本信息或更新说明不符合客户端格式")
    data = args.apk.read_bytes()
    if not 0 < len(data) <= 128 * 1024 * 1024:
        raise SystemExit("APK 大小超出更新限制")
    manifest = dict(schemaVersion=1, versionName=version, versionCode=int(code), packageName=name,
                    minSdk=int(minimum[1]), apkSize=len(data), apkSha256=hashlib.sha256(data).hexdigest(), releaseNotes=notes)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
