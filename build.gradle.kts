import java.util.zip.ZipInputStream

plugins {
    java
    idea
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.anatawa12.forge"

version = "1.2-${property("version")!!}"

base {
    archivesName.set("ForgeGradle")
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
    sourceCompatibility = JavaVersion.VERSION_1_8
}

val gradleStartDev = false

// Создаём source set для GradleStart шаблонов
sourceSets {
    create("templates") {
        java {
            srcDir("src/templates/java")
        }
    }
}

// Настройка IDEA для корректного отображения
idea {
    module {
        sourceDirs = sourceDirs + file("src/templates/java")
    }
}

// Синхронизируем изменения из src/templates/java в src/main/resources
val syncTemplatesToResources by tasks.creating(Sync::class) {
    from("src/templates/java")
    into("src/main/resources")
    include("**/*.java")
}

// Настраиваем задачи для templates source set
tasks.named<JavaCompile>("compileTemplatesJava") {
    // Компилируем только для IDE, не включаем в основной jar
    destinationDirectory.set(layout.buildDirectory.dir("templates-classes"))
}

// Автоматически синхронизируем перед processResources
tasks.named("processResources") {
    dependsOn(syncTemplatesToResources)
}

// Также добавляем зависимость для sourcesJar (создаётся позже)
tasks.whenTaskAdded {
    if (name == "sourcesJar") {
        dependsOn(syncTemplatesToResources)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net") {
        name = "forge"
    }
    maven("https://libraries.minecraft.net/") {
        name = "mojang"
    }
}

val jar by tasks.getting(Jar::class) {
    // Исключаем GradleStart классы из jar (они должны быть только в resources)
    exclude("GradleStart*.class")
    exclude("net/minecraftforge/gradle/GradleStartCommon*.class")
    exclude("net/minecraftforge/gradle/OldPropertyMapSerializer*.class")
    exclude("net/minecraftforge/gradle/tweakers/**/*.class")

    // Стратегия для дубликатов (если файлы уже есть из processResources)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    manifest {
        attributes(mapOf(
            "version" to project.version,
            "javaCompliance" to project.java.targetCompatibility,
            "group" to project.group,
            "Implementation-Version" to project.version
        ))
    }
}

dependencies {
    implementation(gradleApi())

    // moved to the beginning to be the overrider
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("com.google.guava:guava:33.6.0-jre")

    implementation("com.opencsv:opencsv:5.12.0") // reading CSVs.. also used by SpecialSource
    implementation("com.cloudbees:diff4j:1.3") // for difing and patching
    implementation("com.github.abrarsyed.jastyle:jAstyle:1.3") // formatting

    implementation("com.github.jponge:lzma-java:1.3") // replaces the LZMA binary
    implementation("com.nothome:javaxdelta:2.0.1") // GDIFF implementation for BinPatches
    implementation("com.google.code.gson:gson:2.14.0") // Used instead of Argo for building changelog.
    implementation("org.glavo:pack200:0.3.0") // Pack200 support for Java 14+

    implementation("net.md-5:SpecialSource:1.11.6") // deobf and reobs

    // mcp stuff
    implementation("de.oceanlabs.mcp:RetroGuard:3.6.6")
    implementation("de.oceanlabs.mcp:mcinjector:3.2-SNAPSHOT")
    implementation("net.minecraftforge:Srg2Source:4.2.7")

    // pin jdt deps
    // locked means locked due to java 8
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.26.0") // locked
    implementation("org.eclipse.platform:org.eclipse.core.commands:3.9.800") // locked
    implementation("org.eclipse.platform:org.eclipse.core.contenttype:3.7.1000") // locked
    implementation("org.eclipse.platform:org.eclipse.core.expressions:3.7.100") // locked
    implementation("org.eclipse.platform:org.eclipse.core.filesystem:1.7.700") // locked
    implementation("org.eclipse.platform:org.eclipse.core.jobs:3.11.0") // locked
    implementation("org.eclipse.platform:org.eclipse.core.resources:3.14.0") // locked
    implementation("org.eclipse.platform:org.eclipse.core.runtime:3.22.0") // locked
    implementation("org.eclipse.platform:org.eclipse.equinox.app:1.5.100") // locked
    implementation("org.eclipse.platform:org.eclipse.equinox.common:3.14.100") // locked
    implementation("org.eclipse.platform:org.eclipse.equinox.preferences:3.9.100") // locked
    implementation("org.eclipse.platform:org.eclipse.equinox.registry:3.10.200") // locked
    implementation("org.eclipse.platform:org.eclipse.osgi:3.18.400")
    implementation("org.eclipse.platform:org.eclipse.text:3.11.0") // locked
    implementation("org.osgi:org.osgi.service.prefs:1.1.2")
    implementation("org.osgi:osgi.annotation:8.1.0")

    //Stuff used in the GradleStart classes
    if (gradleStartDev) {
        compileOnly("com.mojang:authlib:1.5.16")
        compileOnly("net.minecraft:launchwrapper:1.11")
    }

    // Зависимости для templates source set (только для IDE индексации)
    "templatesCompileOnly"("com.mojang:authlib:1.5.16")
    "templatesCompileOnly"("net.minecraft:launchwrapper:1.11")
    "templatesCompileOnly"("com.google.guava:guava:31.1-jre")
    "templatesCompileOnly"("com.google.code.gson:gson:2.10.1")
    "templatesCompileOnly"("org.apache.logging.log4j:log4j-api:2.17.1")
    "templatesCompileOnly"("org.apache.logging.log4j:log4j-core:2.17.1")
    "templatesCompileOnly"("net.sf.jopt-simple:jopt-simple:4.6")

}

