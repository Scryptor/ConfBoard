plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

// Симулятор клиента: подключается к реальной доске в сети и рисует.
// ./gradlew :core:simClient
tasks.register<JavaExec>("simClient") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("board.core.SimClientKt")
    standardInput = System.`in`
}
