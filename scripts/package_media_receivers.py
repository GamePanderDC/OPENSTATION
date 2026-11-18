#!/usr/bin/env python3
"""Build the two deterministic, separately downloadable OTA receiver bundles."""

from hashlib import sha256
from io import BytesIO
from pathlib import Path
import tarfile
from tempfile import TemporaryDirectory

from manage_media_receivers import FILES, REVISION, check_layout, download


def archive(path, files):
    with tarfile.open(path, "w") as out:
        for name, data in sorted(files.items()):
            entry = tarfile.TarInfo(name)
            entry.mode = 0o700
            entry.size = len(data)
            out.addfile(entry, BytesIO(data))
    return sha256(path.read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    cache = root / "build" / "qprots-media"
    output = root / "build" / "media-receivers"
    output.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="openstation-receivers-") as tmp:
        for receiver, filename in (("airplay", "OpenStationAirPlay2.tar"), ("spotify", "OpenStationSpotifyConnect.tar")):
            files = {}
            for artifact, digest in FILES[receiver]:
                file = cache / Path(artifact).name
                if not file.is_file() or sha256(file.read_bytes()).hexdigest() != digest:
                    file = download(artifact, digest, Path(tmp))
                if file.name == "payload.tar":
                    check_layout(file)
                files[file.name] = file.read_bytes()
            if receiver == "airplay":
                changes = (
                    (
                        b'[ "$(getprop ro.build.version.incremental)" = 5000000921 ] || exit 65',
                        b'case "$(getprop ro.build.version.incremental)" in 5000000921|2875769476) ;; *) exit 65;; esac',
                    ),
                    (
                        b"/data/local/tmp/ya-airplay-stage-*",
                        b"/data/local/openstation/receivers/stage-*",
                    ),
                )
                for old, new in changes:
                    if files["manage.sh"].count(old) != 1:
                        raise RuntimeError("Unexpected AirPlay installer")
                    files["manage.sh"] = files["manage.sh"].replace(old, new)
            else:
                files["control.sh"] = (root / "scripts" / "spotify_receiver.sh").read_bytes().replace(b"\r\n", b"\n")
                files["MANIFEST.sha256"] = "".join(f"{sha256(data).hexdigest()}  {name}\n"
                                                    for name, data in sorted(files.items())).encode("ascii")
                files["OPENSTATION_MEDIA_REVISION"] = (REVISION + "\n").encode("ascii")
            dest = output / filename
            digest = archive(dest, files)
            print(filename, dest.stat().st_size, digest)


if __name__ == "__main__":
    main()
