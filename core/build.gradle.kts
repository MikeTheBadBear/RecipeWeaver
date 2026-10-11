val jdbcDrivers = configurations.create("jdbcDrivers")

dependencies {
    implementation(project(":api"))
    implementation(project(":adapters"))
    compileOnly("org.spigotmc:spigot-api:1.20.6-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:3.93.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testRuntimeOnly(files(jdbcDrivers))
    add(jdbcDrivers.name, "org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
    add(jdbcDrivers.name, "com.h2database:h2:2.5.250") { isTransitive = false }
    add(jdbcDrivers.name, "com.mysql:mysql-connector-j:26.7.0") { isTransitive = false }
    add(jdbcDrivers.name, "org.mariadb.jdbc:mariadb-java-client:3.5.10") { isTransitive = false }
    add(jdbcDrivers.name, "org.postgresql:postgresql:42.7.13") { isTransitive = false }
    add(jdbcDrivers.name, "com.microsoft.sqlserver:mssql-jdbc:13.6.0.jre11") { isTransitive = false }
    add(jdbcDrivers.name, "com.oracle.database.jdbc:ojdbc11:23.26.3.0.0") { isTransitive = false }
    add(jdbcDrivers.name, "org.slf4j:slf4j-api:2.0.17") { isTransitive = false }
}

tasks.processResources {
    inputs.property("pluginVersion", project.version.toString())
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val apiJar = project(":api").tasks.named<Jar>("jar")
val adaptersJar = project(":adapters").tasks.named<Jar>("jar")

tasks.jar {
    archiveBaseName.set("RecipeWeaver")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("**/*.SF", "**/*.RSA", "**/*.DSA", "**/INDEX.LIST")
    dependsOn(apiJar, adaptersJar)
    from({ zipTree(apiJar.get().archiveFile.get().asFile) })
    from({ zipTree(adaptersJar.get().archiveFile.get().asFile) })
    from({ jdbcDrivers.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/INDEX.LIST")
    }
}
