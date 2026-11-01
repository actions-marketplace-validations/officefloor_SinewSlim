import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

// Every module is plain Java 17: the IntelliJ platform's floor, and the study measurer's.
subprojects {
    apply(plugin = "java-library")

    group = "net.officefloor.sinewslim"
    version = rootProject.property("version") as String

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // the search recurses once or twice per level of the statement tree, and an else-if chain
        // nests one level per branch: some methods of the study's corpora need a deep stack
        jvmArgs("-Xss64m")
        // the full parity run reads the export named here (DESIGN.md, Testing); without it only
        // the committed subset is read
        for (name in listOf("sinewslim.parity.full", "sinewslim.livewidth.facts", "sinewslim.livewidth.root")) {
            System.getProperty(name)?.let { systemProperty(name, it) }
        }
        maxHeapSize = "2g"
    }
}

// The libraries other projects build on go to Maven Central under
// net.officefloor.sinewslim. The CLI and the plugin are not published here: they ship as the
// release jar, the Docker image and the Marketplace plugin.
//
// Dry run, no secrets needed: ./gradlew publishAllPublicationsToStagingRepository writes the
// unsigned bundle to build/staging-deploy. A release signs it and uploads it to the Central
// Portal with ./gradlew publishToMavenCentral, given ORG_GRADLE_PROJECT_mavenCentralUsername,
// ORG_GRADLE_PROJECT_mavenCentralPassword, ORG_GRADLE_PROJECT_signingInMemoryKey and
// ORG_GRADLE_PROJECT_signingInMemoryKeyPassword.
val libraries = setOf("sinewslim-facts", "sinewslim-core", "sinewslim-javaparser", "sinewslim-config", "sinewslim-scan")
val signing = providers.gradleProperty("signingInMemoryKey").isPresent

configure(subprojects.filter { it.name in libraries }) {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
        coordinates(project.group.toString(), project.name, project.version.toString())
        publishToMavenCentral()
        // Central refuses an unsigned bundle; the dry run has no key and is not signed
        if (signing) {
            signAllPublications()
        }
        pom {
            name.set("SinewSlim ${project.name.removePrefix("sinewslim-")}")
            description.set(provider { project.description })
            url.set("https://github.com/officefloor/SinewSlim")
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("sagenschneider")
                    name.set("Daniel Sagenschneider")
                    organization.set("OfficeFloor")
                    organizationUrl.set("https://officefloor.net")
                }
            }
            scm {
                url.set("https://github.com/officefloor/SinewSlim")
                connection.set("scm:git:https://github.com/officefloor/SinewSlim.git")
                developerConnection.set("scm:git:ssh://git@github.com/officefloor/SinewSlim.git")
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}
