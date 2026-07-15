plugins {
    `java-library`
}

group = "io.github.phateio"
version = "0.11.1"

java {
    // Paper 26.2 (and thus its paper-api artifact) requires Java 25, so this
    // plugin targets 25 too. WorldRewild is bound to Paper 26.x by its Moonrise
    // reflection anyway, so there is no older API line to stay compatible with.
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    // Paper API for the Bukkit surface used here (worlds, async chunk load, TPS,
    // commands). Pinned to the running server's build: WorldRewild reflects into
    // Paper's Moonrise chunk system (RegionDataController), which is 26.x-specific,
    // so it targets Paper 26.2 rather than an older stable API line.
    compileOnly("io.papermc.paper:paper-api:26.2.build.40-alpha")

    // Residence API, used only for the optional claim guard when the Residence
    // plugin is installed at runtime (soft-dependency). Compiled against 6.0.0.1
    // from JitPack; the running server has 6.0.1.8, but the tiny surface used here
    // (creation/subzone/area-add/resize events + CuboidArea) is stable across
    // 6.0.x. Non-transitive: we need only Residence's own classes, and pulling its
    // Spigot/CMILib deps would fail to resolve and is unnecessary. Not on the
    // runtime classpath.
    compileOnly("com.github.Zrips:Residence:6.0.0.1") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Lint everything except the noisy [classfile] warnings from the paper-api
    // jar's compile-only JetBrains annotations, which are not on our classpath.
    options.compilerArgs.add("-Xlint:all,-classfile")
}

tasks.processResources {
    // Mirrors Maven-style ${name} / ${version} filtering in plugin.yml.
    val props = mapOf(
        "name" to project.name,
        "version" to project.version,
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
