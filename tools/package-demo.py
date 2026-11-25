#!/usr/bin/env python3
"""Package a built ChatSphere JAR and a platform's Maven runtime dependencies."""
import argparse
from io import BytesIO
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

PLATFORMS = {
    "linux-x64": ("linux", "Linux", "x86_64"),
    "windows-x64": ("win", "Windows", "x86_64"),
}


def sqlite_for_platform(path, operating_system, architecture):
    native_dir = "org/sqlite/native/"
    selected = f"{native_dir}{operating_system}/{architecture}/"
    buffer = BytesIO()
    with ZipFile(path) as source, ZipFile(buffer, "w", ZIP_DEFLATED) as output:
        if not any(name.startswith(selected) and not name.endswith("/") for name in source.namelist()):
            raise ValueError(f"Missing SQLite native library for {operating_system} {architecture}")
        for entry in source.infolist():
            if entry.filename.startswith(native_dir) and not entry.filename.startswith(selected):
                continue
            output.writestr(entry.filename, source.read(entry), compress_type=ZIP_DEFLATED)
    return buffer.getvalue()


def add_script(archive, name, contents):
    details = ZipInfo(f"ChatSphere/{name}")
    details.create_system = 3
    details.external_attr = (0o100755 << 16)
    archive.writestr(details, contents)


def package(args):
    classifier, os_name, architecture = PLATFORMS[args.platform]
    dependencies = sorted(args.dependencies.glob("*.jar"))
    javafx = [jar for jar in dependencies if jar.name.startswith("javafx-")
              and jar.name.endswith(f"-{classifier}.jar")]
    if len(javafx) != 4:
        raise ValueError(f"Expected four OpenJFX {classifier} runtime JARs, found {len(javafx)}")
    if not all(any(jar.name.startswith(prefix) for jar in dependencies)
               for prefix in ("sqlite-jdbc-", "ikonli-javafx-", "ikonli-feather-pack-", "slf4j-api-")):
        raise ValueError("The dependency directory must contain the full Maven runtime classpath")

    base = Path(__file__).resolve().parents[1]
    client = 'com.chatsphere.client.ClientApp'
    server = 'com.chatsphere.server.ServerMain'
    with ZipFile(args.output, "w", ZIP_DEFLATED, compresslevel=9) as archive:
        archive.write(args.app, "ChatSphere/lib/chatsphere.jar")
        archive.write(base / "docs/VERIFY.md", "ChatSphere/START-HERE.md")
        archive.writestr("ChatSphere/demo-note.txt", "ChatSphere LAN file transfer test.\n")
        for jar in dependencies:
            if jar in javafx:
                archive.write(jar, f"ChatSphere/lib/javafx/{jar.name}")
            elif jar.name.startswith("sqlite-jdbc-"):
                archive.writestr(f"ChatSphere/lib/{jar.name}", sqlite_for_platform(jar, os_name, architecture))
            elif not jar.name.startswith("javafx-"):
                archive.write(jar, f"ChatSphere/lib/{jar.name}")
        platform_options = '--module-path "lib/javafx" --add-modules javafx.controls,javafx.fxml '
        if classifier == "win":
            for name, main, options in (("server", server, ""), ("client", client, platform_options)):
                add_script(archive, f"start-{name}.cmd",
                           '@echo off\r\ncd /d "%~dp0"\r\n'
                           f'java {options}-cp "lib/*" {main} %*\r\n'
                           'if errorlevel 1 pause\r\n')
        else:
            for name, main, options in (("server", server, ""), ("client", client, platform_options)):
                add_script(archive, f"start-{name}.sh",
                           '#!/bin/sh\nset -eu\ncd "$(dirname "$0")"\n'
                           f'exec java {options}-cp "lib/*" {main} "$@"\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=PLATFORMS)
    parser.add_argument("app", type=Path)
    parser.add_argument("dependencies", type=Path)
    parser.add_argument("output", type=Path)
    package(parser.parse_args())


if __name__ == "__main__":
    main()
