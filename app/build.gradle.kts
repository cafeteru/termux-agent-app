plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.termux.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.termux.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.github.mwiede:jsch:0.2.21")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
}

tasks.withType<Test>().configureEach {
    val isLinuxArm64 = System.getProperty("os.name").equals("Linux", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("aarch64", "arm64")
    if (isLinuxArm64) {
        // shortcut: Robolectric's conscrypt/nativeruntime lacks linux-aarch64 binaries; runs on x86_64 CI
        exclude("**/MainActivityE2ETest.class")
    }
}
