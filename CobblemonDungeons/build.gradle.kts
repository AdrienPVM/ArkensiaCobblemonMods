plugins {
    java
    id("fabric-loom") version "1.8-SNAPSHOT"
}

group = "com.sebastian"  // change to your namespace
version = "1.1.147"
base.archivesName.set("cobblemon-dungeons")

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")  // Fabric dependencies
    maven("https://maven.impactdev.net/repository/maven-public/") // Cobblemon Mirror
    maven("https://jitpack.io")
    maven("https://cursemaven.com")
    // Modrinth Maven resolves mod jars as ordinary Gradle dependencies, so a
    // clean clone builds with no manual downloads. Nothing third-party is
    // redistributed by this repository.
    maven("https://api.modrinth.com/maven")
}

dependencies {
    // Minecraft & mappings
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.1:v2")

    // Fabric Loader + API
    modImplementation("net.fabricmc:fabric-loader:0.16.10")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.107.0+1.21.1")

    // Radical Cobblemon Trainers API (CurseForge Project ID: 1152792)
    modImplementation("curse.maven:radical-cobblemon-trainers-api-1152792:7483015")

    // Cobblemon 1.7.1 (fabric), from Modrinth Maven.
    // Pinned by Modrinth version id: the version number "1.7.1" is shared by the
    // Fabric and NeoForge builds, so resolving by number can fetch the wrong jar.
    modImplementation("maven.modrinth:cobblemon:s64m1opn")

    // Kotlin (Required because Cobblemon is written in Kotlin)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")

    // SQL Support
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")
    include("com.zaxxer:HikariCP:5.1.0")
    include("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    // CheeseDash (required dependency for admin dashboard)
    modImplementation(files("../CheeseDash/build/libs/cheesedash-1.0.0.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.compilerArgs.addAll(listOf("--add-exports", "jdk.httpserver/sun.net.httpserver=ALL-UNNAMED"))
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
