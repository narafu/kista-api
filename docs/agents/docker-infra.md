## Docker / 인프라

### JVM 기본 TimeZone (호출부 명시, 전역 설정 금지)
- Docker 컨테이너 기본 TZ = UTC(호스트 무관, JVM 공통) → `LocalDate.now()`/`LocalTime.now()`를 타임존 없이 호출하면 UTC 날짜 반환 → KST 09시 이전 "오늘" 오판. Fly.io 운영 시절 이 문제로 실제 발견된 정책
- 해결 정책(`45758166`): `KistaApplication.main()`의 전역 `TimeZone.setDefault()` 의존 제거 — 모든 `LocalDate.now()`/`LocalTime.now()` 호출부에 `TimeZones.KST`(`com.kista.common.TimeZones`)를 명시. 신규 호출부 추가 시 반드시 `LocalDate.now(TimeZones.KST)` 형태 사용, `KistaApplication`에 전역 설정 재도입 금지
- `build.gradle.kts` test task에 `systemProperty("user.timezone", "Asia/Seoul")` — CI 환경에서도 테스트 일관성 보장
- 시간 기준 정책(거래일 KST 단일 기준, US 외부 데이터만 어댑터 내부 변환): `constraints.md`의 "시간 기준 정책 (KST 단일 기준)" 섹션 참고

