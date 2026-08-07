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

### Fly.io 배포 방식 (폐지됨)
- OCI 전환 완료로 `.github/workflows/fly-deploy.yml`(긴급 롤백용 `workflow_dispatch` 전용) 삭제 — main push 자동 배포는 서버 배포(`server-deploy.yml`)만 담당
- 리전: `nrt` (도쿄), 최소 1대 상시 유지 (`min_machines_running=1`) — 스케쥴러 04:30 KST 실행 보장
- `fly.toml`의 배포 전략은 `[deploy]` 섹션의 `strategy` 키다 — **`[deployment]`는 flyctl이 인식하지 못하는 오타 섹션名**이라 조용히 무시된다(`flyctl config show --local --toml`로 로컬 파싱 결과를 직접 비교해야 확인 가능, `config show` 기본 동작은 로컬 파일이 아닌 Fly 서비스에 저장된 원격 설정을 보여줘서 이 오타를 못 잡는다). Redis fencing 도입 시 이 오타 때문에 의도했던 `immediate`(pre-branch DB/JVM token protocol과 Redis fencing binary가 겹치지 않게 하는 1회성 protocol cutover)가 실제로는 한 번도 적용되지 않고 매번 flyctl 기본값인 `rolling`으로 배포됐다 — 그런데도 신·구 프로토콜이 실제로 충돌하는 사고는 없었다(2026-07-22 확인). 현재 `strategy = "rolling"`으로 명시 고정돼 있다.
- 향후 유사한 protocol cutover가 필요하면: (1) `[deploy] strategy = "immediate"`로 변경 후 `flyctl config show --local --toml`로 로컬 파싱 결과에 `[deploy] strategy = 'immediate'`가 실제로 찍히는지 반드시 확인, (2) 배포 로그에 `Updating existing machines ... with immediate strategy` 문구로 실제 적용을 재확인, (3) `fly status`로 정상 기동 확인 후 별도 커밋으로 `rolling` 복원 — 오타로 검증 자체가 무력화됐던 사례가 있으니 "커밋했다"가 아니라 "실제 적용을 확인했다"를 기준으로 삼을 것.

### 서버 배포 방식 (현재 OCI)
- `.github/workflows/server-deploy.yml` — `main` push 시 GitHub Actions가 전체 테스트 스위트(ArchUnit 포함) 검증 → `linux/arm64` GHCR 이미지 빌드·push → 매매 시간대 가드 통과 후 SSH로 서버에 배포
- 배포 파일: `deploy/server/docker-compose.yml`(kista-api 단일 서비스 — caddy·redis는 kista-infra 레포가 전담), `deploy/server/README.md`(초기 서버 설정·롤백 runbook·커트오버 체크리스트 전체 — 상세 절차는 이 README 참고)
- `kista-infra`(private, `/opt/kista-infra/`) 레포가 caddy(양 도메인 리버스 프록시)·postgres·redis·백업 cron을 전담한다. `shared_net`(caddy↔kista-api/kista-ui)·`data_net`(postgres·redis↔kista-api만, kista-ui 미가입) 두 개의 external Docker 네트워크로 앱↔인프라 경계를 분리한다. **인스턴스 재편·컷오버 완료(2026-08-07)** — kista-api-server(A)가 caddy·postgres·redis·kista-api·kista-ui를 모두 올인원으로 호스팅, kista-ui-server는 삭제됨, DB는 Supabase에서 자체호스팅 postgres(`postgres:5432/kistadb`)로 이관 완료. fida-server만 별도 유지.
- **현재 인스턴스는 OCI `VM.Standard.A1.Flex`(Ampere arm64), 2 OCPU, 12GB RAM, 부트 볼륨 50GB, Ubuntu 24.04** — 워크플로 `platforms` 값(`linux/arm64`)과 인스턴스 아키텍처가 항상 일치해야 하며, 인스턴스를 다른 아키텍처로 재생성하면 `server-deploy.yml`의 `platforms` 값도 함께 변경 필요
- **OCI 볼륨은 in-place 축소 불가, OCPU·메모리는 가능** — 최초 12GB/부트 200GB로 생성했다가 free-tier 리전 스토리지(200GB) 전량을 부트 볼륨 하나가 점유해 다른 인스턴스를 만들 여유가 없어져 부트 볼륨만 50GB로 재생성함(2026-08-03). 볼륨을 줄여야 하면 재생성이 유일한 방법 — 아래 무중단 재생성 노하우 참고. 반면 OCPU·메모리는 Flexible shape 속성이라 `oci compute instance update --shape-config '{"ocpus":N,"memoryInGBs":M}'`로 살아있는 인스턴스에서 바로 변경 가능(적용에 재부팅 필요, IP·볼륨 유지) — 재생성 없이 스펙만 조정할 땐 이 경로를 우선 검토
- **예약 공인 IP 재할당으로 무중단 호스트 교체**: 공인 IP를 처음부터 Reserved(에페메럴 아님)로 할당해두면, 인스턴스 재생성(스펙 변경·볼륨 축소 등) 시 새 인스턴스를 임시 공인 IP로 완전히 기동·스모크 테스트한 뒤 `oci network public-ip update --private-ip-id <새 인스턴스 private-ip-ocid>`로 예약 IP만 재할당하면 된다 — 도메인·DNS·GitHub Secret(`SERVER_HOST`)·카카오 OAuth·CORS 전부 무변경. old 인스턴스는 외부 검증 통과 전까지 종료하지 않는 것이 핵심 안전장치(문제 발생 시 예약 IP를 old로 즉시 되돌려 롤백)
- Caddy가 80/443만 외부에 공개하고 HTTPS를 종료해 `kista-api:8080`으로 reverse proxy — 서버 방화벽에서 `8080`은 절대 공개하지 않는다
- GitHub Secrets: `SERVER_HOST`(서버 IP/도메인), `SERVER_USER`(SSH 사용자명), `SERVER_SSH_KEY`(SSH 개인키), `SERVER_SSH_PORT`(기본값 22, 선택) — 이 레포의 Actions는 `.env`를 렌더링하지 않는다. `/opt/kista-api/.env`는 `kista-infra` 레포의 배포 워크플로가 `secrets/kista-api.env.gpg`를 복호화해 매 배포마다 렌더링·덮어쓴다
- `.env` 필수 키는 `deploy/server/README.md`의 ".env 내용" 섹션 참고 — `API_DOMAIN`은 헬스체크 대상 도메인을 문서화하는 값일 뿐, kista-infra Caddy의 실제 라우팅은 kista-infra 자신의 `.env`(`infra.env.gpg`)에 담긴 `API_DOMAIN` 값을 따로 참조한다(둘은 같은 값이지만 소스가 다름)
- **Redis는 자체 호스팅**(kista-infra 레포의 `docker-compose.yml`의 `redis` 서비스, AOF 영속성) — Fly.io는 다중 인스턴스 가능성 때문에 외부 공유 Redis(Upstash)가 필수였지만, OCI는 단일 인스턴스라 로컬 Redis 하나로 "모든 운영 인스턴스가 같은 Redis를 봐야 함" 제약이 자동 충족됨. `REDIS_URL`은 `docker-compose.yml`에 `redis://redis:6379`로 하드코딩되어 있어 `.env` 설정 불필요
- Fly.io의 기존 Redis("Fly Redis" 애드온, `fly-*-redis.upstash.io`)는 Fly 사설 네트워크(`fdaa::/16`, 6PN 전용) 주소라 **외부에서 접근 불가** — 커트오버 시 이 값을 그대로 재사용하려다 실측(`fly ssh console`로 조회 후 외부에서 접속 시도 → `Network is unreachable`)으로 확인됨. 벤더를 완전히 바꾸는 마이그레이션에서는 Fly Redis를 승계할 수 없다는 뜻이라, 새 Redis(관리형이든 자체 호스팅이든)를 처음부터 새로 구성해야 함

