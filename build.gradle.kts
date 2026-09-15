plugins {
    java
    `java-test-fixtures`
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

// Spring Boot BOM은 Spring Modulith 버전을 관리하지 않으므로 별도 BOM platform import 필요
dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

dependencies {
    implementation(project(":shared"))

    // Spring Boot Core
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Database
    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)

    // Redis (Upstash 블랙리스트 + 캐시)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Security & JWT
    implementation(libs.spring.boot.starter.security)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // NimbusJwtDecoder (ECC P-256 JWKS 검증)
    implementation(libs.jjwt.api) // DevAuthController(local) HS256 토큰 생성용
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.gson) // jjwt는 Jackson 3 미지원 — gson 직렬화로 대체
    runtimeOnly(libs.gson)

    // API Documentation
    implementation(libs.springdoc.openapi.webmvc.ui)

    // Observability
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.otlp) // Grafana Cloud OTLP push (단일 프로세스 — Alloy 사이드카 대신 앱이 직접 push)

    // Firebase
    implementation(libs.firebase.admin)

    // Spring Modulith (버전은 위 dependencyManagement BOM import가 관리)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.events.api)
    implementation(libs.spring.modulith.events.jdbc)
    implementation(libs.spring.modulith.events.jackson) // EventSerializer 빈 제공 (JdbcEventPublicationAutoConfiguration 필수 의존성 — 브리프 미기재)

    // Apache HttpClient 5 — HttpComponentsClientHttpRequestFactory (에러 응답 바디 정상 읽기)
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // Lombok (컴파일 타임 코드 생성)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // Testing
    testImplementation(project(":trading-core")) // 메인 src는 runtimeOnly로 컴파일 경계 차단, 테스트 소스는 기존대로 trading-core 타입 직접 참조 유지(testImplementation은 implementation을 더 이상 상속하지 않음)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.archunit.junit5)
    // Boot 4 기술별 테스트 슬라이스 분리 — @WebMvcTest / @DataJpaTest+@AutoConfigureTestDatabase / TestRestTemplate
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.http.client)
    testImplementation(libs.spring.boot.restclient)
    // Testcontainers — @DataJpaTest + PostgreSQL 통합 테스트 (*IT.java)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // Spring Modulith 테스트 지원 (ApplicationModuleTest) + 모듈 구조 다이어그램 생성
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.modulith.docs)
    // InvestmentPointsHttpAdapter 등 내부 API HTTP 어댑터 테스트용 (버전 카탈로그 미등록 — 단일 사용처라 직접 좌표 지정)
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.0.0")

    // testFixtures 소스셋 지원 — DataJpaTestBase 등 컴파일에 필요
    testFixturesImplementation(project(":trading-core")) // Account 등 trading-core 도메인 모델 접근 필수 (User는 root 자체 testFixtures→main 암묵 의존으로 별도)
    testFixturesImplementation(project(":shared")) // sharedkernel 등 :shared 서브프로젝트로 분리된 공용 어휘 접근 필수
    testFixturesImplementation(libs.spring.boot.starter.data.jpa)
    testFixturesImplementation(libs.spring.boot.starter.data.jpa.test)
    testFixturesImplementation(libs.spring.boot.starter.webmvc.test)
    testFixturesImplementation(libs.spring.boot.starter.security)
    testFixturesImplementation(libs.spring.security.test)
    testFixturesImplementation(libs.spring.modulith.starter.core)
    testFixturesImplementation(libs.spring.modulith.starter.test)
    testFixturesImplementation(libs.lombok)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        // Docker/Testcontainers 필요 테스트는 기본 test 태스크에서 제외 — 별도 integration 태스크 사용
        excludeTags("integration")
    }
    maxHeapSize = "2g" // Boot 4 테스트 슬라이스 세분화로 캐시되는 ApplicationContext 수 증가 — 기본 힙으로 OOM 발생
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
