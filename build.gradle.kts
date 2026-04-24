plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

group = "com.kista"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Core
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Database
    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // Querydsl JPA (Jakarta EE 10 호환)
    implementation("${libs.querydsl.jpa.get()}:jakarta")
    annotationProcessor("${libs.querydsl.apt.get()}:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    // API Documentation
    implementation(libs.springdoc.openapi.webmvc.ui)

    // Observability
    implementation(libs.micrometer.prometheus)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
}

// Querydsl Q-class 생성 디렉토리 설정
val querydslDir = layout.buildDirectory.dir("generated/querydsl")

sourceSets {
    main {
        java {
            srcDir(querydslDir)
        }
    }
}

tasks.withType<JavaCompile> {
    options.generatedSourceOutputDirectory = querydslDir.get().asFile
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
    environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    systemProperty("api.version", "1.44")
}
