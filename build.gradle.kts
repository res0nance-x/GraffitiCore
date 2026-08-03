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
	implementation("org.example:R3:1.0-SNAPSHOT")
	testImplementation(kotlin("test"))
}


kotlin {
	jvmToolchain(25)
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