plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "cn.luorenmu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.simbot.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation("com.typesafe:config:1.4.3")
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.koin.ktor)
    implementation("com.microsoft.playwright:playwright:1.42.0")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("ch.qos.logback:logback-classic:1.4.11")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("org.reflections:reflections:0.10.2")
    implementation("io.ktor:ktor-server-freemarker:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.insert-koin:koin-logger-slf4j:3.5.6")
    implementation("com.belerweb:pinyin4j:2.5.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
