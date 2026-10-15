plugins {
    java
    // Paper-Server (NMS) zum Kompilieren – nötig für die Fake-Spieler
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "de.sessentials"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    // Kompiliert gegen 26.2 – läuft auf 26.2 und 26.3. Enthält Paper-API und Server.
    // Achtung: Die Fake-Spieler nutzen Server-Interna und müssen bei Minecraft-Updates geprüft werden.
    paperweight.paperDevBundle("26.2.build.112-stable")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Optional: Bank als Vault-Economy anbieten
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

tasks {
    compileJava {
        // Paper 26.x benötigt Java 25
        options.release = 25
        options.encoding = "UTF-8"
    }
    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("project" to mapOf("version" to project.version))
        inputs.properties(mapOf("version" to project.version))
        filesMatching("plugin.yml") { expand(props) }
    }
    jar {
        archiveFileName = "SEssentials-${project.version}.jar"
    }
}
