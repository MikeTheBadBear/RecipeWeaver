dependencies {
    compileOnly(project(":api"))
    compileOnly("org.spigotmc:spigot-api:1.20.6-R0.1-SNAPSHOT")
}

tasks.processResources {
    inputs.property("pluginVersion", project.version.toString())
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar { archiveBaseName.set("RecipeWeaverSmoke") }
