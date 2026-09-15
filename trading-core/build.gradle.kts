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

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // JwtDecoderConfig(:shared)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi.webmvc.ui)
    runtimeOnly(libs.postgresql)

    implementation("org.springframework.boot:spring-boot-starter-data-redis") // broker/TossRedisTokenStore

    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.events.api)

    implementation("org.apache.httpcomponents.client5:httpclient5") // broker KIS/Toss HTTP 클라이언트

    // Observability — MetricsConfig(:shared, platform.metrics)가 MeterRegistry 빈을 요구하는데,
    // 이 빈은 spring-boot-starter-actuator의 auto-config가 만든다(micrometer registry 구현체만
    // 있고 actuator 자체가 없으면 부팅 실패 — 4a Task 10 로컬 2-프로세스 스모크 테스트에서 실측 확인)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.otlp)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.webmvc.test) // @WebMvcTest for controller tests
    testImplementation(libs.spring.boot.starter.security.test) // @WebMvcTest SecurityFilterChain auto-detection (Boot 4 requirement)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":")) // trading-core 테스트가 user/admin 소유 보안 테스트 스캐폴딩(SecurityConfig 등)에 역참조 — Stage 3/4에서 보안 모듈 경계 정리 시 해소 대상, Task 8에서 문서화 예정
    testImplementation(testFixtures(project(":"))) // DataJpaTestBase, WebMvcTestSupport, DomainFixtures
    testImplementation(libs.spring.security.test) // SecurityMockMvcRequestPostProcessors
}

tasks.named<Test>("test") {
    workingDir = rootProject.projectDir
    useJUnitPlatform {
        excludeTags("integration")
    }
    systemProperty("user.timezone", "Asia/Seoul")
}

// Docker/로컬 Redis 등 필요 통합 테스트 전용 태스크 — 루트에서 ./gradlew integration 실행 시 함께 구동됨
tasks.register<Test>("integration") {
    group = "verification"
    description = "로컬 Redis 등 필요 통합 테스트 (*IT.java, @Tag(\"integration\"))"
    workingDir = rootProject.projectDir
    useJUnitPlatform {
        includeTags("integration")
    }
    systemProperty("user.timezone", "Asia/Seoul")
}
