#!/usr/bin/env python3
"""Launch a Forge 1.20.1 client from an HMCL-installed .minecraft, offline mode.

Usage: launch_client.py <instance_dir> <username> [server_host:port]
"""
import json
import os
import subprocess
import sys
import uuid

MC_ROOT = r"C:\Users\MSN\Desktop\mc\.minecraft"
VERSION = "1.20.1-Forge"
JAVA = r"C:\Program Files\Java\jdk-17\bin\java.exe"


def offline_uuid(name: str) -> str:
    return str(uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + name))


def build_command(instance: str, username: str, server: str | None):
    with open(os.path.join(MC_ROOT, "versions", VERSION, f"{VERSION}.json"), encoding="utf-8") as fh:
        data = json.load(fh)

    library_dir = os.path.join(MC_ROOT, "libraries")
    natives = os.path.join(MC_ROOT, "versions", VERSION, "natives-windows-x86_64")
    assets = os.path.join(MC_ROOT, "assets")

    # classpath: every library that carries a real file + the version jar
    cp_entries = []
    missing = []
    for lib in data.get("libraries", []):
        downloads = lib.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact and artifact.get("path"):
            path = os.path.join(library_dir, artifact["path"].replace("/", os.sep))
            if os.path.exists(path):
                cp_entries.append(path)
            else:
                missing.append(artifact["path"])
        elif "name" in lib:
            # natives-only entries have no artifact; skip
            pass
    version_jar = os.path.join(MC_ROOT, "versions", VERSION, f"{VERSION}.jar")
    cp_entries.append(version_jar)

    subs = {
        "${natives_directory}": natives,
        "${launcher_name}": "HMCL",
        "${launcher_version}": "3.6.12",
        "${classpath}": ";".join(cp_entries),
        "${classpath_separator}": ";",
        "${library_directory}": library_dir,
        "${version_name}": VERSION,
        "${primary_jar_name}": f"{VERSION}.jar",
        "${game_directory}": instance,
        "${assets_root}": assets,
        "${assets_index_name}": data["assetIndex"]["id"],
        "${auth_player_name}": username,
        "${auth_uuid}": offline_uuid(username),
        "${auth_access_token}": "0",
        "${clientid}": "",
        "${auth_xuid}": "",
        "${user_type}": "legacy",
        "${version_type}": "release",
    }

    def expand(token: str) -> str:
        for key, value in subs.items():
            token = token.replace(key, value)
        return token

    args = [JAVA, "-Xms1G", "-Xmx2G"]

    for entry in data["arguments"]["jvm"]:
        if isinstance(entry, str):
            args.append(expand(entry))
        elif isinstance(entry, dict):
            rules = entry.get("rules", [])
            allow = any(r.get("action") == "allow" and r.get("os", {}).get("name") in ("windows", None)
                        for r in rules)
            if allow:
                args.extend(expand(v) for v in entry["value"])

    # JVM 选项之后必须先给主类，再给游戏参数
    args.append(data["mainClass"])

    for entry in data["arguments"]["game"]:
        if isinstance(entry, str):
            args.append(expand(entry))
        elif isinstance(entry, dict):
            continue  # feature-gated args (demo/resolution/quickplay) handled below

    if server:
        args.extend(["--quickPlayMultiplayer", server])

    return args, missing


def main():
    instance = sys.argv[1]
    username = sys.argv[2]
    server = sys.argv[3] if len(sys.argv) > 3 else None
    os.makedirs(instance, exist_ok=True)

    args, missing = build_command(instance, username, server)
    if missing:
        print(f"[warn] {len(missing)} libraries missing, e.g. {missing[:3]}")

    log = os.path.join(instance, "client_stdout.log")
    with open(log, "w", encoding="utf-8") as fh:
        proc = subprocess.Popen(args, cwd=instance, stdout=fh, stderr=subprocess.STDOUT)
    print(f"launched {username} pid={proc.pid} log={log}", flush=True)
    print(f"cmdline len={len(' '.join(args))}", flush=True)

    # 常驻：让调用方（后台任务）跟着客户端一起活着，避免进程被回收
    proc.wait()
    print(f"{username} exited with {proc.returncode}", flush=True)


if __name__ == "__main__":
    main()
