## Docker / 인프라

### JVM 기본 TimeZone (KST 고정)
- Fly.io 컨테이너 기본 TZ = UTC → `LocalDate.now()` 가 UTC 날짜 반환 → KIS 휴장 조회 오판 (공휴일 미감지)
- 해결: `KistaApplication.main()` 첫 줄 `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))` — `SpringApplication.run()` 보다 먼저 호출 필수
- `build.gradle.kts` test task에 `systemProperty("user.timezone", "Asia/Seoul")` — CI 환경에서도 테스트 일관성 보장
- tradeDate(KST)↔trade_date(UTC=US 거래일) 변환 정책: `constraints.md`의 "tradeDate 변환 정책" 섹션 참고

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

## 배포/인프라/외부 연동 런북

### Fly.io 배포 모니터링
```bash
# 운영 로그 실시간 조회
fly logs -a kista-api                                           # kista-api 운영 로그

# 헬스 체크
curl https://kista-api.fly.dev/actuator/health
# 배포 상태 확인
fly status -a kista-api
# 수동 배포 (main 브랜치 push 시 GitHub Actions 자동 배포)
fly deploy --app kista-api
# 증상: "Connection to localhost:5432 refused" = DB_URL 환경변수 미설정
# 로컬 Docker 컨테이너명: kista-api-app-1 (앱), kista-api-postgres-1 (DB) — kista-kista-api-1 아님
# 로컬 로그 확인: ~/.local/bin/docker --context desktop-linux logs kista-api-app-1 --tail=200
```

### Fly.io 환경변수 설정
```bash
# 환경변수 일괄 설정
fly secrets set KEY=VALUE KEY2=VALUE2 --app kista-api
# 환경변수 목록 확인
fly secrets list --app kista-api
# 필수: KIS_APP_KEY/SECRET/HTS_ID, KIS_ACCOUNT_NO/TYPE, KIS_SYMBOL/EXCHANGE_CODE,
#        TELEGRAM_BOT_TOKEN/CHAT_ID, DB_URL/USERNAME/PASSWORD, GEMINI_API_KEY,
#        JWT_SIGNING_KEY, AES_ENCRYPTION_KEY,
#        ADMIN_KAKAO_IDS (쉼표 구분 카카오 ID 목록, 자동 ADMIN 승격)
#        CORS_ALLOWED_ORIGINS (Vercel 프로덕션 URL)
# SPRING_PROFILES_ACTIVE=prod 는 fly.toml [env]에 이미 고정
```

### kista-ui 운영 로그 조회
```bash
# 운영 로그 실시간 조회 (vercel-cli)
vercel logs                                                     # kista-ui 운영 로그
```

### kista-ui URL 변경 연동 (멀티 레포)
# kista-api URL 변경 후 kista-ui에 전달하는 방법:
# 1) 이 대화의 변경 목록을 kista-ui 세션에 붙여넣기 (가장 빠름)
# 2) kista-ui 세션에서: git -C <kista-api 절대경로> diff HEAD~1
# kista-api 세션에서 절대경로로 kista-ui 파일 직접 편집 가능하나 git 커밋은 kista-ui 세션에서 따로 수행

### kis-trade-mcp 재시작
```bash
# 소스: ~/workspace/open-trading-api/MCP/Kis Trading MCP
# `KeyError: 'my_acct'` 오류 = ENV=live로 실행 시 재시작마다 yaml 재생성 → docker exec sed 수정은 무의미, 이미지 재빌드 필수
docker stop kis-trade-mcp && docker rm kis-trade-mcp
docker build -t kis-trade-mcp:latest ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP
docker run -d -p 3001:3000 --name kis-trade-mcp \
  --env-file ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP/.env.live \
  -e KIS_APP_KEY=<kista .env의 KIS_APP_KEY> \
  -e "KIS_APP_SECRET=<kista .env의 KIS_APP_SECRET>" \
  -e KIS_HTS_ID=<kista .env의 KIS_HTS_ID> \
  -e KIS_ACCT_STOCK=<kista .env의 KIS_ACCOUNT_NO> \
  -e KIS_PROD_TYPE=01 \
  kis-trade-mcp:latest
# KIS_ACCOUNT_NO → KIS_ACCT_STOCK (변수명 다름 주의)
# KIS_PROD_TYPE=01 필수 — .env.live에 빈값으로 있어서 누락 시 my_prod='' → changeTREnv 분기 미적용
```

### .mcp.json 경로 이식성
- args에 절대경로 하드코딩 금지 — `"command": "sh", "args": ["-c", "node ${HOME}/workspace/..."]` 패턴으로 Mac/WSL 공용화
- `env` 섹션 값은 쉘 확장 없이 리터럴 문자열로 전달됨 — `${HOME}` 써도 확장 안 됨
- 환경변수 참조가 필요한 값은 `sh -c "VAR=${HOME}/... node ..."` 형태로 args에 포함
- HTTP 타입 MCP `headers` 값도 리터럴 — 토큰 하드코딩 금지
- 인증이 필요한 MCP는 `stdio` 타입 + `sh -c "npx mcp-remote <url> --header \"Authorization: Bearer ${TOKEN_VAR}\""` 패턴, 토큰은 `~/.zshrc`에 `export TOKEN_VAR=...`
- `~/.claude/settings.json`은 `mcpServers` 미지원 — 글로벌 MCP 서버는 `~/.claude/.mcp.json`에 추가
- `/doctor` "Missing environment variables" 경고는 false positive — `sh`가 부모 환경에서 자동 상속

### Privacy 기준표 운영 → 로컬 마이그레이션 (supabase-cli)
# "privacy 기준표 운영에서 로컬로 마이그레이션" 요청 시 이 절차 사용
# 대상 테이블: privacy_trade_bases (기준 마스터), privacy_trade_base_orders (주문 세트)
```bash
# 1. 운영 DB에서 CSV 덤프
supabase db query --linked --output csv "SELECT * FROM privacy_trade_bases ORDER BY created_at" > /tmp/privacy_trade_bases.csv
supabase db query --linked --output csv "SELECT * FROM privacy_trade_base_orders ORDER BY created_at" > /tmp/privacy_trade_base_orders.csv

# 2. CSV를 로컬 컨테이너에 복사
docker cp /tmp/privacy_trade_bases.csv kista-api-postgres-1:/tmp/privacy_trade_bases.csv
docker cp /tmp/privacy_trade_base_orders.csv kista-api-postgres-1:/tmp/privacy_trade_base_orders.csv

# 3. 로컬 DB에 임포트 (NULL 'NULL' 옵션 필수 — supabase CSV에서 NULL이 문자열 "NULL"로 출력됨)
docker exec kista-api-postgres-1 psql -U kista -d kistadb -c \
  "COPY privacy_trade_bases (id, trade_date, ticker, current_cycle_start, current_cycle_realized_pnl, avg_price, holdings, created_at) FROM '/tmp/privacy_trade_bases.csv' WITH (FORMAT CSV, HEADER true, NULL 'NULL');"
docker exec kista-api-postgres-1 psql -U kista -d kistadb -c \
  "COPY privacy_trade_base_orders (id, privacy_trade_id, direction, order_type, quantity, price, created_at) FROM '/tmp/privacy_trade_base_orders.csv' WITH (FORMAT CSV, HEADER true, NULL 'NULL');"
```
# 로컬에 기존 데이터가 있으면 먼저 TRUNCATE (FK 순서 주의: orders → bases)
# docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "TRUNCATE privacy_trade_base_orders, privacy_trade_bases;"