### 서버 배포 방식 (현재 OCI)
- 배포 설정 변경은 커밋으로 끝난 게 아니라 **실제 적용 여부를 반드시 확인**할 것 — 과거 Fly.io 시절 `fly.toml`의 배포 전략 섹션 키가 오타(`[deployment]` — 올바른 키는 `[deploy]`)라 조용히 무시되고 한 번도 적용되지 않은 사고 이력이 있음. 플랫폼은 바뀌었지만 "커밋했다"≠"적용됐다"는 원칙은 현행 OCI 배포에도 동일하게 적용
- `.github/workflows/server-deploy.yml` — `main` push 시 GitHub Actions가 전체 테스트 스위트(ArchUnit 포함) 검증 → `linux/arm64` GHCR 이미지 빌드·push → SSH로 서버에 배포 (매매 시간대 가드는 `deploy-scheduler` 잡에만 적용 — 아래 참고)
- **2-role 배포 (2026-09-04~)**: 같은 GHCR 이미지를 컨테이너 2개로 띄운다 — `kista-api`(HTTP, `scheduler.enabled=false`, 매매 가드 없이 잦은 배포)와 `kista-scheduler`(`SCHEDULER_ENABLED=true`, 매매 배치 실행, 매매 시간대 배포 가드 유지). `server-deploy.yml`은 `verify`·`build` 후 `deploy-api`·`deploy-scheduler` 두 독립 잡(`_deploy-role.yml` 재사용 워크플로)을 호출한다. 매매 시간대에 push하면 `deploy-api`는 통과, `deploy-scheduler`만 `exit 1` — 장 마감 후 Actions에서 해당 잡만 Re-run. EPR 미완료 이벤트 재발행 소유자는 `kista-scheduler` 단독(`application-prod.yml`이 API role은 `false`, 스케쥴러 컨테이너가 env로 `true`) — 양쪽 재발행 시 중복 알림 방지. 수동 트리거(`/api/admin/scheduler/*`)는 Caddy가 `kista-scheduler`로 라우팅(kista-infra 레포). 또한 `deploy-api`·`deploy-scheduler` 두 잡이 각각 `production` GitHub 환경을 참조하므로, `production`에 protection rule(필수 리뷰어·wait timer)을 걸면 push 1건당 승인이 2회 필요해진다 — 현재는 protection rule 없음.
- **Flyway 마이그레이션 backward-compat 필수**: 2-role은 독립 배포라 `kista-scheduler`가 이전 이미지로 새 스키마를 물 수 있다. 컬럼 추가는 nullable/DEFAULT, 드롭·리네임은 두 배포로 나눠 코드가 참조를 먼저 끊는다(expand/contract). 이 조건을 못 지키는 마이그레이션은 두 role을 같은 커밋에서 함께 배포
- 배포 파일: `deploy/server/docker-compose.yml`(kista-api + kista-scheduler 2-role — caddy·redis는 kista-infra 레포가 전담), `deploy/server/README.md`(초기 서버 설정·롤백 runbook·커트오버 체크리스트 전체 — 상세 절차는 이 README 참고)
- `kista-infra`(private, `/opt/kista-infra/`) 레포가 caddy(양 도메인 리버스 프록시)·postgres·redis·백업 cron을 전담한다. `shared_net`(caddy↔kista-api/kista-ui)·`data_net`(postgres·redis↔kista-api만, kista-ui 미가입) 두 개의 external Docker 네트워크로 앱↔인프라 경계를 분리한다. **인스턴스 재편·컷오버 완료(2026-08-07)** — kista-api-server(A)가 caddy·postgres·redis·kista-api·kista-ui를 모두 올인원으로 호스팅, kista-ui-server는 삭제됨, DB는 Supabase에서 자체호스팅 postgres(`postgres:5432/kistadb`)로 이관 완료. fida-server만 별도 유지.
- **현재 인스턴스는 OCI `VM.Standard.A1.Flex`(Ampere arm64), 2 OCPU, 12GB RAM, 부트 볼륨 50GB, Ubuntu 24.04** — 워크플로 `platforms` 값(`linux/arm64`)과 인스턴스 아키텍처가 항상 일치해야 하며, 인스턴스를 다른 아키텍처로 재생성하면 `server-deploy.yml`의 `platforms` 값도 함께 변경 필요
- **OCI 볼륨은 in-place 축소 불가, OCPU·메모리는 가능** — 최초 12GB/부트 200GB로 생성했다가 free-tier 리전 스토리지(200GB) 전량을 부트 볼륨 하나가 점유해 다른 인스턴스를 만들 여유가 없어져 부트 볼륨만 50GB로 재생성함(2026-08-03). 볼륨을 줄여야 하면 재생성이 유일한 방법 — 아래 무중단 재생성 노하우 참고. 반면 OCPU·메모리는 Flexible shape 속성이라 `oci compute instance update --shape-config '{"ocpus":N,"memoryInGBs":M}'`로 살아있는 인스턴스에서 바로 변경 가능(적용에 재부팅 필요, IP·볼륨 유지) — 재생성 없이 스펙만 조정할 땐 이 경로를 우선 검토
- **예약 공인 IP 재할당으로 무중단 호스트 교체**: 공인 IP를 처음부터 Reserved(에페메럴 아님)로 할당해두면, 인스턴스 재생성(스펙 변경·볼륨 축소 등) 시 새 인스턴스를 임시 공인 IP로 완전히 기동·스모크 테스트한 뒤 `oci network public-ip update --private-ip-id <새 인스턴스 private-ip-ocid>`로 예약 IP만 재할당하면 된다 — 도메인·DNS·GitHub Secret(`SERVER_HOST`)·카카오 OAuth·CORS 전부 무변경. old 인스턴스는 외부 검증 통과 전까지 종료하지 않는 것이 핵심 안전장치(문제 발생 시 예약 IP를 old로 즉시 되돌려 롤백)
- Caddy가 80/443만 외부에 공개하고 HTTPS를 종료해 `kista-api:8080`으로 reverse proxy — 서버 방화벽에서 `8080`은 절대 공개하지 않는다
- GitHub Secrets: `SERVER_HOST`(서버 IP/도메인), `SERVER_USER`(SSH 사용자명), `SERVER_SSH_KEY`(SSH 개인키), `SERVER_SSH_PORT`(기본값 22, 선택) — 이 레포의 Actions는 `.env`를 렌더링하지 않는다. `/opt/kista-api/.env`는 `kista-infra` 레포의 배포 워크플로가 `secrets/kista-api.env.gpg`를 복호화해 매 배포마다 렌더링·덮어쓴다
- `.env` 필수 키는 `deploy/server/README.md`의 ".env 내용" 섹션 참고 — `API_DOMAIN`은 헬스체크 대상 도메인을 문서화하는 값일 뿐, kista-infra Caddy의 실제 라우팅은 kista-infra 자신의 `.env`(`infra.env.gpg`)에 담긴 `API_DOMAIN` 값을 따로 참조한다(둘은 같은 값이지만 소스가 다름)
- **Redis는 자체 호스팅**(kista-infra 레포의 `docker-compose.yml`의 `redis` 서비스, AOF 영속성) — Fly.io는 다중 인스턴스 가능성 때문에 외부 공유 Redis(Upstash)가 필수였지만, OCI는 단일 인스턴스라 로컬 Redis 하나로 "모든 운영 인스턴스가 같은 Redis를 봐야 함" 제약이 자동 충족됨. `REDIS_URL`은 `docker-compose.yml`에 `redis://redis:6379`로 하드코딩되어 있어 `.env` 설정 불필요
- Fly.io의 기존 Redis("Fly Redis" 애드온, `fly-*-redis.upstash.io`)는 Fly 사설 네트워크(`fdaa::/16`, 6PN 전용) 주소라 **외부에서 접근 불가** — 커트오버 시 이 값을 그대로 재사용하려다 실측(`fly ssh console`로 조회 후 외부에서 접속 시도 → `Network is unreachable`)으로 확인됨. 벤더를 완전히 바꾸는 마이그레이션에서는 Fly Redis를 승계할 수 없다는 뜻이라, 새 Redis(관리형이든 자체 호스팅이든)를 처음부터 새로 구성해야 함

