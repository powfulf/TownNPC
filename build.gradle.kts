plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
}

group = "id.gaffa"
version = "1.0.0"
description = "Packet-based walking NPC bots for Paper"


repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:all,-processing")
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
    jar {
        archiveFileName.set("townnpc-${project.version}.jar")
    }
}
