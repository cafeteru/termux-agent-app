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
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

afterEvaluate {
    val testDebugUnitTest = tasks.named<Test>("testDebugUnitTest")

    testDebugUnitTest.configure {
        exclude("**/*E2ETest.class")
        exclude("**/*IntegrationTest.class")
        exclude("**/*ForegroundServiceTest.class")
    }

    tasks.register<Test>("testDebugE2E") {
        description = "Runs Robolectric and Compose end-to-end tests."
        group = "verification"
        testClassesDirs = testDebugUnitTest.get().testClassesDirs
        classpath = testDebugUnitTest.get().classpath
        include("**/*E2ETest.class")
        include("**/*IntegrationTest.class")
        include("**/*ForegroundServiceTest.class")
    }

    tasks.register("testDebugAll") {
        description = "Runs unit tests and end-to-end tests."
        group = "verification"
        dependsOn(testDebugUnitTest, "testDebugE2E")
    }
}
