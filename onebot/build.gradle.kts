plugins {
    kotlin("jvm")
}

group = "cn.luorenmu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.netty)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation(libs.simbot.core)
    implementation(libs.simbot.component.onebot)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
tasks.jar {
    val defaultJarName = "ERBot.jar"
    val jarName = providers.gradleProperty("erbotJarName").orNull ?: defaultJarName
    archiveFileName.set(jarName)

    manifest {
        attributes["Main-Class"] = "cn.luorenmu.onebot.OneBotMainKt"
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
