#!/usr/bin/env python3
"""Retain exact shared APIs used by the separately minified instrumentation APK.

Reads class files without modifying bytecode. The generated rules retain class and
member signatures and overridability, while still allowing name obfuscation. This
avoids retaining entire Kotlin/Compose libraries or turning off Release shrinking.
Input: Gradle-resolved application/test runtime classpaths and compiled test classes.
"""
import io
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

class Reader:
    def __init__(self, data):
        self.data, self.position = data, 0
    def read(self, count):
        value = self.data[self.position:self.position + count]
        if len(value) != count:
            raise ValueError("Truncated class file")
        self.position += count
        return value
    def number(self, count):
        return int.from_bytes(self.read(count), "big")

def parse(data):
    reader = Reader(data)
    if reader.read(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class magic")
    reader.read(4)
    pool, index, count = [None], 1, reader.number(2)
    while index < count:
        tag = reader.number(1)
        if tag == 1:
            value = reader.read(reader.number(2)).decode("utf-8", "replace")
        elif tag in (3, 4):
            value = reader.read(4)
        elif tag in (5, 6):
            value = reader.read(8)
        elif tag in (7, 8, 16, 19, 20):
            value = reader.number(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (reader.number(2), reader.number(2))
        elif tag == 15:
            value = (reader.number(1), reader.number(2))
        else:
            raise ValueError("Unknown constant-pool tag: " + str(tag))
        pool.append((tag, value)); index += 1
        if tag in (5, 6):
            pool.append(None); index += 1
    def utf(index):
        return pool[index][1]
    def class_name(index):
        return utf(pool[index][1]) if index else None
    access = reader.number(2)
    name, parent = class_name(reader.number(2)), class_name(reader.number(2))
    parents = [parent] + [class_name(reader.number(2)) for _ in range(reader.number(2))]
    members = set()
    for kind in ("field", "method"):
        for _ in range(reader.number(2)):
            reader.read(2)
            members.add((kind, utf(reader.number(2)), utf(reader.number(2))))
            for _ in range(reader.number(2)):
                reader.read(2); reader.read(reader.number(4))
    references, member_refs = set(), set()
    for entry in pool:
        if entry is None:
            continue
        tag, value = entry
        if tag == 7:
            references.add(utf(value))
        elif tag == 1:
            references.update(re.findall(r"L([\w$/]+)[;<]", value))
        elif tag in (9, 10, 11):
            owner, pair = value
            member_name, descriptor = pool[pair][1]
            member_refs.add((class_name(owner), "field" if tag == 9 else "method", utf(member_name), utf(descriptor)))
    return name, {"access": access, "parents": parents, "members": members,
                  "references": references, "member_refs": member_refs}

def classes(path):
    path = Path(path)
    if path.is_dir():
        for entry in sorted(path.rglob("*.class")):
            yield parse(entry.read_bytes())
    else:
        with zipfile.ZipFile(path) as archive:
            if path.suffix == ".aar":
                for name in archive.namelist():
                    if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                        with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                            for entry in nested.namelist():
                                if entry.endswith(".class"):
                                    yield parse(nested.read(entry))
            else:
                for name in archive.namelist():
                    if name.endswith(".class") and not name.startswith("META-INF/versions/"):
                        yield parse(archive.read(name))

def types(descriptor):
    result, position = [], 0
    primitives = dict(zip("VZBCSIJFD", ["void", "boolean", "byte", "char", "short", "int", "long", "float", "double"]))
    while position < len(descriptor):
        token = descriptor[position]; position += 1
        if token in "()":
            continue
        dimensions = 0
        while token == "[":
            dimensions += 1; token = descriptor[position]; position += 1
        if token == "L":
            end = descriptor.index(";", position)
            value = descriptor[position:end].replace("/", "."); position = end + 1
        else:
            value = primitives[token]
        result.append(value + "[]" * dimensions)
    return result

def generate(application, tests):
    retained = {}
    definitions = dict(application, **tests)
    def retain(name):
        if name not in application or name in retained:
            return
        retained[name] = set()
        for parent in application[name]["parents"]:
            retain(parent)
    def resolve(owner, member, visited=None):
        visited = set() if visited is None else visited
        if owner not in definitions or owner in visited:
            return None
        visited.add(owner)
        if owner in application and member in application[owner]["members"]:
            return owner
        for parent in definitions[owner]["parents"]:
            found = resolve(parent, member, visited)
            if found:
                return found
        return None
    for test_name, info in tests.items():
        # The app also calls back into test-defined implementations (for example
        # coroutines' MainDispatcherFactory). Their overridden ABI must survive
        # even when no test instruction calls the interface method directly.
        for member in info["members"]:
            if member[0] == "method" and not member[1].startswith("<"):
                declaring = resolve(test_name, member)
                if declaring:
                    retain(declaring)
                    retained[declaring].add(member)
        for name in info["references"]:
            retain(name)
        for owner, kind, name, descriptor in info["member_refs"]:
            retain(owner)
            declaring = resolve(owner, (kind, name, descriptor))
            if declaring:
                retain(declaring)
                retained[declaring].add((kind, name, descriptor))
    lines = ["# Generated by scripts/generate-release-test-api.py from compiled test callers.",
             "# Exact shared ABI boundaries only; names remain obfuscated. Other code retains normal optimization.",
             "# Do not replace these with blanket library keep rules or warning suppression."]
    for owner, members in sorted(retained.items()):
        kind = "interface" if application[owner]["access"] & 0x200 else "class"
        # R8 can resynthesize Kotlin compatibility adapters after package
        # repackaging, producing mappings that a separate test R8 invocation
        # cannot apply to their original static calls. Keep only these adapters'
        # ABI names stable; the remaining shared types still use obfuscated names.
        stable_adapter = owner.endswith("$DefaultImpls") or owner == "kotlin/coroutines/jvm/internal/DebugProbesKt"
        keep = "-keep " if stable_adapter else "-keep,allowobfuscation "
        lines.append(keep + kind + " " + owner.replace("/", ".") + " {")
        for category, name, descriptor in sorted(members):
            parsed = types(descriptor)
            if category == "field":
                declaration = parsed[0] + " " + name
            else:
                declaration = ("" if name == "<init>" else parsed[-1] + " ") + name + "(" + ",".join(parsed[:-1]) + ")"
            lines.append("    " + declaration + ";")
        lines.append("}")
    return "\n".join(lines) + "\n", len(retained), sum(map(len, retained.values()))

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: generate-release-test-api.py CLASSPATH_JSON OUTPUT_PRO")
    root = Path(__file__).resolve().parent.parent
    paths = json.loads(Path(sys.argv[1]).read_text())
    app_paths = paths["releaseRuntimeClasspath"] + [str(root / "app/build/tmp/kotlin-classes/release"),
                  str(root / "app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes")]
    test_paths = sorted(set(paths["releaseAndroidTestRuntimeClasspath"]) - set(paths["releaseRuntimeClasspath"]))
    test_paths += [str(root / "app/build/tmp/kotlin-classes/releaseAndroidTest")]
    application = dict(entry for path in app_paths for entry in classes(path))
    tests = dict(entry for path in test_paths for entry in classes(path))
    output, count, members = generate(application, tests)
    Path(sys.argv[2]).write_text(output)
    print(f"Retained {count} shared types and {members} exact member signatures; names remain obfuscated.")
