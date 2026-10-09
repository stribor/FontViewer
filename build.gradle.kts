import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "org.stribor.fontpreview"
version = providers.gradleProperty("pluginVersion").get()
kotlin { jvmToolchain(21) }
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    testImplementation(kotlin("stdlib"))
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2025.3")
    }
}
intellijPlatform {
    // The plugin has editor controls but no Settings configurable to index.
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion { sinceBuild = "253" }
        changeNotes = "Remember font comparison zoom, Hide unchanged and Highlight outlines across comparisons and IDE restarts."
    }
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            val localVerificationIde = providers.gradleProperty("verificationLocalIde")
            val product = providers.gradleProperty("verificationProduct")
            val release = providers.gradleProperty("verificationVersion").orElse("2025.3")
            when {
                localVerificationIde.isPresent -> local(localVerificationIde.get())
                product.isPresent -> create(IntelliJPlatformType.fromCode(product.get()), release.get())
                else -> {
                    create(IntelliJPlatformType.IntellijIdea, release.get())
                    create(IntelliJPlatformType.CLion, release.get())
                }
            }
        }
    }
}
// CI verifies the exact ZIP produced by its build job, without rebuilding it in each matrix job.
tasks.named<VerifyPluginTask>("verifyPlugin") {
    providers.gradleProperty("verificationArchive").orNull?.let {
        archiveFile = layout.projectDirectory.file(it)
    }
}
val regressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass = "org.stribor.fontpreview.RegressionChecksKt"
    args(layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    val nativeArch = when (System.getProperty("os.arch")) {
        "x86_64", "x64" -> "amd64"
        "arm64" -> "aarch64"
        else -> System.getProperty("os.arch")
    }
    systemProperty("jna.boot.library.path", intellijPlatform.platformPath.resolve("lib/jna/$nativeArch").toString())
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED")
}
tasks.check { dependsOn(regressionTest) }
// The regression suite is a JavaExec entry point, not a JUnit/IDE test harness.
tasks.test { enabled = false }
