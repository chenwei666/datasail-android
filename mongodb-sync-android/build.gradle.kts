plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
val upstream by configurations.creating { isTransitive = false }
dependencies {
    upstream("org.mongodb:mongodb-driver-sync:5.6.5")
    compileOnly("org.mongodb:mongodb-driver-sync:5.6.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.bson)
    compileOnly(libs.jsr305)
    testRuntimeOnly(libs.slf4j.nop)
}
val profileLock = groovy.json.JsonSlurper().parse(file("upstream.lock.json")) as Map<*, *>
val generated = layout.buildDirectory.dir("generated/profile")
val prepareProfile by tasks.registering(Exec::class) {
    inputs.files("upstream.lock.json", "patches/android.patch", fileTree("upstream"), upstream, rootProject.file("scripts/android-driver-profile.py"))
    outputs.dir(generated)
    doFirst { commandLine("python3", rootProject.file("scripts/android-driver-profile.py"), "prepare", upstream.singleFile, generated.get().asFile) }
}
sourceSets.main { java.srcDir(generated) }
tasks.compileJava { dependsOn(prepareProfile) }
tasks.jar {
    inputs.property("profileMetadataName", "datasail-${project.name}-profile.json")
    archiveBaseName.set("mongodb-driver-sync-android")
    archiveVersion.set(profileLock["profile"] as String)
    isPreserveFileTimestamps = false; isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({ zipTree(upstream.singleFile) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        (profileLock["replacedEntries"] as List<*>).forEach { exclude(it as String) }
    }
    from("upstream.lock.json") { into("META-INF"); rename { "datasail-${project.name}-profile.json" } }
    manifest { attributes("Implementation-Title" to "DataSail mongodb-driver-sync Android profile", "Implementation-Version" to profileLock["profile"]) }
}
val profile by configurations.creating { isCanBeConsumed = true; isCanBeResolved = false }
val verifyProfile by tasks.registering(Exec::class) {
    dependsOn(tasks.jar)
    inputs.files(tasks.jar, upstream, "upstream.lock.json", rootProject.file("scripts/android-driver-profile.py"))
    doFirst { commandLine("python3", rootProject.file("scripts/android-driver-profile.py"), "verify", upstream.singleFile, tasks.jar.get().archiveFile.get().asFile) }
}
dependencies { testImplementation(files(tasks.jar)) }
tasks.test { dependsOn(verifyProfile); useJUnit(); testLogging { events("passed", "failed", "skipped") } }
tasks.check { dependsOn(verifyProfile) }
artifacts { add(profile.name, tasks.jar) { builtBy(verifyProfile) } }
