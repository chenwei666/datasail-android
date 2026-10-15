#!/usr/bin/env python3
"""Check final DEX type references against the pre-adaptation missing-class evidence.

This complements R8 and device tests; it is not a proof of all runtime behavior.
"""
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

def dex_symbols(data):
    def integer(offset):
        return struct.unpack_from("<I", data, offset)[0]
    if not data.startswith(b"dex\n"):
        raise ValueError("Invalid DEX header")
    strings = []
    for i in range(integer(56)):
        offset = integer(integer(60) + i * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", "replace"))
    types = [strings[integer(integer(68) + i * 4)] for i in range(integer(64))]
    defined = {types[integer(integer(100) + i * 32)] for i in range(integer(96))}
    fields, methods = set(), set()
    for size_offset, table_offset, target in [(80, 84, fields), (88, 92, methods)]:
        for i in range(integer(size_offset)):
            owner, _, name = struct.unpack_from("<HHI", data, integer(table_offset) + i * 8)
            target.add((types[owner], strings[name]))
    return set(types), defined, fields, methods

def dex_types(data):
    return dex_symbols(data)[0]

def reflection_gaps(defined, fields, methods):
    resource = "Lcom/microsoft/sqlserver/jdbc/SQLServerResource;"
    builder = "Lorg/mariadb/jdbc/Configuration$Builder;"
    required = {"user", "password", "sslMode", "tlsSocketType", "connectTimeout", "socketTimeout",
                "allowLocalInfile", "allowMultiQueries", "allowPublicKeyRetrieval", "useServerPrepStmts"}
    missing = ["class " + name for name in (resource, builder) if name not in defined]
    missing += ["field " + name for name in sorted(required) if (builder, name) not in fields]
    missing += ["method " + name for name in sorted(required) if (builder, name) not in methods]
    return missing

def verify(apk_path, baseline_path):
    baseline = json.loads(baseline_path.read_text())["classes"]
    forbidden = {"L" + item.replace(".", "/") + ";" for item in baseline}
    with zipfile.ZipFile(apk_path) as apk:
        dex_files = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if not dex_files:
            raise ValueError("APK contains no DEX files")
        symbols = [dex_symbols(apk.read(name)) for name in dex_files]
        types, defined, fields, methods = [set().union(*(s[index] for s in symbols)) for index in range(4)]
    return {"apkSha256": hashlib.sha256(apk_path.read_bytes()).hexdigest(),
            "baselineCount": len(forbidden), "dexFiles": dex_files,
            "remainingMissingTypeReferences": sorted(forbidden & types),
            "missingRequiredReflectionSymbols": reflection_gaps(defined, fields, methods)}

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify-release-dex.py APK")
    result = verify(Path(sys.argv[1]), Path(__file__).resolve().parent.parent /
                    "docs/evidence/v0140/r8-missing-baseline.json")
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result["remainingMissingTypeReferences"] or result["missingRequiredReflectionSymbols"]))
