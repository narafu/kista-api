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

// Spring Boot BOM은 Spring Modulith 버전을 관리하지 않으므로 별도 BOM platform import 필요
dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

dependencies {
    // package-info.java의 @ApplicationModule(OPEN) 선언 — sharedkernel/platform 모두 Modulith 모듈
    implementation(libs.spring.modulith.starter.core)

    // platform.persistence(JPA Auditing) + platform.internalapi(RestClient) — Spring/JPA 바인딩
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.web)

    // platform.security(SecurityConfig/JwtAuthFilter 등) — JWT 인증 스택
    implementation(libs.spring.boot.starter.security)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // NimbusJwtDecoder

    // Observability — platform.metrics(MetricsConfig)의 io.micrometer.core.instrument.* 사용
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.otlp)

    // Apache HttpClient 5 — platform.internalapi(InternalApiClientConfig)
    implementation(libs.httpclient5)

    // platform.redis(RedisPubSubConfig) — trade.event/push-notification 채널 공통 배선
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // platform.internalapi(InternalApiErrorDetails) — Boot 4 기본 Jackson 3(tools.jackson)이 아닌
    // Jackson 2(com.fasterxml.jackson.databind) API 직접 사용, 버전은 Boot dependency-management BOM 관리
    implementation(libs.jackson.databind)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
}
