## Docker / 인프라

### JVM 기본 TimeZone (호출부 명시, 전역 설정 금지)
- Fly.io 컨테이너 기본 TZ = UTC → `LocalDate.now()`/`LocalTime.now()`를 타임존 없이 호출하면 UTC 날짜 반환 → KST 09시 이전 "오늘" 오판
- 해결 정책(`45758166`): `KistaApplication.main()`의 전역 `TimeZone.setDefault()` 의존 제거 — 모든 `LocalDate.now()`/`LocalTime.now()` 호출부에 `TimeZones.KST`(`com.kista.common.TimeZones`)를 명시. 신규 호출부 추가 시 반드시 `LocalDate.now(TimeZones.KST)` 형태 사용, `KistaApplication`에 전역 설정 재도입 금지
- `build.gradle.kts` test task에 `systemProperty("user.timezone", "Asia/Seoul")` — CI 환경에서도 테스트 일관성 보장
- 시간 기준 정책(거래일 KST 단일 기준, US 외부 데이터만 어댑터 내부 변환): `constraints.md`의 "시간 기준 정책 (KST 단일 기준)" 섹션 참고

### Fly.io 런타임 메모리 설정
- Fly.io: 2GB RAM (`fly.toml [[vm]] memory='2gb'`)
- `ENV JAVA_OPTS="-Xmx768m -Xms128m ..."` — Dockerfile에 설정됨
- 이전 1GB 설정은 `Xmx384m` + SerialGC 사용
- G1GC: 2GB 환경에서 요청/스케줄러 겹침 시 지연시간 변동 완화 목적

### Fly.io 배포 방식
- `.github/workflows/fly-deploy.yml` — `main` push 시 GitHub Actions가 compileJava + ArchUnit 검증 후 `fly deploy` 자동 실행
- 리전: `nrt` (도쿄), 최소 1대 상시 유지 (`min_machines_running=1`) — 스케쥴러 04:30 KST 실행 보장
- `fly.toml`의 `strategy = "immediate"`는 현재 production의 pre-branch DB/JVM token protocol과 Redis fencing binary가 겹치지 않게 하는 **1회성 protocol cutover** 설정이다. 이 배포에는 짧은 downtime과 health-check 없는 동시 교체가 허용된다. 실제 배포는 자동 workflow가 수행하며 이 변경 작업에서 수동 배포하지 않는다.
- 첫 production 배포 성공 후 `fly status --app kista-api`와 Toss 계좌/관리자 토큰 발급을 확인한다. 이어지는 별도 follow-up에서 `fly.toml`을 정확히 `strategy = "rolling"`으로 되돌려 커밋·push하고 자동 배포 성공까지 확인한다. 최초 cutover와 rolling 복원을 같은 배포에 섞지 않는다.

### Fly.io 다중 인스턴스 Toss 토큰 조정
- 모든 Fly 인스턴스의 Toss 계좌·관리자 canonical token은 Upstash Redis hash로 공유한다. OAuth 실제 만료보다 5분 짧은 TTL, fencing generation, expiry epoch를 저장한다. Toss는 PostgreSQL `broker_tokens`와 JPA pool을 사용하지 않는다. KIS는 기존 PostgreSQL token cache를 유지한다.
- scope별 60초 Redis owner lease와 generation `INCR`는 하나의 Lua script로 실행한다. lease expiry 뒤 successor가 더 큰 generation을 받으면 canonical CAS가 늦은 이전 owner write를 거절한다. owner-safe Lua unlock은 successor lease를 보존한다.
- Redis에는 canonical raw token 외에 영구 generation counter와 최근 SHA-256 fingerprint(2초)가 존재한다. raw bearer token을 로그 또는 fingerprint key에 기록하지 않는다. Redis 연결·script 실패는 로컬/DB fallback 없이 503으로 fail-closed 하며 운영 인스턴스는 모두 같은 Redis를 보아야 한다.

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

### docker-compose 서비스
- `postgres:17` (kistadb/kista/kista, 포트 5432)

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
vercel logs                                                     # kista-ui 운영 로그

