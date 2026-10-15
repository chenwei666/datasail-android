plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
dependencies {
    implementation(project(path = ":sqlserver-android", configuration = "profile"))
    implementation(project(path = ":mariadb-android", configuration = "profile"))
    implementation(project(path = ":postgresql-android", configuration = "profile"))
    implementation(project(path = ":mongodb-core-android", configuration = "profile"))
    implementation(project(path = ":mongodb-sync-android", configuration = "profile"))
    implementation(libs.bson)
    implementation(libs.antlr.runtime)
    implementation(libs.slf4j.nop)
    implementation(libs.jsr305)
    implementation("org.checkerframework:checker-qual:3.55.1")
    testImplementation(libs.junit)
}
tasks.test { useJUnit(); testLogging { events("passed", "skipped", "failed") } }

val integrationTest by sourceSets.creating
kotlin.sourceSets.getByName("integrationTest").kotlin.srcDir("src/fixture/kotlin")
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies { add(integrationTest.implementationConfigurationName, sourceSets.main.get().output) }
tasks.register<Test>("integrationTest") {
    description = "Opt-in authorized synthetic database tests; fails if local fixture configuration is missing."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    exclude("**/UntrustedCertificateTest*", "**/JdbcWorkspaceIntegrationTest*", "**/KeyValueIntegrationTest*", "**/MongoIntegrationTest*", "**/SearchIntegrationTest*")
    val fixture = providers.environmentVariable("DATASAIL_TEST_CONFIG")
    val trust = providers.environmentVariable("DATASAIL_TEST_TRUSTSTORE")
    doFirst {
        require(fixture.isPresent && trust.isPresent) { "Run scripts/test-mysql-local.sh or provide authorized fixture configuration." }
        systemProperty("datasail.test.config", fixture.get())
        systemProperty("javax.net.ssl.trustStore", trust.get())
        systemProperty("javax.net.ssl.trustStorePassword", "changeit")
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}
tasks.register<Test>("untrustedTlsTest") {
    description = "Real-server negative TLS test using the stock JDK CA store, without the fixture CA."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/UntrustedCertificateTest*")
    doFirst {
        val config = providers.environmentVariable("DATASAIL_TEST_CONFIG").get()
        systemProperty("datasail.test.config", config)
        systemProperty("javax.net.ssl.trustStore", System.getProperty("java.home") + "/lib/security/cacerts")
        systemProperty("javax.net.ssl.trustStorePassword", "changeit")
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}

tasks.named<Test>("integrationTest") { exclude("**/SqlServerIntegrationTest*", "**/SqlServerAutoCertificateIntegrationTest*") }
tasks.register<Test>("sqlServerTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/SqlServerIntegrationTest*")
    doFirst {
        systemProperty("datasail.sqlserver.config", providers.environmentVariable("DATASAIL_SQLSERVER_CONFIG").get())
        systemProperty("datasail.sqlserver.trust", providers.environmentVariable("DATASAIL_SQLSERVER_TRUSTSTORE").get())
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}

// Build-time inspection only; ASM is never a dependency of the APK.
val driverAudit by sourceSets.creating
val auditOriginal by configurations.creating { isTransitive = false }
dependencies {
    auditOriginal(libs.mssql)
    add(driverAudit.implementationConfigurationName, libs.asm)
    add(driverAudit.implementationConfigurationName, libs.asm.tree)
}
tasks.register<JavaExec>("driverCompatibilityReport") {
    dependsOn(configurations.runtimeClasspath)
    group = "verification"
    description = "Map R8 missing types to driver methods without modifying or suppressing them."
    classpath = driverAudit.runtimeClasspath
    mainClass.set("com.example.datasail.audit.DriverAudit")
    doFirst {
        // Use a stable baseline to compare the same missing types before and after adaptation.
        val evidence = rootProject.file("docs/evidence/v021/release-missing-rules.txt")
        args = listOf(evidence.absolutePath, layout.buildDirectory.file("reports/driver-audit.txt").get().asFile.absolutePath) +
            (configurations.runtimeClasspath.get().files.filter { it.name.startsWith("mssql-jdbc-") || it.name.startsWith("mariadb-java-client-") } + auditOriginal.files).map { it.absolutePath }
    }
}

tasks.register<Test>("jdbcWorkspaceTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/JdbcWorkspaceIntegrationTest*")
    doFirst {
        systemProperty("datasail.test.config", providers.environmentVariable("DATASAIL_TEST_CONFIG").get())
        systemProperty("datasail.test.trust", providers.environmentVariable("DATASAIL_TEST_TRUSTSTORE").get())
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}

 tasks.register<Test>("keyValueTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/KeyValueIntegrationTest*")
    doFirst {
        systemProperty("datasail.test.config", providers.environmentVariable("DATASAIL_TEST_CONFIG").get())
        systemProperty("datasail.test.trust", providers.environmentVariable("DATASAIL_TEST_TRUSTSTORE").get())
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}

 tasks.register<Test>("mongoTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/MongoIntegrationTest*")
    doFirst {
        systemProperty("datasail.test.config", providers.environmentVariable("DATASAIL_TEST_CONFIG").get())
        systemProperty("datasail.test.trust", providers.environmentVariable("DATASAIL_TEST_TRUSTSTORE").get())
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("searchTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/SearchIntegrationTest*")
    doFirst {
        val repeats = providers.environmentVariable("DATASAIL_SEARCH_READ_REPEATS").orElse("1").get().toInt()
        require(repeats in 1..10)
        systemProperty("datasail.search.readRepeats", repeats)
        systemProperty("datasail.test.config", providers.environmentVariable("DATASAIL_TEST_CONFIG").get())
        systemProperty("datasail.test.trust", providers.environmentVariable("DATASAIL_TEST_TRUSTSTORE").get())
    }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}

// SQL Server without a configured certificate: reproduces the IP + self-signed user setup.
tasks.register<Test>("sqlServerAutoCertificateTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    include("**/SqlServerAutoCertificateIntegrationTest*")
    doFirst { systemProperty("datasail.sqlserver.config", providers.environmentVariable("DATASAIL_SQLSERVER_CONFIG").get()) }
    testLogging { events("passed", "skipped", "failed") }
    outputs.upToDateWhen { false }
}
