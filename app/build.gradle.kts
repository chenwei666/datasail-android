plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    sourceSets.getByName("androidTest").java.srcDir("../core/src/fixture/kotlin")
    namespace = "com.example.datasail"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.example.datasail"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notClass"] = "com.example.datasail.SqlServerDeviceTest,com.example.datasail.JdbcWorkspaceDeviceTest,com.example.datasail.UpgradeTest,com.example.datasail.KeyValueDeviceTest,com.example.datasail.MongoDeviceTest,com.example.datasail.MongoUiTest,com.example.datasail.SearchDeviceTest,com.example.datasail.SearchUiTest,com.example.datasail.DiscoveryUiTest"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/native-image/**") }
    lint { abortOnError = true; warningsAsErrors = true; lintConfig = rootProject.file("lint.xml") }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
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
    androidTestImplementation(libs.mongodb) { exclude(group = "org.mongodb", module = "bson-record-codec") }
    androidTestImplementation(project(path = ":sqlserver-android", configuration = "profile"))
}
