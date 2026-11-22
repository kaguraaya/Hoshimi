import com.acgcompass.build.GenerateThirdPartyNoticesTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    baseline = file("$projectDir/config/detekt-baseline.xml")
    config.setFrom(file("$projectDir/config/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java", "src/androidTest/java"))
}

ktlint {
    android.set(true)
    version.set("1.0.1")
    outputToConsole.set(true)
    baseline.set(file("$projectDir/config/ktlint-baseline.xml"))
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// Bangumi OAuth 应用凭据只从本地文件、Gradle 属性或 CI 环境变量注入，不进入源码或 Git。
val acgLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun oauthCredential(localKey: String, gradleKey: String, environmentKey: String): String = sequenceOf(
    acgLocalProperties.getProperty(localKey),
    providers.gradleProperty(gradleKey).orNull,
    providers.environmentVariable(environmentKey).orNull,
).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val bangumiClientId = oauthCredential("bangumi.clientId", "bangumiClientId", "BANGUMI_CLIENT_ID")
val bangumiClientSecret = oauthCredential("bangumi.clientSecret", "bangumiClientSecret", "BANGUMI_CLIENT_SECRET")
val bangumiOauthProxyBaseUrl = oauthCredential(
    "bangumi.oauthProxyBaseUrl",
    "bangumiOauthProxyBaseUrl",
    "BANGUMI_OAUTH_PROXY_BASE_URL",
)
// The Worker owns the OAuth secret. Proxy builds must not package it in BuildConfig.
val packagedBangumiClientSecret = if (bangumiOauthProxyBaseUrl.isBlank()) bangumiClientSecret else ""
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

// Release signing reads gitignored local configuration. The key and passwords stay outside Git.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseKeystoreFile = keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseSigning =
    releaseKeystoreFile?.isFile == true &&
        listOf("storePassword", "keyAlias", "keyPassword").all { key ->
            !keystoreProperties.getProperty(key).isNullOrBlank()
        }
if (releaseRequested && !hasReleaseSigning) {
    throw GradleException(
        "Release builds require a valid gitignored keystore.properties and external keystore.",
    )
}

android {
    namespace = "com.acgcompass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kaguraaya.hoshimi"
        minSdk = 26
        targetSdk = 34
        versionCode = 95
        versionName = "0.49.0"

        // 可选：用不同的包名 / 版本后缀出包（例如手头没有正式签名、想与已安装的正式版并存时）：
        //   ./gradlew assembleRelease -Phoshimi.applicationIdSuffix=.preview -Phoshimi.versionNameSuffix=-preview
        providers.gradleProperty("hoshimi.applicationIdSuffix").orNull?.let { applicationIdSuffix = it }
        providers.gradleProperty("hoshimi.versionNameSuffix").orNull?.let { versionNameSuffix = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BANGUMI_CLIENT_ID", buildConfigString(bangumiClientId))
        buildConfigField("String", "BANGUMI_CLIENT_SECRET", buildConfigString(packagedBangumiClientSecret))
        buildConfigField("String", "BANGUMI_OAUTH_PROXY_BASE_URL", buildConfigString(bangumiOauthProxyBaseUrl))
    }

    // Room schema export (design: exportSchema=true for migration tests).
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    // Expose exported Room schemas to instrumented migration tests.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 默认不混淆压缩；`-Phoshimi.minify=true` 可开启 R8 + 资源压缩出小体积包（约为原来的五分之一）。
            // 压缩包必须在真机上完整回归后再分发：R8 会裁掉它认为用不到的代码。
            val minify = providers.gradleProperty("hoshimi.minify").orNull == "true"
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Robolectric 界面走查需要真实的 Android 资源（主题 / Manifest）。
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // kotest uses the JUnit5 platform; JUnit4 (Robolectric) tests run through the vintage engine.
            it.useJUnitPlatform()
            // 界面走查截图默认写入 build/outputs/roborazzi。
            it.systemProperty("roborazzi.test.record", "true")
            // Robolectric 运行时要下载 android-all；可用 -Probolectric.repo=<镜像> 换源（受限网络 / CI）。
            providers.gradleProperty("robolectric.repo").orNull?.let { repo ->
                it.systemProperty("robolectric.dependency.repo.url", repo)
            }
            // 口味算法离线评测的筛选项：-Peval.splits=temporal、-Peval.variants=legacy,gateL（不传则全跑）。
            listOf("eval.splits", "eval.variants").forEach { key ->
                providers.gradleProperty(key).orNull?.let { value -> it.systemProperty(key, value) }
            }
        }
    }
}

