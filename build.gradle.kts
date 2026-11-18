plugins {
    java
}

group = "cloud.schwnbrg.questbook"
// Die Release-Pipeline setzt die Version aus dem Tag, lokal gilt der Standardwert
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

// Minecraft-Versionen, fuer die je eine Jar entsteht. Der Code ist derselbe, gebaut wird gegen die jeweilige Paper-API.
// Neue Version unterstuetzen: hier eintragen. Bricht dort etwas in der API, schlaegt genau dieser Build fehl.
val mcVersions = listOf("26.2", "26.3")

repositories {
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Die normale Compile-Konfiguration nutzt die aelteste Version, damit die IDE etwas zum Arbeiten hat
dependencies {
    compileOnly("io.papermc.paper:paper-api:${mcVersions.first()}.build.+")
    testImplementation("io.papermc.paper:paper-api:${mcVersions.first()}.build.+")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version, "apiVersion" to mcVersions.first())
    }
}

// Die Standard-Jar entfaellt, es gibt nur die Jars je Minecraft-Version
tasks.jar {
    enabled = false
}

val versionJars = mcVersions.map { mc ->
    val id = mc.replace('.', '_')

    val api = configurations.create("paperApi_$id") {
        isCanBeConsumed = false
        isCanBeResolved = true
        dependencies.add(project.dependencies.create("io.papermc.paper:paper-api:$mc.build.+"))
    }

    val compile = tasks.register<JavaCompile>("compileJava_$id") {
        source(fileTree("src/main/java"))
        // Nur bei echten API-Unterschieden: src/mc<version>/java mit Compat-Implementierungen
        source(fileTree("src/mc$mc/java"))
        classpath = api
        destinationDirectory.set(layout.buildDirectory.dir("classes/java/$id"))
        javaCompiler.set(javaToolchains.compilerFor(java.toolchain))
    }

    val resources = tasks.register<Copy>("processResources_$id") {
        from("src/main/resources")
        into(layout.buildDirectory.dir("resources/$id"))
        filesMatching("plugin.yml") {
            expand("version" to project.version, "apiVersion" to mc)
        }
    }

    tasks.register<Jar>("jar_$id") {
        archiveBaseName.set("questbook")
        archiveClassifier.set("mc$mc")
        from(compile.map { it.destinationDirectory })
        from(resources)
    }
}

tasks.assemble {
    dependsOn(versionJars)
}
