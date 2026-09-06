import org.gradle.api.tasks.Exec

plugins {
	kotlin("jvm") version "2.4.0"
}

group = "R3"
version = "1.0"

repositories {
	mavenCentral()
}

dependencies {
	implementation("R3:R3:1.0")
	testImplementation(kotlin("test"))
}


kotlin {
	jvmToolchain(25)
}


tasks.test {
	useJUnitPlatform()
}
val compileTypescript = tasks.register<Exec>("compileTypescript") {
	workingDir = file("src/main/resources/web")
	commandLine(if (System.getProperty("os.name").lowercase().contains("windows")) "npx.cmd" else "npx", "tsc")
}

tasks.named("processResources") {
	dependsOn(compileTypescript)
}