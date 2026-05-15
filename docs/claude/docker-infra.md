## Docker / 인프라

### Render 무료 티어 런타임 OOM
- 증상: `Out of memory (used over 512Mi)` — 앱 강제 종료
- 원인: Heap + Metaspace + CodeCache + OS 합산이 512MB 초과
- 해결: `ENV JAVA_OPTS="-Xmx220m -Xms32m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC -XX:+UseContainerSupport"`
- SerialGC: 저트래픽 스케줄러 앱에 적합, G1GC 대비 메모리 오버헤드 낮음

### Render 배포 방식
- GHCR 불필요 — Render가 GitHub 레포에서 Dockerfile 직접 빌드·배포
- `main` push 시 자동 배포, 환경변수 변경 시 자동 재배포 트리거됨

### Supabase 연결 (Render → Supabase)
- 반드시 포트 **6543** (PgBouncer Transaction Mode Pooler) 사용
- JDBC URL에 `?pgbouncer=true&prepareThreshold=0` 필수 — 없으면 prepared statement 오류
- `DB_USERNAME` 형식: `postgres.<project-ref>` (일반 `postgres` 아님)
- Spring Boot 첫 기동 시 Flyway + 네트워크 레이턴시로 ~140초 소요 — `HEALTHCHECK --start-period=180s` 권장

### Docker 빌드 OOM
- `gradle.properties`는 Dockerfile에 복사되지 않음 — JVM이 컨테이너 메모리 ~25%를 힙으로 자동 할당해 BuildKit OOM 유발
- 증상: `docker compose up` 빌드 중 `failed to receive status: ... error reading from server: EOF`
- 해결: `Dockerfile` builder 스테이지에 `ENV JAVA_TOOL_OPTIONS="-Xmx768m"` (이미 적용됨)

### 로컬 Docker Compose 환경변수 주입 방식
- `.env`는 `${VAR}` 치환용 — 컨테이너에 직접 주입되지 않음, `environment:` 섹션에 명시된 것만 주입됨
- `DB_URL`은 하드코딩(로컬 postgres) — `.env`의 Supabase URL 무시됨
- 컨테이너 필수 env: `AES_ENCRYPTION_KEY`(복호화), `JWT_SIGNING_KEY`(JWT 검증) — **빈 문자열로 주입 시 기동 불가** (`AesCryptoService: Empty key`), `.env`에 반드시 실제 값 설정

### 로컬 포트 할당
- Grafana: `3030:3000` (호스트 3030 → 컨테이너 내부 3000) — `3030:3030`은 동작 안 함, kista-ui와 3000 충돌 방지

### Dockerfile `lombok.config` 누락
- 증상: `Parameter 0 of constructor in <Service> required a bean of type 'java.lang.String' that could not be found`
- 원인: `lombok.config`가 `src/`·`gradle/` 외부에 있어 Docker 빌드 시 Lombok이 `@Value` 전파 불가
- 현재 Dockerfile: `COPY gradlew settings.gradle.kts build.gradle.kts lombok.config ./` 로 이미 수정됨
- 새 루트 설정 파일 추가 시 동일하게 COPY 라인에 포함할 것

### WSL Docker Desktop Integration 미활성화 시 우회 방법
- 증상: `The command 'docker' could not be found in this WSL 2 distro.`
- 원인: Docker Desktop → Settings → Resources → WSL Integration이 Ubuntu distro에 비활성화
- 직접 해결 (Docker Desktop 설정 없이): `~/.local/bin/docker` 래퍼 스크립트로 PowerShell 경유 실행 (이미 설치됨)
  - `~/.local/bin`은 `~/.zshrc`에서 이미 PATH 포함 — 스크립트 생성 즉시 사용 가능
  - 핵심: `wslpath -w "$(pwd)"` 로 WSL 경로 → Windows UNC 경로 변환 후 PowerShell `Set-Location` 으로 이동
  - Docker context: `desktop-windows`(기본) 대신 `--context desktop-linux` 지정 필수 (`docker context ls` 로 확인)
- `docker-compose.yml` Supabase 잔재 제거 완료: `supabase_network_kista-api` 외부 네트워크 삭제, `postgres:16` 서비스 추가 (kistadb/kista/kista, 포트 5432)