### Fly↔서버(OCI) 병행 운영 전환 안전성 (2026-08-03 커트오버 실측)
- 커트오버 기간 중 Fly와 서버(OCI)가 같은 Supabase DB를 동시에 바라보는 시점이 발생한다. 매매 스케쥴러 중복 실행 방지는 Redis가 아니라 **Postgres 기반 분산 락**(`SchedulerLockService`, `scheduler_locks` 테이블, DB 서버 시각 `now()` 기준 `INSERT ... ON CONFLICT ... WHERE lock_until <= now()`)이라 두 인스턴스가 서로 다른 호스트라도 시계 편차 없이 안전하게 경쟁한다 — 어느 한쪽만 매 사이클 실행된다
- 단, 이 락은 "중복 실행"만 막을 뿐 "어느 쪽이 실행되는지"는 보장하지 않는다 — Fly가 이겨도 동작 자체는 가능하므로, **커트오버 기간에는 Fly 머신을 `fly scale count 0`으로 내려 능동적 경쟁 자체를 제거하는 것을 권장**(이미지·시크릿·릴리스 히스토리는 보존되어 `fly scale count 1`로 즉시 롤백 가능 — `fly apps destroy`와는 다름). 브로커가 IP 화이트리스트(Toss 등)를 쓰면 등록된 IP가 아닌 호스트의 매매 API 호출은 어차피 거부되지만, 화이트리스트가 없는 브로커(KIS 등)는 이 보호를 못 받으므로 스케일다운이 여전히 필요

### Fly.io 다중 인스턴스 Toss 토큰 조정
- 모든 Fly 인스턴스의 Toss 계좌·관리자 canonical token은 Upstash Redis hash로 공유한다. OAuth 실제 만료보다 5분 짧은 TTL, fencing generation, expiry epoch를 저장한다. Toss는 PostgreSQL `broker_tokens`와 JPA pool을 사용하지 않는다. KIS는 기존 PostgreSQL token cache를 유지한다.
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

