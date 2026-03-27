plugins {
    id("org.jetbrains.intellij.platform") version "2.3.0"
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
}

group = "com.reviewplugin"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        pluginVerifier()
        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.reviewplugin"
        name = "Agent Review"
        version = project.version.toString()
        description = """
            Renders file-based code review annotations written by a human or a coding agent.
            No server. No network. The protocol is a directory of JSON files under .review/comments/.
            The plugin reads and writes them. The agent reads and writes them.
            Both parties see each other's comments in real time.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "241"
        }
    }
}

tasks {
    test {
        useJUnit()
    }
    buildSearchableOptions {
        enabled = false
    }
}
