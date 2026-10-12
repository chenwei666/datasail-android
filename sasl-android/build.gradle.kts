plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
val verifyUpstream by tasks.registering(Exec::class) {
    inputs.files(fileTree("src"), "upstream.lock.json")
    commandLine("python3", "-c", """
import hashlib,json,pathlib
lock=json.loads(pathlib.Path('upstream.lock.json').read_text())
for name,expected in lock['sha256'].items():
    assert hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()==expected,name
print('Apache Harmony SASL API sources and notices match pinned upstream bytes.')
""")
}
tasks.compileJava { dependsOn(verifyUpstream) }
tasks.check { dependsOn(verifyUpstream) }
tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
