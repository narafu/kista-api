# ── Stage 1: Build ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Docker 빌드 중 Gradle JVM 메모리 상한 — 미설정 시 컨테이너 메모리 ~25%를 힙으로 잡아 BuildKit OOM 유발
ENV JAVA_TOOL_OPTIONS="-Xmx768m"

# Gradle Wrapper 및 의존성 레이어 캐싱 (소스 변경 없을 때 재사용)
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts lombok.config ./
RUN ./gradlew dependencies --no-daemon -q || true

# 소스 복사 및 JAR 빌드
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 보안: non-root 사용자로 실행
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /workspace/build/libs/app.jar app.jar

# JVM 옵션: Render 무료 512MB 기준 메모리 분배
# Heap 220m + Metaspace 128m + CodeCache 64m + OS/스택 ~50m ≈ 462m (512m 이내)
# SerialGC: G1GC보다 메모리 오버헤드 낮음 — 저트래픽 스케줄러 앱에 적합
ENV JAVA_OPTS="-Xmx220m \
               -Xms32m \
               -XX:MaxMetaspaceSize=128m \
               -XX:ReservedCodeCacheSize=64m \
               -XX:+UseSerialGC \
               -XX:+UseContainerSupport \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
