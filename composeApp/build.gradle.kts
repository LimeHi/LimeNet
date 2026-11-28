import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionLines = rootProject.file("version.txt").readLines().map(String::trim)
val appVersionName = appVersionLines.getOrNull(0)?.takeIf(String::isNotBlank) ?: "0.1.0"
val appVersionCode = appVersionLines.getOrNull(1)?.toIntOrNull() ?: 1
val releaseStoreFile = providers.gradleProperty("LIMENET_STORE_FILE")
    .orElse(providers.environmentVariable("LIMENET_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("LIMENET_STORE_PASSWORD")
    .orElse(providers.environmentVariable("LIMENET_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("LIMENET_KEY_ALIAS")
    .orElse(providers.environmentVariable("LIMENET_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("LIMENET_KEY_PASSWORD")
    .orElse(providers.environmentVariable("LIMENET_KEY_PASSWORD"))
    .orNull

kotlin {
    jvmToolchain(17)

    jvm("desktop")

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.animation)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.google.zxing:javase:3.5.3")
                implementation("com.github.sarxos:webcam-capture:0.3.12")
            }
        }
        // Android-клиент: Compose живёт в commonMain, здесь только платформенные
        // зависимости (Activity, VPN, уведомления).
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                // Google Play Services Code Scanner transitively requests Fragment 1.0.0;
                // Activity Result APIs require Fragment 1.3.0 or newer.
                implementation("androidx.fragment:fragment-ktx:1.8.5")
                implementation("androidx.core:core-ktx:1.15.0")
                implementation(compose.material3)
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
            }
        }
    }
}

// Папка ресурсов для упаковки: vendor (Xray + Sing-box) и scripts.
// Compose раскладывает содержимое common/ в <app>/resources в образе.
val appResourcesDir = layout.buildDirectory.dir("appResources")

compose.resources {
    publicResClass = true
    generateResClass = auto
    packageOfResClass = "app.limenet.resources"
}

compose.desktop {
    application {
        mainClass = "app.limenet.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "LimeNet"
            packageVersion = appVersionName
            vendor = "Lime Prod"
            description = "LimeNet - VPN client by Lime Prod"
            copyright = "Lime Prod"
            // Controller/UrlTester ходят через java.net.http.HttpClient.
            // Без этого модуля упакованный лаунчер падает с «Failed to launch JVM».
            modules("java.net.http")
            // ядро Xray и скрипты едут в комплекте: приложение ищет их через
            // compose.application.resources.dir (см. Platform.findRoot)
            appResourcesRootDir.set(appResourcesDir)
            windows {
                iconFile.set(project.file("icons/limenet.ico"))
                menuGroup = "Lime Prod"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Не менять между выпусками: этот код связывает MSI-пакеты для обновления
                // поверх установленной версии. При выпуске новой версии увеличивать version.txt.
                upgradeUuid = "8f3d1c64-7a21-4b9e-9f0d-2c6b51a7e4d8"
            }
        }
    }
}

android {
    namespace = "com.limeprod.limenet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.limeprod.limenet"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", appVersionCode.toString())

        // Ядро Xray официально собирается только под arm64 и x86_64.
        // Остальные ABI выкидываем, чтобы APK не ставился на них «наполовину».
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        java.srcDirs("src/androidMain/kotlin")
        res.srcDirs("src/androidMain/res")
        assets.srcDirs("src/androidMain/assets")
        jniLibs.srcDirs("src/androidMain/jniLibs")
    }

    // libxray.so — это исполняемый бинарник Xray, а не JNI-библиотека.
    // Его нужно распаковывать на диск, иначе exec невозможен.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("limenet") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (signingConfigs.names.contains("limenet")) {
                signingConfig = signingConfigs.getByName("limenet")
            }
        }
    }
}

// Compose сам регистрирует задачу `prepareAppResources` (раскладка appResourcesRootDir
// в образ). Наша задача только готовит staging-папку с нужной структурой common/.
val syncAppResources by tasks.registering(Sync::class) {
    into(appResourcesDir)
    from(rootProject.file("vendor")) { into("common/vendor") }
    from(rootProject.file("licenses")) { into("common/licenses") }
    from(rootProject.file("scripts")) { into("common/scripts") }
    from(rootProject.file("version.txt")) { into("common") }
    from(rootProject.file("version.txt"))
}

tasks.matching { it.name == "prepareAppResources" }
    .configureEach { dependsOn(syncAppResources) }

tasks.matching {
    it.name in setOf(
        "createDistributable",
        "packageExe",
        "packageMsi",
        "packageDistributionForCurrentOS",
    )
}.configureEach { dependsOn(syncAppResources) }

// Convenient copies of release installers in one user-facing folder.
tasks.register<Copy>("copyAndroidReleaseToBuilds") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "LimeNet-Android-$appVersionName.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("builds/android"))
}

tasks.register<Copy>("copyWindowsInstallersToBuilds") {
    dependsOn("packageExe", "packageMsi")
    from(layout.buildDirectory.dir("compose/binaries/main/exe")) {
        include("*.exe")
        rename { "LimeNet-Windows-$appVersionName.exe" }
    }
    from(layout.buildDirectory.dir("compose/binaries/main/msi")) {
        include("*.msi")
        rename { "LimeNet-Windows-$appVersionName.msi" }
    }
    into(rootProject.layout.projectDirectory.dir("builds/windows"))
}
