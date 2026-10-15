#!/usr/bin/env python3
"""Create an owner-controlled signing identity OUTSIDE the repository; never print secrets.

Back up both output files through a private channel. Never upload this directory to
GitHub or include it in a source/distribution archive. Reuse it for every later release.
"""
import os
from pathlib import Path
import secrets
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: prepare-release-signing.py ABSOLUTE_PRIVATE_DIRECTORY")
destination = Path(sys.argv[1])
repository = Path(__file__).resolve().parent.parent
if not destination.is_absolute() or destination.resolve().is_relative_to(repository):
    raise SystemExit("Choose an absolute path outside the repository")
os.umask(0o077)
destination.mkdir(mode=0o700, parents=True, exist_ok=False)
password = destination / "store-password.txt"
password.write_text(secrets.token_urlsafe(48) + "\n")
subprocess.run([
    "keytool", "-genkeypair", "-keystore", str(destination / "datasail-release.p12"),
    "-storetype", "PKCS12", "-storepass:file", str(password), "-keypass:file", str(password),
    "-alias", "datasail-release", "-keyalg", "RSA", "-keysize", "4096",
    "-sigalg", "SHA256withRSA", "-validity", "10950", "-dname", "CN=DataSail", "-noprompt",
], check=True)
print("Signing identity created. Keep both files private and back them up together.")
