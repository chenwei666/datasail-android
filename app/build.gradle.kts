import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    val signingDirectory = providers.environmentVariable("DATASAIL_SIGNING_DIR").orNull
    if (signingDirectory != null) {
        signingConfigs.create("production") {
            val directory = file(signingDirectory)
            require(directory.isAbsolute && !directory.canonicalPath.startsWith(rootDir.canonicalPath + "/")) {
                "Release signing material must be stored outside the source repository."
            }
            storeFile = directory.resolve("datasail-release.p12")
            storePassword = directory.resolve("store-password.txt").readText().trim()
            keyPassword = storePassword
            keyAlias = "datasail-release"
            storeType = "PKCS12"
        }
    }
    testBuildType = providers.gradleProperty("datasail.testBuildType").getOrElse("debug")
    sourceSets.getByName("androidTest").java.srcDir("../core/src/fixture/kotlin")
    namespace = "com.example.datasail"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.example.datasail"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.14.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notClass"] = "com.example.datasail.SqlServerDeviceTest,com.example.datasail.JdbcWorkspaceDeviceTest,com.example.datasail.UpgradeTest,com.example.datasail.UpgradeStateSeedTest,com.example.datasail.UpgradeStateVerifyTest,com.example.datasail.KeyValueDeviceTest,com.example.datasail.MongoDeviceTest,com.example.datasail.MongoUiTest,com.example.datasail.SearchDeviceTest,com.example.datasail.SearchUiTest,com.example.datasail.DiscoveryUiTest,com.example.datasail.CertificateTrustUiTest,com.example.datasail.SpreadsheetSqlServerUiTest,com.example.datasail.AiServiceUiTest"
    }
    buildTypes {
        release {
            isDebuggable = false
            if (signingDirectory != null) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "release-test-api.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/native-image/**") }
    lint { abortOnError = true; warningsAsErrors = true; lintConfig = rootProject.file("lint.xml") }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
// AndroidX Test uses these two compiler annotations. Use their exact upstream
// sources only in the test APK; the full annotation JAR also references javac APIs.
val verifyTestAnnotationSources by tasks.registering {
    val lockFile = file("src/androidTest/upstream-annotations.json")
    inputs.file(lockFile)
    inputs.files(fileTree("src/androidTest/java/com/google/errorprone/annotations"))
    doLast {
        val lock = groovy.json.JsonSlurper().parse(lockFile) as Map<*, *>
        (lock["files"] as Map<*, *>).forEach { (path, expected) ->
            val bytes = file("src/androidTest/java/$path").readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Upstream test annotation changed: $path" }
        }
    }
}
tasks.matching { it.name == "preReleaseAndroidTestBuild" || it.name == "preDebugAndroidTestBuild" }
    .configureEach { dependsOn(verifyTestAnnotationSources) }
dependencies {
    runtimeOnly(project(":sasl-android"))
    implementation(libs.conscrypt)
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.bson)
    androidTestImplementation(project(path = ":mongodb-core-android", configuration = "profile"))
    androidTestImplementation(project(path = ":mongodb-sync-android", configuration = "profile"))
    androidTestImplementation(project(path = ":sqlserver-android", configuration = "profile"))
}
