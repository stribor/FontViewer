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
        changeNotes = "Hide unavailable copy actions in the context menu. Keep Copy Glyph ID and Inspect Glyph available for every glyph."
    }
}
val regressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.stribor.fontpreview.RegressionChecksKt"
    args(layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
tasks.check { dependsOn(regressionTest) }
