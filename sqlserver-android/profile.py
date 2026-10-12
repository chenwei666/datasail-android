#!/usr/bin/env python3
"""Verified source overlays only. Never suppress R8 errors or rewrite bytecode."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
LOCK = json.loads((ROOT / "upstream.lock.json").read_text())
PREFIX = "com/microsoft/sqlserver/jdbc/"

def digest(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise SystemExit(message)

def prepare(binary, destination):
    source = ROOT / "upstream/mssql-jdbc-13.6.0.jre8-sources.jar"
    require(digest(source.read_bytes()) == LOCK["sourceSha256"], "Upstream source hash mismatch")
    require(digest(binary.read_bytes()) == LOCK["binarySha256"], "Upstream binary hash mismatch")
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    with zipfile.ZipFile(source) as archive:
        for name in LOCK["sources"]:
            path = destination / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(archive.read(name).decode().replace("\r\n", "\n"))
    subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-i", str(ROOT / "patches/android-platform.patch")], cwd=destination, check=True)
    for name, expected in LOCK["sources"].items():
        require(digest((destination / name).read_bytes()) == expected, "Patched source mismatch: " + name)

def verify(binary, profile):
    require(digest(binary.read_bytes()) == LOCK["binarySha256"], "Upstream binary hash mismatch")
    replaced = {PREFIX + name + ".class" for name in LOCK["classes"]}
    signatures = {"META-INF/MANIFEST.MF", "META-INF/MSFTSIG.SF", "META-INF/MSFTSIG.RSA"}
    with zipfile.ZipFile(binary) as original, zipfile.ZipFile(profile) as built:
        old = {n for n in original.namelist() if not n.endswith("/")}
        new = {n for n in built.namelist() if not n.endswith("/")}
        require(len(built.namelist()) == len(set(built.namelist())), "Duplicate JAR entries")
        require(new == (old - signatures) | {"META-INF/MANIFEST.MF", "META-INF/LICENSE", "META-INF/datasail-profile.json"}, "Unexpected profile entries")
        for name in old - signatures - replaced:
            require(original.read(name) == built.read(name), "Unexpected upstream modification: " + name)
        forbidden = [b"java/lang/management/", b"javax/naming/", b"javax/xml/stream/", b"javax/xml/transform/stax/", b"org/bouncycastle/"]
        for name in replaced:
            require(name in new, "Missing replacement: " + name)
            require(not any(ref in built.read(name) for ref in forbidden), "Unsupported reference remains: " + name)
        require(json.loads(built.read("META-INF/datasail-profile.json")) == LOCK, "Profile provenance mismatch")
    print("Profile verified: 5 source-compiled classes; all other upstream entries unchanged; patched classes have no JMX/JNDI/StAX/BouncyCastle references.")

if __name__ == "__main__":
    mode, binary, destination = sys.argv[1:]
    {"prepare": prepare, "verify": verify}[mode](Path(binary), Path(destination))
