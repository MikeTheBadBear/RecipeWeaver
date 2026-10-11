plugins {
    base
}

group = "dev.recipeweaver"
version = "3.7"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<Jar>().configureEach {
        // Windows sync can leave conflict copies beside compiled classes. They are
        // not Java types and must never become installable or smoke JAR entries.
        exclude { it.name.endsWith(".class") && it.name.contains(" ") }
    }
}

private fun verifyProductionJar(candidate: java.io.File, expectedVersion: String) {
    check(candidate.isFile) { "Missing production JAR: $candidate" }
    java.util.zip.ZipFile(candidate).use { zip ->
        val pluginEntry = zip.getEntry("plugin.yml") ?: error("Missing plugin.yml")
        val metadata = zip.getInputStream(pluginEntry).bufferedReader().use { it.readText() }
        check(metadata.contains("version: '" + expectedVersion + "'")) {
            "Packaged plugin.yml has the wrong version"
        }
        check(metadata.contains("author: TM Game Studio")) {
            "Packaged plugin.yml has the wrong author"
        }
        check(zip.entries().asSequence().none { it.name.endsWith(".class") && it.name.contains(" ") }) {
            "Production JAR contains a stray or invalid class filename"
        }
        check(zip.entries().asSequence().none { it.name.startsWith("dev/recipeweaver/smoke/") }) {
            "Test-only smoke classes were bundled in the production JAR"
        }
        check(zip.entries().asSequence().none { entry ->
            entry.name.matches(Regex("(?i)^META-INF/[^/]+\\.(SF|RSA|DSA)$"))
                    || entry.name.equals("META-INF/INDEX.LIST", ignoreCase = true)
        }) { "Production JAR contains stale third-party signature metadata" }
        listOf(
            "org/sqlite/JDBC.class",
            "org/h2/Driver.class",
            "com/mysql/cj/jdbc/Driver.class",
            "org/mariadb/jdbc/Driver.class",
            "org/postgresql/Driver.class",
            "com/microsoft/sqlserver/jdbc/SQLServerDriver.class",
            "oracle/jdbc/OracleDriver.class"
        ).forEach { check(zip.getEntry(it) != null) { "Missing bundled JDBC driver class: $it" } }
        val mainClass = zip.getEntry("dev/recipeweaver/core/RecipeWeaverPlugin.class")
            ?: error("Missing plugin main class")
        java.io.DataInputStream(zip.getInputStream(mainClass)).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid class file" }
            input.readUnsignedShort()
            check(input.readUnsignedShort() == 65) { "Production plugin must use Java 21 bytecode" }
        }
    }
}

tasks.register("verifyBuildJar") {
    dependsOn(":core:jar")
    doLast {
        val candidate = project(":core").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        verifyProductionJar(candidate, project.version.toString())
    }
}

tasks.register("releaseJar") {
    dependsOn("verifyBuildJar")
    doLast {
        val candidate = project(":core").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val release = layout.projectDirectory.file(
            "releases/RecipeWeaver-" + project.version + ".jar").asFile
        release.parentFile.mkdirs()
        if (release.exists()) {
            check(java.nio.file.Files.mismatch(candidate.toPath(), release.toPath()) == -1L) {
                "Refusing to replace retained release JAR: $release; bump the version after verification"
            }
        } else {
            java.nio.file.Files.copy(candidate.toPath(), release.toPath())
        }
    }
}

tasks.register("verifyReleaseJar") {
    dependsOn("releaseJar")
    doLast {
        val release = layout.projectDirectory.file(
            "releases/RecipeWeaver-" + project.version + ".jar").asFile
        verifyProductionJar(release, project.version.toString())
    }
}