### 다중 인스턴스 Toss 토큰 조정
- 모든 인스턴스의 Toss 계좌·관리자 canonical token은 자체호스팅 Redis hash로 공유한다. OAuth 실제 만료보다 5분 짧은 TTL, fencing generation, expiry epoch를 저장한다. Toss는 PostgreSQL `broker_tokens`와 JPA pool을 사용하지 않는다. KIS는 기존 PostgreSQL token cache를 유지한다.
- scope별 20초 Redis owner lease(OAuth RestTemplate 타임아웃 최악 케이스보다 여유 있게, owner crash 시 blast radius 최소화 목적)와 generation `INCR`는 하나의 Lua script로 실행한다. lease expiry 뒤 successor가 더 큰 generation을 받으면 canonical CAS가 늦은 이전 owner write를 거절한다. owner-safe Lua unlock은 successor lease를 보존한다.
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

### 서버(OCI) 운영 모니터링
```bash
# 운영 로그 실시간 조회 (SSH 접속 후, /opt/kista-api 또는 /opt/kista-ui에서)
docker compose logs -f kista-api                                # kista-api 운영 로그
docker compose logs -f kista-ui                                 # kista-ui 운영 로그

# 헬스 체크 / 배포 상태
curl https://api.kista-app.com/actuator/health
gh run list --repo narafu/kista-api                              # 최근 배포 워크플로 상태
gh workflow run "Server Deploy" --repo narafu/kista-api          # 수동 재배포 트리거(push 미인식 시 우회 경로)
```

### 외부 모니터링/알림
컨테이너 자체 healthcheck(`docker-compose.yml`의 `healthcheck:`)는 실패 시 재시작만 할 뿐 사람에게 알리지 않음 — 아래 3개는 사람이 실제로 장애를 인지하기 위한 외부 계층.

