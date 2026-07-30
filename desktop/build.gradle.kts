import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.reload.gradle.ComposeHotRun
import java.util.Properties

plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.conveyor)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.hot.reload)
}

group = rootProject.extra["groupName"].toString()
version = rootProject.extra["versionName"].toString()

val javaVersionEnum: JavaVersion by rootProject.extra

kotlin {
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget = JvmTarget.fromTarget(javaVersionEnum.toString())
                }
            }
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":common"))

                implementation(libs.vaqua)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersionEnum.toString()))
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar> {
    exclude("META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.SF")
}

// ── 自定义 DMG 打包 ────────────────────────────────────────────────
// jpackage 内部调用 hdiutil 时硬编码 -fs HFS+，而 macOS 10.14+ 已
// 弃用 HFS+ 创建，在新系统上直接失败。此 Task 改用 hdiutil create
// -format UDZO（APFS），绕开 jpackage 的 DMG 步骤，稳定可靠。
// 用法：./gradlew :desktop:createDmg
// 可替代 packageDmg：./gradlew :desktop:createDistributable :desktop:createDmg
// ───────────────────────────────────────────────────────────────────
tasks.register<Exec>("createDmg") {
    description = "使用 hdiutil (UDZO/APFS) 创建 DMG，避免 jpackage 的 HFS+ 兼容问题"
    group = "distribution"
    dependsOn("createDistributable")

    val appNameVal = rootProject.extra["appName"].toString()
    val versionNameVal = rootProject.extra["versionName"].toString()
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app/${appNameVal}.app")
    val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg")
    val outputFile = layout.buildDirectory.file("compose/binaries/main/dmg/${appNameVal}-${versionNameVal}.dmg")

    inputs.dir(appDir)
    outputs.file(outputFile)

    doFirst {
        dmgDir.get().asFile.mkdirs()
        outputFile.get().asFile.delete()
    }
    commandLine(
        "hdiutil", "create",
        "-format", "UDZO",
        "-srcfolder", appDir.get().asFile.absolutePath,
        "-volname", appNameVal,
        outputFile.get().asFile.absolutePath,
    )
}

tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("MainKt")
    // Skiko tries to create a lock file in ~/.skiko/, which may fail under
    // sandbox restrictions. Point it at the cached native library directory
    // so it skips the download + lock-file path.
    val skikoDir = file(System.getProperty("user.home") + "/.skiko")
    if (skikoDir.exists()) {
        val osPrefix = when {
            System.getProperty("os.name").lowercase().contains("mac") -> "skiko-macos-"
            System.getProperty("os.name").lowercase().contains("linux") -> "skiko-linux-"
            System.getProperty("os.name").lowercase().contains("windows") -> "skiko-windows-"
            else -> "skiko-macos-"
        }
        val dylibDir = skikoDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(osPrefix) }
            ?.firstOrNull()
        if (dylibDir != null) {
            jvmArgs("-Dskiko.library.path=${dylibDir.absolutePath}")
        }
    }
}

compose.desktop {
    val packageName: String by rootProject.extra
    val appName: String by rootProject.extra

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.reader())
    }

    application {
        buildTypes.release.proguard {
            isEnabled.set(false)
            version.set("7.4.0")
        }

        mainClass = "MainKt"
        
        jvmArgs += listOf(
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
            "-Dorg.slf4j.simpleLogger.showDateTime=true",
            "-Dorg.slf4j.simpleLogger.dateTimeFormat=yyyy-MM-dd HH:mm:ss.SSS",
            "-Dorg.slf4j.simpleLogger.showThreadName=false",
            "-Dorg.slf4j.simpleLogger.cacheOutputStream=false",
            "-Dorg.slf4j.simpleLogger.logFile=${System.getProperty("user.home")}/bifrost_debug.log",
            "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}",
            "-Duser.home=${System.getProperty("user.home")}"
        )
        
        nativeDistributions {
            modules("jdk.crypto.ec")
            modules("java.management")
            modules("jdk.accessibility")
            modules("java.sql")

            this.packageName = packageName

            windows {
                menu = true
                this.console = true

                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                targetFormats(TargetFormat.Exe, TargetFormat.AppImage)
            }

            macOS {
                bundleID = packageName
                iconFile.set(project.file("src/jvmMain/resources/icon.icns"))
                packageVersion = rootProject.extra["versionName"].toString()
                targetFormats(TargetFormat.Pkg)
                this.packageName = appName

                signing {
                    localProperties.getProperty("macosSigningId", null)?.let {
                        sign.set(true)
                        identity.set(it)
                    }
                }

                notarization {
                    localProperties.getProperty("macosNotarizationEmail", null)?.let {
                        appleID.set(it)
                    }
                    localProperties.getProperty("macosNotarizationPassword", null)?.let {
                        password.set(it)
                    }
                    localProperties.getProperty("macosNotarizationTeamId", null)?.let {
                        teamID.set(it)
                    }
                }
            }

            linux {
                modules("jdk.security.auth")
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
                packageVersion = rootProject.extra["versionName"].toString()
                targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            }

            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            this.packageName = appName
        }
    }
}

project.configurations.create("desktopRuntimeClasspath") {
    extendsFrom(project.configurations.findByName("jvmRuntimeClasspath")!!)
}

tasks.named<hydraulic.conveyor.gradle.WriteConveyorConfigTask>("writeConveyorConfig") {
    dependsOn(tasks.named("build"))

    doLast {
        val config = StringBuilder()
        config.appendLine("app.fsname = bifrost")
        config.appendLine("app.display-name = ${project.rootProject.extra["appName"]}")
        config.appendLine("app.rdns-name = ${project.rootProject.extra["packageName"]}")
        destination.get().asFile.appendText(config.toString())
    }
}

dependencies {
    linuxAarch64(libs.compose.linux.arm64)
    linuxAmd64(libs.compose.linux.x64)
    macAarch64(libs.compose.macos.arm64)
    macAarch64(libs.vaqua)
    macAmd64(libs.compose.macos.x64)
    macAmd64(libs.vaqua)
    windowsAarch64(libs.compose.windows.arm64)
    windowsAmd64(libs.compose.windows.x64)
}