val generatedLicenseAssets = layout.buildDirectory.dir("generated/thirdPartyNotices/assets")
val generateThirdPartyNotices = tasks.register<GenerateThirdPartyNoticesTask>("generateThirdPartyNotices") {
    outputFile.set(generatedLicenseAssets.map { it.file("third_party_notices.txt") })
    developmentTools.set(
        listOf(
            "开发、构建与测试工具",
            "Gradle 8.9（Apache License 2.0）— https://gradle.org/",
            "Android Gradle Plugin ${libs.versions.agp.get()}（Apache License 2.0）— " +
                "https://developer.android.com/build",
            "Kotlin Gradle、Compose 与 Serialization 插件 ${libs.versions.kotlin.get()}（Apache License 2.0）— " +
                "https://kotlinlang.org/",
            "KSP ${libs.versions.ksp.get()}（Apache License 2.0）— https://github.com/google/ksp",
            "Dagger Hilt Gradle Plugin 与编译器 ${libs.versions.hilt.get()}（Apache License 2.0）— " +
                "https://dagger.dev/hilt/",
            "Room Compiler 与 Room Testing ${libs.versions.room.get()}（Apache License 2.0）— " +
                "https://developer.android.com/jetpack/androidx/releases/room",
            "Detekt ${libs.versions.detekt.get()}（Apache License 2.0）— https://detekt.dev/",
            "Gradle Ktlint Plugin ${libs.versions.ktlintGradle.get()}（MIT License）— " +
                "https://github.com/JLLeitschuh/ktlint-gradle",
            "ktlint 1.0.1（MIT License）— https://github.com/pinterest/ktlint",
            "JUnit ${libs.versions.junit.get()}（Eclipse Public License 1.0）— https://junit.org/junit4/",
            "Kotest ${libs.versions.kotest.get()}（Apache License 2.0）— https://kotest.io/",
            "Kotlin Coroutines Test ${libs.versions.coroutines.get()}（Apache License 2.0）— " +
                "https://github.com/Kotlin/kotlinx.coroutines",
            "MockWebServer ${libs.versions.okhttp.get()}（Apache License 2.0）— " +
                "https://square.github.io/okhttp/",
            "AndroidX Test JUnit ${libs.versions.androidxTestExtJunit.get()}（Apache License 2.0）— " +
                "https://developer.android.com/training/testing/instrumented-tests",
            "Espresso ${libs.versions.espressoCore.get()}（Apache License 2.0）— " +
                "https://developer.android.com/training/testing/espresso",
            "Compose UI Test ${libs.versions.composeBom.get()}（Apache License 2.0）— " +
                "https://developer.android.com/develop/ui/compose/testing",
            "Kotest Property ${libs.versions.kotest.get()}（Apache License 2.0）— " +
                "https://kotest.io/docs/proptest/property-based-testing.html",
            "JUnit Vintage Engine ${libs.versions.junitVintage.get()}（Eclipse Public License 2.0）— " +
                "https://junit.org/junit5/",
            "Robolectric ${libs.versions.robolectric.get()}（MIT License）— https://robolectric.org/",
            "Roborazzi ${libs.versions.roborazzi.get()}（Apache License 2.0）— " +
                "https://github.com/takahirom/roborazzi",
            "",
            "设计参考",
            "界面的表面层级、底栏动效等设计语言参考了 FlClash（GPL-3.0）— https://github.com/chen08209/FlClash，" +
                "本应用没有包含其源代码。",
        ),
    )
}
configurations.configureEach {
    if (name != "releaseRuntimeClasspath") return@configureEach
    val coordinates = incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { artifact ->
            val id = artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
            id?.let { "${it.group}:${it.module}:${it.version}" }
        }.sorted()
    }
    generateThirdPartyNotices.configure {
        runtimeCoordinates.set(coordinates)
    }
}

android.sourceSets.getByName("main").assets.srcDir(generatedLicenseAssets)
tasks.named("preBuild").configure { dependsOn(generateThirdPartyNotices) }

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // 动态开屏使用 androidx core-splashscreen，并与 DayNight 启动底色保持一致。
    implementation(libs.androidx.core.splashscreen)

    // Compose (BOM aligns all Compose artifact versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Async
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Network / serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Images
    implementation(libs.coil.compose)
    // 口味码二维码：本地生成、识别相册里的二维码图片（不需要相机权限）。
    implementation(libs.zxing.core)
    // 顶栏背景模糊（Android 12 起真模糊，更早的系统退回半透明底色）。
    implementation(libs.haze)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Unit testing (JVM) — JUnit4 platform host + kotest (incl. kotest-property)
    testImplementation(libs.junit)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // JVM 界面走查（Robolectric + Compose UI Test + Roborazzi 截图）。
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.vintage.engine)

    // Instrumented / UI tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Room migration testing (MigrationTestHelper) + property generators
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotest.property)
}