### Fly.io 배포 모니터링
```bash
# 운영 로그 실시간 조회
fly logs -a kista-api                                           # kista-api 운영 로그
vercel logs                                                     # kista-ui 운영 로그

# 헬스 체크 / 배포 상태
curl https://kista-api.fly.dev/actuator/health
fly status -a kista-api
# 수동 배포 (서버 전환 후 workflow_dispatch 전용 — 커트오버 기간 긴급 롤백용)
fly deploy --app kista-api
# 증상: "Connection to localhost:5432 refused" = DB_URL 환경변수 미설정
# 로컬 컨테이너명: kista-api-app-1 (앱), kista-api-postgres-1 (DB)
# 로컬 로그 확인: ~/.local/bin/docker --context desktop-linux logs kista-api-app-1 --tail=200
```

### 외부 모니터링/알림
Fly.io 자체 헬스체크(`fly.toml`)는 실패한 machine을 재시작할 뿐 사람에게 알리지 않음 — 아래 3개는 사람이 실제로 장애를 인지하기 위한 외부 계층.

- **가동 여부**: UptimeRobot(무료) → `https://kista-api.fly.dev/actuator/health` 5분 간격 외부 체크, 알림 채널(이메일/텔레그램) 연결
- **스케줄러 미실행 감지(dead-man's switch)**: healthchecks.io(무료) — `HeartbeatPort`/`HeartbeatAdapter`가 `TradingOpenScheduler`(월~금 22:30 KST)·`TradingCloseScheduler`(화~토 04:30 KST) 완료 시 ping. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전) — healthchecks.io 콘솔에서 각 체크 예상 주기 등록 필요
- **메트릭 가시성**: Grafana Cloud OTLP push — 호스팅 위치(Fly.io/서버) 무관하게 `micrometer-registry-otlp`가 앱에서 직접 `management.otlp.metrics.export.url`로 60초 간격 push. 서버(OCI)에서도 별도 Alloy 사이드카 없이 동일하게 동작 (`GRAFANA_CLOUD_OTLP_*` 환경변수, → "Fly.io 환경변수 설정" 참고 — 서버는 `deploy/server/README.md`의 `.env` 목록에 동일 변수 있음)
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

### DB 백업 (자체호스팅 postgres, 2026-08-07 Phase 3 이관 완료)
DB는 Supabase에서 자체호스팅 postgres(`kista-postgres` 컨테이너, A 서버)로 이관 완료됐다. 옛 `db-backup.yml`(Supabase workflow_dispatch 수동 백업)은 삭제됨 — `kista-infra` 레포의 `scripts/backup.sh`가 유일한 백업 경로다.

- `kista-infra`의 `scripts/backup.sh`: `docker exec kista-postgres pg_dump -U kista kistadb -Fc` → `gpg --symmetric`(`BACKUP_ENCRYPTION_KEY`) → `oci os object put`(Object Storage, Always Free 20GB), 30일 롤링 삭제
- 서버 cron: `0 2 * * * /opt/kista-infra/scripts/backup.sh` (매일 02:00 KST, 매매·pg 부하 없는 시각)
- `oci` CLI는 `/usr/local/bin/oci` 심볼릭 링크로 설치(원본 `/home/ubuntu/bin/oci`) — cron 기본 PATH에 `/home/ubuntu/bin`이 없어 심볼릭 링크로 우회
- 수동 백업: 서버에서 `/opt/kista-infra/scripts/backup.sh` 직접 실행
- 이관 시 사용한 Supabase 자격증명은 `.env.production`(로컬, gitignored)과 kista-api `.env.supabase-rollback`(서버, `/opt/kista-api/`)에만 남아있다 — Supabase 프로젝트 자체는 롤백 대비 최소 1주 보존 후 정리

### 복구
1. Object Storage에서 다운로드 → `gpg --batch --yes --passphrase "$BACKUP_ENCRYPTION_KEY" -d backup-YYYYMMDD.sql.gpg -o backup.dump`
2. `docker exec -i kista-postgres pg_restore --no-owner --no-privileges -U kista -d kistadb < backup.dump` (재해 복구로 DB 자체가 없으면 먼저 `createdb -U kista kistadb`)
3. 복원 후 `flyway_schema_history` 최신 버전이 배포 코드의 마이그레이션 버전과 일치하는지 확인 — 불일치 시 앱 기동 실패
4. 앱 재기동 후 `/actuator/health` 200 확인 + 텔레그램 시작 알림 수신 확인

### 키 백업 (분실 시 복구 불가 — DB 백업과 별도 보관 필수)
- `AES_ENCRYPTION_KEY` — 분실 시 accounts의 암호화 컬럼(계좌번호·API 키) 전체 복호화 불가 → 사용자 재등록 필요
- `JWT_SIGNING_KEY` — 분실 시 전체 사용자 재로그인 (치명적이지 않음)
- `BACKUP_ENCRYPTION_KEY` — 분실 시 GitHub Actions에 쌓인 모든 DB 백업 artifact 복호화 불가 (백업 자체가 무의미해짐)
- 확인: `fly secrets list -a kista-api` (값은 안 보임 — 원본을 별도 보관해야 함), GH secret은 `gh secret list`로 이름만 확인 가능
```
