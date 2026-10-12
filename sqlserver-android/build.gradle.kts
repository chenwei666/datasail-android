plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

val upstream by configurations.creating { isTransitive = false }
dependencies {
    upstream(libs.mssql)
    compileOnly(libs.mssql)
    testImplementation(libs.junit)
}
val generated = layout.buildDirectory.dir("generated/profile")
val prepareProfile by tasks.registering(Exec::class) {
    inputs.files("profile.py", "upstream.lock.json", "patches/android-platform.patch", "upstream/mssql-jdbc-13.6.0.jre8-sources.jar", upstream)
    outputs.dir(generated)
    doFirst { commandLine("python3", "profile.py", "prepare", upstream.singleFile, generated.get().asFile) }
}
sourceSets.main { java.srcDir(generated) }
tasks.compileJava { dependsOn(prepareProfile) }
tasks.jar {
    archiveBaseName.set("mssql-jdbc-android")
    archiveVersion.set("13.6.0.jre8-datasail.1")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({ zipTree(upstream.singleFile) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/MSFTSIG.SF", "META-INF/MSFTSIG.RSA")
        listOf("MaxResultBufferParser", "SQLServerCertificateUtils", "SQLServerSQLXML", "ByteArrayOutputStreamToInputStream", "SQLServerEntityResolver").forEach {
            exclude("com/microsoft/sqlserver/jdbc/$it.class")
        }
    }
    from("upstream.lock.json") { into("META-INF"); rename { "datasail-profile.json" } }
    from(rootProject.file("docs/licenses/mssql-jdbc-LICENSE.txt")) { into("META-INF"); rename { "LICENSE" } }
    manifest { attributes("Implementation-Title" to "DataSail SQL Server Android profile", "Implementation-Version" to "13.6.0.jre8-datasail.1") }
}
val profile by configurations.creating { isCanBeConsumed = true; isCanBeResolved = false }
val verifyProfile by tasks.registering(Exec::class) {
    dependsOn(tasks.jar)
    inputs.files(tasks.jar, upstream, "profile.py", "upstream.lock.json")
    doFirst { commandLine("python3", "profile.py", "verify", upstream.singleFile, tasks.jar.get().archiveFile.get().asFile) }
}
dependencies { testImplementation(files(tasks.jar)) }
tasks.test { dependsOn(verifyProfile); useJUnit(); testLogging { events("passed", "failed", "skipped") } }
tasks.check { dependsOn(verifyProfile) }
artifacts { add(profile.name, tasks.jar) { builtBy(verifyProfile) } }