- **가동 여부**: UptimeRobot(무료) → `https://api.kista-app.com/actuator/health` 5분 간격 외부 체크, 알림 채널(이메일/텔레그램) 연결
- **스케줄러 미실행 감지(dead-man's switch)**: healthchecks.io(무료) — `HeartbeatPort`/`HeartbeatAdapter`가 `TradingOpenScheduler`(월~금 22:30 KST)·`TradingCloseScheduler`(화~토 04:30 KST) 완료 시 ping. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전) — healthchecks.io 콘솔에서 각 체크 예상 주기 등록 필요
- **메트릭 가시성**: Grafana Cloud OTLP push — 호스팅 위치 무관하게 `micrometer-registry-otlp`가 앱에서 직접 `management.otlp.metrics.export.url`로 60초 간격 push (`GRAFANA_CLOUD_OTLP_*` 환경변수, kista-infra의 `secrets/kista-api.env.gpg`로 관리 — 값 목록은 저장소 루트 `.env.example` 참고)
  - 대시보드 JSON: `deploy/grafana/kista-api-dashboard.json` — Grafana Cloud 계정 유실 시 재구성용, Dashboards → New → Import로 재적용
  - **Import 시 주의**: 대시보드 JSON Model 편집창(Settings → JSON Model)은 계정이 이미 v2 스키마(`elements`/`layout`)로 마이그레이션된 경우 구버전 `panels`/`gridPos` JSON을 거부함(`Missing property "elements"` 등) — 기존 대시보드를 고치려 하지 말고 **Import로 새로 생성** 후 기존 것 삭제
  - Grafana Cloud 메트릭 이름은 Micrometer 관례와 다를 수 있음 — 예: 업타임은 `process_uptime_seconds`가 아니라 **`process_uptime_milliseconds`**(단위도 ms). 확인 방법: Explore에서 `{application="kista-api"}`로 전체 시리즈 조회 후 이름 확인

### 서버(OCI) 환경변수 설정
`fly secrets set` 같은 CLI 경로 없음 — 환경변수는 전부 kista-infra 레포의 GPG 암호화 시크릿으로 관리한다.
```bash
cd ../kista-infra
./scripts/env.sh edit kista-api      # 복호화 → 편집 → 저장 시 자동 재암호화 → commit/push하면 다음 배포에 반영
```
- 필수/선택 키 전체 목록은 `kista-infra/.env.example` 참고(JWT_SIGNING_KEY·AES_ENCRYPTION_KEY·ADMIN_KAKAO_IDS·INTERNAL_API_TOKEN·카카오 OAuth·텔레그램 봇·DB_URL/USERNAME/PASSWORD·CORS_ALLOWED_ORIGINS·HEARTBEAT_*_URL·GRAFANA_CLOUD_OTLP_* 등)
- `SPRING_PROFILES_ACTIVE=prod`는 `deploy/server/docker-compose.yml`의 `environment:`에 고정
- 편집한 시크릿은 push해야 서버에 반영된다 — kista-infra의 `server-deploy.yml`이 매 배포마다 복호화해 `/opt/kista-api/.env`를 렌더링·덮어쓰지만, 재시작 대상은 caddy/postgres/redis뿐이라 kista-api에 실제로 반영하려면 kista-api 자체 배포도 별도로 트리거해야 함

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

### 운영 → 로컬 마이그레이션 (자체호스팅 postgres, Phase 3 이관 후 절차)
DB가 자체호스팅 postgres로 바뀌면서 supabase-cli의 CSV 덤프/COPY 우회가 더 이상 필요 없다 — SSH로 서버 컨테이너에서 직접 `pg_dump -t`로 테이블을 골라 떠서 로컬로 복원한다.
```bash
# 1. 서버에서 필요한 테이블만 pg_dump (custom format)
ssh -i ~/secret/oci-ssh-key-kista.key ubuntu@<SERVER_HOST> \
  "docker exec kista-postgres pg_dump -U kista -d kistadb -Fc \
     -t privacy_trade_bases -t privacy_trade_base_orders -t fear_greed_snapshots \
     -f /tmp/seed.dump && docker cp kista-postgres:/tmp/seed.dump /tmp/seed.dump"

# 2. 로컬로 다운로드 후 로컬 컨테이너로 복원
scp -i ~/secret/oci-ssh-key-kista.key ubuntu@<SERVER_HOST>:/tmp/seed.dump /tmp/seed.dump
docker cp /tmp/seed.dump kista-api-postgres-1:/tmp/seed.dump
docker exec kista-api-postgres-1 pg_restore -U kista -d kistadb --data-only --disable-triggers /tmp/seed.dump
# 로컬에 기존 데이터가 있으면 먼저 TRUNCATE (FK 순서 주의: orders → bases)
# docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "TRUNCATE privacy_trade_base_orders, privacy_trade_bases, fear_greed_snapshots;"
```

## 백업/복구 런북

### DB 백업 (자체호스팅 postgres, 2026-08-07 Phase 3 이관 완료)
DB는 Supabase에서 자체호스팅 postgres(`kista-postgres` 컨테이너, A 서버)로 이관 완료됐다. 옛 `db-backup.yml`(Supabase workflow_dispatch 수동 백업)은 삭제됨 — `kista-infra` 레포의 `scripts/backup.sh`가 유일한 백업 경로다.

- `kista-infra`의 `scripts/backup.sh`: `docker exec kista-postgres pg_dump -U kista kistadb -Fc` → `gpg --symmetric`(`BACKUP_ENCRYPTION_KEY`) → `oci os object put`(Object Storage, Always Free 20GB), 30일 롤링 삭제
- 서버 cron: `0 2 * * * /opt/kista-infra/scripts/backup.sh` (매일 02:00 KST, 매매·pg 부하 없는 시각)
- `oci` CLI는 `/usr/local/bin/oci` 심볼릭 링크로 설치(원본 `/home/ubuntu/bin/oci`) — cron 기본 PATH에 `/home/ubuntu/bin`이 없어 심볼릭 링크로 우회
- 수동 백업: 서버에서 `/opt/kista-infra/scripts/backup.sh` 직접 실행
- Supabase 프로젝트(nnpchirdkaxvdybhqzct)는 2026-08-14 영구 삭제 완료 — 서버 `.env.supabase-rollback`도 함께 삭제됨. 롤백 경로 없음, 백업/복구는 전적으로 위 `kista-infra` Object Storage 경로에 의존

### 복구
1. Object Storage에서 다운로드 → `gpg --batch --yes --passphrase "$BACKUP_ENCRYPTION_KEY" -d backup-YYYYMMDD.sql.gpg -o backup.dump`
2. `docker exec -i kista-postgres pg_restore --no-owner --no-privileges -U kista -d kistadb < backup.dump` (재해 복구로 DB 자체가 없으면 먼저 `createdb -U kista kistadb`)
3. 복원 후 `flyway_schema_history` 최신 버전이 배포 코드의 마이그레이션 버전과 일치하는지 확인 — 불일치 시 앱 기동 실패
4. 앱 재기동 후 `/actuator/health` 200 확인 + 텔레그램 시작 알림 수신 확인

### 키 백업 (분실 시 복구 불가 — DB 백업과 별도 보관 필수)
- `AES_ENCRYPTION_KEY` — 분실 시 accounts의 암호화 컬럼(계좌번호·API 키) 전체 복호화 불가 → 사용자 재등록 필요
- `JWT_SIGNING_KEY` — 분실 시 전체 사용자 재로그인 (치명적이지 않음)
- `BACKUP_ENCRYPTION_KEY` — 분실 시 GitHub Actions에 쌓인 모든 DB 백업 artifact 복호화 불가 (백업 자체가 무의미해짐)
- 확인: kista-infra 레포의 GPG 암호화 시크릿 파일에 원본 보관, GH secret은 `gh secret list`로 이름만 확인 가능(값은 안 보임)
```
