plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
dependencies {
    implementation(libs.mssql)
    implementation(libs.mariadb)
    testImplementation(libs.junit)
}
tasks.test { useJUnit(); testLogging { events("passed", "skipped", "failed") } }

val integrationTest by sourceSets.creating
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies { add(integrationTest.implementationConfigurationName, sourceSets.main.get().output) }
tasks.register<Test>("integrationTest") {
    description = "Opt-in authorized synthetic database tests; fails if local fixture configuration is missing."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    exclude("**/UntrustedCertificateTest*")
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