val compileJava by tasks.getting(JavaCompile::class) {
    options.isDeprecation = true
    //options.compilerArgs += ["-Werror", "-Xlint:unchecked"]
}

val javadoc by tasks.getting(Javadoc::class) {
    // linked javadoc urls.. why not...

    classpath = classpath.filter { !(it.name == "main" && it.parentFile.name == "resources") } 
    val options = options as StandardJavadocDocletOptions
    options.links("https://gradle.org/docs/current/javadoc/")
    options.links("https://guava.dev/releases/18.0/api/docs/")
    options.links("https://asm.ow2.io/javadoc/")
}

java {
    withJavadocJar()
    withSourcesJar()
}

artifacts {
    archives(jar)
}

publishing {
    publications {
        val bintray by this.creating(MavenPublication::class) {
            from(components["java"])
            artifactId = base.archivesName.get()

            pom {
                name.set(project.base.archivesName.get())
                description.set("Gradle plugin for Forge")
                url.set("https://github.com/anatawa12/ForgeGradle-1.2")

                scm {
                    url.set("https://github.com/anatawa12/ForgeGradle-1.2")
                    connection.set("scm:git:git://github.com/anatawa12/ForgeGradle-1.2.git")
                    developerConnection.set("scm:git:git@github.com:anatawa12/ForgeGradle-1.2.git")
                }

                issueManagement {
                    system.set("github")
                    url.set("https://github.com/anatawa12/ForgeGradle-1.2/issues")
                }

                licenses {
                    license {
                        name.set("Lesser GNU Public License, Version 2.1")
                        url.set("https://www.gnu.org/licenses/lgpl-2.1.html")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("AbrarSyed")
                        name.set("Abrar Syed")
                        roles.set(setOf("developer"))
                    }

                    developer {
                        id.set("LexManos")
                        name.set("Lex Manos")
                        roles.set(setOf("developer"))
                    }

                    developer {
                        id.set("anatawa12")
                        name.set("anatawa12")
                        roles.set(setOf("developer"))
                    }

                    developer {
                        id.set("LuxinfineTeam")
                        name.set("LuxinfineTeam")
                        roles.set(setOf("developer"))
                    }
                }
            }
        }
    }
}

gradlePlugin {
    isAutomatedPublishing = false
    plugins {
        create("fml") {
            id = "fml"
            implementationClass = "net.minecraftforge.gradle.user.patch.FmlUserPlugin"
        }
        create("forge") {
            id = "forge"
            implementationClass = "net.minecraftforge.gradle.user.patch.ForgeUserPlugin"
        }
    }
}

// write out version so its convenient for doc deployment
file("build").mkdirs()
file("build/version.txt").writeText("$version")
