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
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2025.3.1")
    }
}
intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "253" }
        changeNotes = "Remember font comparison zoom, Hide unchanged and Highlight outlines across comparisons and IDE restarts."
    }
}
val regressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.stribor.fontpreview.RegressionChecksKt"
    args(layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED")
}
tasks.check { dependsOn(regressionTest) }
