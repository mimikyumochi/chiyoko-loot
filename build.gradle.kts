import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.16-SNAPSHOT"
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
}

fun resolveProperty(name: String, fallback: String): String {
    val prop = project.findProperty(name) ?: return fallback
    return if (prop is Provider<*>) {
        prop.orNull?.toString() ?: fallback
    } else {
        prop.toString()
    }
}

version = resolveProperty("mod_version", "0.0.0")
group = resolveProperty("maven_group", "lgbt.faith")

loom {
    // mixins are kotlin, so the annotation processor can't generate a refmap - remap them from bytecode instead
    mixin {
        useLegacyMixinAp = false
    }

    mods {
        register("chiyoko") {
            sourceSet(sourceSets.main.get())
        }
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    maven("https://maven.terraformersmc.com/releases") {
        name = "TerraformersMC"
        content { includeGroup("com.terraformersmc") }
    }
}

dependencies {
    implementation("com.google.guava:guava:33.0.0-jre")

    minecraft("com.mojang:minecraft:${resolveProperty("minecraft_version", "1.21.11")}")
    mappings("net.fabricmc:yarn:${resolveProperty("yarn_mappings", "1.21.11+build.6")}:v2")

    modImplementation("net.fabricmc:fabric-loader:${resolveProperty("loader_version", "0.19.3")}")

    modImplementation("net.fabricmc.fabric-api:fabric-api:${resolveProperty("fabric_api_version", "0.141.6+1.21.11")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${resolveProperty("fabric_kotlin_version", "1.13.12+kotlin.2.4.0")}")

    // optional - only the api is compiled against, the integration class is never loaded without mod menu
    modCompileOnly("com.terraformersmc:modmenu:${resolveProperty("modmenu_version", "17.0.1")}") { isTransitive = false }
}

tasks.processResources {
    inputs.property("version", version)

    val minecraftVersion = resolveProperty("minecraft_version", "")
    val loaderVersion = resolveProperty("loader_version", "")
    val kotlinLoaderVersion = resolveProperty("fabric_kotlin_version", "")

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to version,
                "minecraft_version" to minecraftVersion,
                "loader_version" to loaderVersion,
                "kotlin_loader_version" to kotlinLoaderVersion
            )
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    withSourcesJar()

    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    inputs.property("projectName", project.name)

    from("LICENSE") {
        rename { "${it}_${project.name}" }
    }
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}