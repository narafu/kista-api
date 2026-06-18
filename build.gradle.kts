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
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Database
    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // Querydsl JPA (Jakarta EE 10 호환)
    implementation("${libs.querydsl.jpa.get()}:jakarta")
    annotationProcessor("${libs.querydsl.apt.get()}:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    // Security & JWT
    implementation(libs.spring.boot.starter.security)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // NimbusJwtDecoder (ECC P-256 JWKS 검증)
    implementation(libs.jjwt.api) // DevAuthController(local) HS256 토큰 생성용
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // API Documentation
    implementation(libs.springdoc.openapi.webmvc.ui)

    // Observability
    implementation(libs.micrometer.prometheus)

    // Firebase
    implementation(libs.firebase.admin)

    // Apache HttpClient 5 — HttpComponentsClientHttpRequestFactory (에러 응답 바디 정상 읽기)
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // Lombok (컴파일 타임 코드 생성)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.archunit.junit5)
    // Testcontainers — @DataJpaTest + PostgreSQL 통합 테스트 (*IT.java)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
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
    useJUnitPlatform {
        // Docker/Testcontainers 필요 테스트는 기본 test 태스크에서 제외 — 별도 integration 태스크 사용
        excludeTags("integration")
    }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("user.timezone", "Asia/Seoul") // 테스트도 KST로 고정 — host TZ 무관하게 일관성 보장
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
}

// Docker + Testcontainers 통합 테스트 전용 태스크 — ./gradlew integration
tasks.register<Test>("integration") {
    group = "verification"
    description = "Testcontainers PG 통합 테스트 (*IT.java)"
    useJUnitPlatform {
        includeTags("integration")
    }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("user.timezone", "Asia/Seoul")
}
