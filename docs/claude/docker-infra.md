## Docker / 인프라

### JVM 기본 TimeZone (KST 고정)
- Fly.io 컨테이너 기본 TZ = UTC → `LocalDate.now()` 가 UTC 날짜 반환 → KIS 휴장 조회 오판 (공휴일 미감지)
- 해결: `KistaApplication.main()` 첫 줄 `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))` — `SpringApplication.run()` 보다 먼저 호출 필수
- `build.gradle.kts` test task에 `systemProperty("user.timezone", "Asia/Seoul")` — CI 환경에서도 테스트 일관성 보장
- DB `tradeDate`(LocalDate) 컬럼만 **UTC = US 거래일** 의미로 저장 — 도메인은 KST 일자, persistence 경계에서 `TradeDateConverter`로 ±1일 변환
- 변환 위치: `OrderPersistenceAdapter`, `PrivacyTradePersistenceAdapter` 의 toEntity/toDomain + LocalDate 파라미터 조회 메서드
- JPA `@Converter(autoApply=true)` 자동 적용 금지 — 가시성 위해 명시 호출만 사용
- `LocalDate.now(ZoneOffset.UTC)` 직접 사용 금지 — KST `LocalDate.now()` 사용 후 Adapter 경계에서 `TradeDateConverter.toUtc()` 경유

### Fly.io 런타임 메모리 설정
- Fly.io: 1GB RAM (`fly.toml [[vm]] memory='1gb'`)
- `ENV JAVA_OPTS="-Xmx512m -Xms64m ..."` — Dockerfile에 설정됨
- 이전 Render 무료 티어(512MB): `Xmx220m` 사용, Fly.io 이전 후 `Xmx512m`으로 상향
- SerialGC: 저트래픽 스케줄러 앱에 적합, G1GC 대비 메모리 오버헤드 낮음

### Fly.io 배포 방식
- `.github/workflows/fly-deploy.yml` — `main` push 시 GitHub Actions가 compileJava + ArchUnit 검증 후 `fly deploy` 자동 실행
- 리전: `nrt` (도쿄), 최소 1대 상시 유지 (`min_machines_running=1`) — 스케줄러 04:00 KST 실행 보장

### Docker 빌드 OOM
- `gradle.properties`는 Dockerfile에 복사되지 않음 — JVM이 컨테이너 메모리 ~25%를 힙으로 자동 할당해 BuildKit OOM 유발
- 증상: `docker compose up` 빌드 중 `failed to receive status: ... error reading from server: EOF`
- 해결: `Dockerfile` builder 스테이지에 `ENV JAVA_TOOL_OPTIONS="-Xmx768m"` (이미 적용됨)

### 로컬 Docker Compose 환경변수 주입 방식
- `.env`는 `${VAR}` 치환용 — 컨테이너에 직접 주입되지 않음, `environment:` 섹션에 명시된 것만 주입됨
- `DB_URL`은 하드코딩(로컬 postgres) — `.env`의 DB_URL 무시됨
- 컨테이너 필수 env: `AES_ENCRYPTION_KEY`(복호화), `JWT_SIGNING_KEY`(JWT 검증) — **빈 문자열로 주입 시 기동 불가** (`AesCryptoService: Empty key`), `.env`에 반드시 실제 값 설정
- `.env` DB 자격증명은 docker-compose postgres 계정과 반드시 일치: `DB_USERNAME=kista` / `DB_PASSWORD=kista` (`postgres`/`postgres` 아님) — 불일치 시 `FATAL: password authentication failed for user "postgres"`
- `.env`의 `DB_NAME`은 순수 DB 이름만 (`kistadb`) — `jdbc:kistadb` 같은 JDBC URL 형식 입력 시 `POSTGRES_DB` 인식 불가, `kistadb` DB 생성 실패
- SQL 마이그레이션 파일 수정 후 반드시 이미지 재빌드: `docker compose build app && docker compose up -d --force-recreate app` — `--force-recreate`만으론 부족, JAR에 구 SQL이 남아있음

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
- `docker-compose.yml`: `postgres:17` 서비스 (kistadb/kista/kista, 포트 5432)

### PostgreSQL 메이저 버전 업그레이드 (볼륨 재생성 필요)
- PG 메이저 버전 간 데이터 포맷 불호환 — 이미지만 바꾸면 기동 실패
- 절차: ① `pg_dump --data-only --disable-triggers -f /tmp/backup.sql` → `docker cp` 로 호스트 보관 ② `docker compose stop app postgres && docker compose rm -f postgres app` ③ `docker volume rm kista-api_postgres_data` ④ `docker-compose.yml` 이미지 버전 변경 ⑤ `docker compose up -d postgres` ⑥ `CREATE DATABASE kistadb OWNER kista;` 수동 실행 ⑦ `docker compose up -d app` (Flyway 실행) ⑧ 앱 healthy 확인 후 `psql -f backup.sql` 복원
- 복원 시 flyway_schema_history duplicate key 오류는 정상 (Flyway가 이미 채움) — 무시
- `${DB_NAME:-}` 환경변수 미설정 시 `POSTGRES_DB=""` → kistadb 자동 생성 안 됨, postgres 기본 DB는 POSTGRES_USER값("kista") — 새 볼륨 후 반드시 `CREATE DATABASE kistadb OWNER kista;` 수동 실행
