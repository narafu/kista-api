## Docker / 인프라

### Docker 빌드 OOM
- `gradle.properties`는 Dockerfile에 복사되지 않음 — JVM이 컨테이너 메모리 ~25%를 힙으로 자동 할당해 BuildKit OOM 유발
- 증상: `docker compose up` 빌드 중 `failed to receive status: ... error reading from server: EOF`
- 해결: `Dockerfile` builder 스테이지에 `ENV JAVA_TOOL_OPTIONS="-Xmx768m"` (이미 적용됨)

### Dockerfile `lombok.config` 누락
- 증상: `Parameter 0 of constructor in <Service> required a bean of type 'java.lang.String' that could not be found`
- 원인: `lombok.config`가 `src/`·`gradle/` 외부에 있어 Docker 빌드 시 Lombok이 `@Value` 전파 불가
- 현재 Dockerfile: `COPY gradlew settings.gradle.kts build.gradle.kts lombok.config ./` 로 이미 수정됨
- 새 루트 설정 파일 추가 시 동일하게 COPY 라인에 포함할 것