# 헬스 체크 / 배포 상태
curl https://kista-api.fly.dev/actuator/health
fly status -a kista-api
# 수동 배포 (main 브랜치 push 시 GitHub Actions 자동 배포)
fly deploy --app kista-api
# 증상: "Connection to localhost:5432 refused" = DB_URL 환경변수 미설정
# 로컬 컨테이너명: kista-api-app-1 (앱), kista-api-postgres-1 (DB)
# 로컬 로그 확인: ~/.local/bin/docker --context desktop-linux logs kista-api-app-1 --tail=200
```

### 외부 모니터링/알림
Fly.io 자체 헬스체크(`fly.toml`)는 실패한 machine을 재시작할 뿐 사람에게 알리지 않음 — 아래 3개는 사람이 실제로 장애를 인지하기 위한 외부 계층.

- **가동 여부**: UptimeRobot(무료) → `https://kista-api.fly.dev/actuator/health` 5분 간격 외부 체크, 알림 채널(이메일/텔레그램) 연결
- **스케줄러 미실행 감지(dead-man's switch)**: healthchecks.io(무료) — `HeartbeatPort`/`HeartbeatAdapter`가 `TradingOpenScheduler`(월~금 22:30 KST)·`TradingCloseScheduler`(화~토 04:30 KST) 완료 시 ping. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전) — healthchecks.io 콘솔에서 각 체크 예상 주기 등록 필요
- **메트릭 가시성**: Grafana Cloud OTLP push — Fly.io는 단일 프로세스라 Alloy 사이드카 대신 `micrometer-registry-otlp`가 앱에서 직접 `management.otlp.metrics.export.url`로 60초 간격 push (`GRAFANA_CLOUD_OTLP_*` 환경변수, → "Fly.io 환경변수 설정" 참고)
  - 대시보드 JSON: `deploy/grafana/kista-api-dashboard.json` — Grafana Cloud 계정 유실 시 재구성용, Dashboards → New → Import로 재적용
  - **Import 시 주의**: 대시보드 JSON Model 편집창(Settings → JSON Model)은 계정이 이미 v2 스키마(`elements`/`layout`)로 마이그레이션된 경우 구버전 `panels`/`gridPos` JSON을 거부함(`Missing property "elements"` 등) — 기존 대시보드를 고치려 하지 말고 **Import로 새로 생성** 후 기존 것 삭제
  - Grafana Cloud 메트릭 이름은 Micrometer 관례와 다를 수 있음 — 예: 업타임은 `process_uptime_seconds`가 아니라 **`process_uptime_milliseconds`**(단위도 ms). 확인 방법: Explore에서 `{application="kista-api"}`로 전체 시리즈 조회 후 이름 확인

### Fly.io 환경변수 설정
```bash
# 환경변수 일괄 설정
fly secrets set KEY=VALUE KEY2=VALUE2 --app kista-api
# 환경변수 목록 확인
fly secrets list --app kista-api
# 필수 환경변수 (V2 멀티계좌 — KIS 자격증명은 accounts 테이블에 계좌별 암호화 저장, 전역 env 아님):
#   JWT_SIGNING_KEY          — EC P-256 JWK JSON (JWT 서명/검증)
#   AES_ENCRYPTION_KEY       — AES-256 암호화 키 (계좌 자격증명 복호화)
#   ADMIN_KAKAO_IDS          — 쉼표 구분 카카오 ID (로그인 시 ADMIN 자동 승격)
#   INTERNAL_API_TOKEN       — 서버 간 내부 인증 (/api/internal/**)
#   KAKAO_CLIENT_ID          — 카카오 OAuth 클라이언트 ID
#   KAKAO_CLIENT_SECRET      — 카카오 OAuth 클라이언트 시크릿 (선택)
#   TELEGRAM_BOT_TOKEN       — 관리자봇 토큰 (NotifyPort 오류/리포트 알림)
#   TELEGRAM_CHAT_ID         — 관리자봇 chat ID
#   DB_URL, DB_USERNAME, DB_PASSWORD — Supabase PostgreSQL 연결
#   REDIS_URL               — Upstash Redis TLS URL (JWT blacklist + Toss 분산 토큰 조정)
#   CORS_ALLOWED_ORIGINS     — 쉼표 구분 허용 Origin (Vercel 프로덕션 URL)
# 선택 환경변수 (모니터링 — 미설정 시 각 기능 비활성/생략, 배포 안전):
#   HEARTBEAT_OPEN_URL       — healthchecks.io 개장 스케쥴러 dead-man's switch ping URL
#   HEARTBEAT_CLOSE_URL      — healthchecks.io 마감 스케쥴러 dead-man's switch ping URL
#   GRAFANA_CLOUD_OTLP_ENABLED    — true 설정 시 OTLP metrics push 활성화 (기본 false)
#   GRAFANA_CLOUD_OTLP_ENDPOINT   — Grafana Cloud OTLP gateway URL (예: https://otlp-gateway-prod-ap-northeast-0.grafana.net/otlp)
#   GRAFANA_CLOUD_OTLP_AUTH_HEADER — Grafana Cloud 발급 "Basic xxxx" 인증 헤더 값 전체
# SPRING_PROFILES_ACTIVE=prod 는 fly.toml [env]에 이미 고정
```

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

### 운영 → 로컬 마이그레이션 (supabase-cli)
```bash
# 1. 운영 DB에서 CSV 덤프 (supabase CLI 출력 메시지가 CSV에 섞이므로 UUID 행만 grep으로 추출)
supabase db query --linked --output csv "SELECT * FROM privacy_trade_bases ORDER BY created_at" | \
  grep -E "^id,|^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}," > /tmp/privacy_trade_bases.csv
supabase db query --linked --output csv "SELECT * FROM privacy_trade_base_orders ORDER BY created_at" | \
  grep -E "^id,|^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}," > /tmp/privacy_trade_base_orders.csv
supabase db query --linked --output csv "SELECT * FROM fear_greed_snapshots ORDER BY created_at" | \
  grep -E "^id,|^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}," > /tmp/fear_greed_snapshots.csv

# 2. CSV를 로컬 컨테이너에 복사
docker cp /tmp/privacy_trade_bases.csv kista-api-postgres-1:/tmp/privacy_trade_bases.csv
docker cp /tmp/privacy_trade_base_orders.csv kista-api-postgres-1:/tmp/privacy_trade_base_orders.csv
docker cp /tmp/fear_greed_snapshots.csv kista-api-postgres-1:/tmp/fear_greed_snapshots.csv

# 3. 로컬 DB에 임포트 (NULL 'NULL' 옵션 필수 — supabase CSV에서 NULL이 문자열 "NULL"로 출력됨)
#    컬럼 순서는 CSV 헤더(SELECT * 순서)와 일치해야 함
docker exec kista-api-postgres-1 psql -U kista -d kistadb -c \
  "COPY privacy_trade_bases (id, release_date, ticker, current_cycle_start, current_cycle_realized_pnl, avg_price, holdings, created_at) FROM '/tmp/privacy_trade_bases.csv' WITH (FORMAT CSV, HEADER true, NULL 'NULL');"
docker exec kista-api-postgres-1 psql -U kista -d kistadb -c \
  "COPY privacy_trade_base_orders (id, privacy_trade_id, direction, order_type, price, quantity, created_at) FROM '/tmp/privacy_trade_base_orders.csv' WITH (FORMAT CSV, HEADER true, NULL 'NULL');"
docker exec kista-api-postgres-1 psql -U kista -d kistadb -c \
  "COPY fear_greed_snapshots (id, source, snapshot_date, value, rating, created_at) FROM '/tmp/fear_greed_snapshots.csv' WITH (FORMAT CSV, HEADER true, NULL 'NULL');"
# 로컬에 기존 데이터가 있으면 먼저 TRUNCATE (FK 순서 주의: orders → bases)
# docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "TRUNCATE privacy_trade_base_orders, privacy_trade_bases, fear_greed_snapshots;"
```

## 백업/복구 런북

### DB 백업 (Supabase 운영)
- Supabase 자동 백업: 대시보드 → Database → Backups에서 플랜별 보존 기간 확인 (Free: 없음, Pro: 일 1회 7일 보존) — Free 플랜 유지 중이므로 자체 백업 필수
- **자동 백업**: `.github/workflows/db-backup.yml` — 매일 02:00 KST cron, `pg_dump` → GPG 대칭키 암호화 → GitHub Actions artifact 업로드(30일 보존). `workflow_dispatch`로 수동 실행도 가능
  - 레포가 **public**이라 artifact는 누구나 다운로드 가능 — 반드시 GPG로 암호화한 뒤 업로드 (평문 업로드 금지)
  - 필요 GH secret: `SUPABASE_DB_URL`(session mode 포트 5432, pgbouncer 6543 아님), `BACKUP_ENCRYPTION_KEY`
  - `SUPABASE_DB_URL` 구성: 앱이 쓰는 Fly `DB_URL`(JDBC, pgbouncer 6543)과 다름 — host/user/password는 동일하게 재사용하되 포트만 `5432`로 바꾸고 `postgresql://user:password@host:5432/postgres` 형태의 순수 URI로 변환 필요
- 수동 백업: `supabase db dump --linked -f backup-$(date +%Y%m%d).sql` — 중요 스키마 변경(마이그레이션 배포) 직전 필수 실행
- 백업 파일(복호화 후)은 레포 밖 안전한 위치에 보관 (git 커밋 금지 — 사용자 데이터 포함)

### 복구
1. artifact 백업 사용 시 먼저 복호화: `gpg --batch --yes --passphrase "$BACKUP_ENCRYPTION_KEY" -o backup.sql -d backup-YYYYMMDD.sql.gpg`
2. 신규/기존 프로젝트에 복원: `psql "$DB_URL" < backup.sql`
3. 복원 후 `flyway_schema_history` 최신 버전이 배포 코드의 마이그레이션 버전과 일치하는지 확인 — 불일치 시 앱 기동 실패
4. 앱 재기동 후 `/actuator/health` 200 확인 + 텔레그램 시작 알림 수신 확인

### 키 백업 (분실 시 복구 불가 — DB 백업과 별도 보관 필수)
- `AES_ENCRYPTION_KEY` — 분실 시 accounts의 암호화 컬럼(계좌번호·API 키) 전체 복호화 불가 → 사용자 재등록 필요
- `JWT_SIGNING_KEY` — 분실 시 전체 사용자 재로그인 (치명적이지 않음)
- `BACKUP_ENCRYPTION_KEY` — 분실 시 GitHub Actions에 쌓인 모든 DB 백업 artifact 복호화 불가 (백업 자체가 무의미해짐)
- 확인: `fly secrets list -a kista-api` (값은 안 보임 — 원본을 별도 보관해야 함), GH secret은 `gh secret list`로 이름만 확인 가능
```
