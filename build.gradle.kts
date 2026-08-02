import org.gradle.api.tasks.Exec

plugins {
	kotlin("jvm") version "2.4.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(kotlin("test"))
}

sourceSets {
	main {
		java.srcDirs(
			file("src/main/kotlin"),
			file("../R3/src/main/kotlin")
		)
		kotlin.srcDirs(
			file("src/main/kotlin"),
			file("../R3/src/main/kotlin")
		)
	}
}

kotlin {
	jvmToolchain(26)
}

tasks.test {
	useJUnitPlatform()
}
val compileTypescript = tasks.register<Exec>("compileTypescript") {
	workingDir = file("src/main/resources/web/js")
	commandLine(if (System.getProperty("os.name").lowercase().contains("windows")) "npx.cmd" else "npx", "tsc")
}

tasks.named("processResources") {
	dependsOn(compileTypescript)
}