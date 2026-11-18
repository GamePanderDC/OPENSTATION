#!/usr/bin/env python3
"""Optional AirPlay 2 / Spotify Connect setup over existing Android Debug Bridge access."""

import argparse
from hashlib import sha256
from pathlib import Path
import re
import subprocess
import sys
import tarfile
from tempfile import TemporaryDirectory
from urllib.request import urlopen

REVISION = "2104537b81f23808570b75bafe93cc1b56e253a6"
BASE = f"https://raw.githubusercontent.com/qprots/station-max/{REVISION}"
AIRPLAY = "/data/local/tmp/ya-airplay"
SPOTIFY = "/data/local/openstation/receivers/spotify"
FILES = {
    "airplay": (
        ("airplay2/release/manage.sh", "be387ecf1e4f733a98f2d5cec54b107b20fd496f309f5ee9b9a1cc641baf283d"),
        ("airplay2/release/payload.tar", "d003116b516fc34870ed60fd85cfb4da5915a87c72d1422a428a606cd3efbc1e"),
    ),
    "spotify": (
        ("binaries/librespot", "0f56b913435e116011bae4e5ce5d0626dbf31f27f0e69af235dc0474cbffc18c"),
        ("binaries/audiosink", "7a4fa601a9a8942fdd33e165d71cfb49ce9671c793c65be9cdbe371ffcadf7a1"),
    ),
}


def download(path, digest, directory):
    file = directory / Path(path).name
    with urlopen(f"{BASE}/{path}", timeout=45) as response:
        data = response.read(32 * 1024 * 1024 + 1)
    if sha256(data).hexdigest() != digest:
        raise RuntimeError(f"SHA-256 не совпала: {path}")
    file.write_bytes(data)
    return file


def check_layout(bundle):
    with tarfile.open(bundle) as archive:
        for entry in archive:
            parts = Path(entry.name).parts
            if not entry.isfile() or not parts or entry.name.startswith("/") or ".." in parts:
                raise RuntimeError(f"Неверная запись в архиве AirPlay: {entry.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receiver", choices=FILES)
    parser.add_argument("action", choices=("install", "start", "stop", "status"))
    parser.add_argument("--serial", help="Серийный номер или адрес устройства в adb")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial] if args.serial else ["adb"]

    def device(*command, quiet=False):
        result = subprocess.run([*adb, *command], capture_output=quiet, text=quiet, check=True, timeout=120)
        return result.stdout.strip() if quiet else ""

    def shell(command, quiet=False):
        return device("shell", command, quiet=quiet)

    if shell("id -u; getprop ro.product.device; getprop ro.product.cpu.abi", quiet=True).split() != ["0", "yandexstation_2", "arm64-v8a"]:
        raise RuntimeError("Нужен существующий root через ADB на Станции Макс (arm64).")
    if args.receiver == "airplay":
        build = shell("getprop ro.build.version.incremental; getprop ro.build.version.sdk; getenforce", quiet=True).split()
        if build != ["5000000921", "28", "Permissive"]:
            raise RuntimeError("Пакет AirPlay 2 поддерживает базу 5000000921, Android 9 и существующий режим Permissive.")
    root = AIRPLAY if args.receiver == "airplay" else SPOTIFY
    script = "manage.sh" if args.receiver == "airplay" else "control.sh"
    if args.action != "install":
        shell(f"/system/bin/sh {root}/{script} {args.action}")
        return

    if args.receiver == "spotify" and shell(f"if [ -f {root}/OPENSTATION_MEDIA_REVISION ]; then cat {root}/OPENSTATION_MEDIA_REVISION; fi", quiet=True) == REVISION:
        shell(f"cd {root} && sha256sum -c MANIFEST.sha256")
        print("Spotify Connect уже установлен.")
        return

    with TemporaryDirectory(prefix="openstation-media-") as tmp:
        folder = Path(tmp)
        files = [(download(path, digest, folder), digest) for path, digest in FILES[args.receiver]]
        if args.receiver == "airplay":
            check_layout(folder / "payload.tar")
            template = "/data/local/tmp/ya-airplay-stage-XXXXXXXX"
        else:
            control = Path(__file__).with_name("spotify_receiver.sh")
            local = folder / "control.sh"
            data = control.read_bytes().replace(b"\r\n", b"\n")
            local.write_bytes(data)
            files.append((local, sha256(data).hexdigest()))
            template = "/data/local/openstation/receivers/.spotify-stage-XXXXXXXX"
            shell("umask 077; mkdir -p /data/local/openstation/receivers; "
                  "[ ! -L /data/local/openstation/receivers ] && chmod 700 /data/local/openstation/receivers")
        parent, prefix = template.rsplit("/", 1)
        stage = shell(f"umask 077; mktemp -d {template}", quiet=True)
        if not re.fullmatch(re.escape(parent + "/" + prefix.removesuffix("XXXXXXXX")) + "[a-zA-Z0-9]{8}", stage):
            raise RuntimeError("Станция вернула неожиданный временный путь.")
        manifest = folder / "MANIFEST.sha256"
        manifest.write_bytes("".join(f"{digest}  {path.name}\n" for path, digest in files).encode("ascii"))
        try:
            for file, _ in files:
                device("push", str(file), f"{stage}/{file.name}")
            device("push", str(manifest), f"{stage}/{manifest.name}")
            shell(f"cd {stage} && sha256sum -c MANIFEST.sha256")
            if args.receiver == "airplay":
                shell(f"/system/bin/sh {stage}/manage.sh install off off")
                shell(f"/system/bin/sh {root}/manage.sh stop")
            else:
                shell(f"cd {stage} && chmod 700 librespot audiosink control.sh && "
                      f"echo {REVISION} > OPENSTATION_MEDIA_REVISION && "
                      f"[ ! -e {root} ] && [ ! -L {root} ] && mv {stage} {root}")
        finally:
            names = " ".join(path.name for path, _ in files)
            subprocess.run([*adb, "shell", f"if [ -d {stage} ]; then cd {stage} && "
                            f"rm -f {names} MANIFEST.sha256 OPENSTATION_MEDIA_REVISION && rmdir {stage}; fi"],
                           timeout=15)
    print("Установлено. Приёмник можно включить в веб-пульте или командой start.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        sys.exit(1)
