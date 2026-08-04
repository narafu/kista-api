# PostgreSQL 자체 호스팅 + kista 올인원 통합 설계

- **작성일**: 2026-08-04
- **상태**: 설계 확정 (구현 대기) — OCI SSH 키·`.env` 시크릿이 있는 PC에서 실행 필요
- **범위**: 신규 `kista-infra` 레포 + `kista-api` + `kista-ui` 3개 레포에 걸친 인프라 재편

## Context (왜 하는가)

현재 kista는 두 OCI 인스턴스에 흩어져 있고, DB는 외부 Supabase(Free)에 있다.

- **인스턴스 A** (`/opt/kista-ui/`): `kista-ui` + `kista-ui-caddy`(80/443 점유)
- **인스턴스 B** (`/opt/kista-api/`): `kista-api` + `kista-api-caddy`(80/443 점유) + `redis`
- **DB**: Supabase Free PostgreSQL (앱은 pgbouncer 6543 JDBC 직결, Supabase 고유 기능 미사용)

**이전 동인**: 앱→타 벤더 DB 인터넷 홉 제거(스케쥴러 배치 레이턴시), 운영 통합·단순화, 벤더 종속 회피. Supabase Free는 자동 백업이 없어 이미 GitHub Actions `pg_dump`로 자체 백업 중 — 이전해도 "관리형 백업"을 잃는 게 아니라 백업 저장 내구성만 본인 책임이 된다.

## 확정 결정

1. **DB = 순수 `postgres:17` 단일 컨테이너** 자체 호스팅 (풀 Supabase 스택 아님 — 앱은 JDBC 연결만 사용, CI `verify` job·로컬 compose와 버전 일치).
2. **레포 3분할**: 신규 **`kista-infra`**(플랫폼) + `kista-api`(앱) + `kista-ui`(앱).
3. **민감정보 = public repo + GitHub Actions Secrets 일원화**, `.env`는 로컬 개발 전용. 서버 `.env`는 배포 시 Actions가 Secrets에서 렌더링(수기 관리 폐지).
4. **인스턴스 A = kista 올인원**, 인스턴스 B = 별도 SSH 키로 격리·순수 예약, 인스턴스 C = 제거.

## 목표 상태 (인스턴스 A)

```
/opt/kista-infra/   ← caddy(edge 80/443) + postgres + redis + shared_net(생성 주체) + 백업 cron
/opt/kista-api/     ← kista-api 컨테이너만 (shared_net join)
/opt/kista-ui/      ← kista-ui 컨테이너만 (shared_net join)
```

**프리티어 한도**: A(2 OCPU/12GB)+B(2 OCPU/12GB)=Ampere 한도 4/24 정확히 일치. 볼륨 A(100)+B(100)=200GB=리전 한도 정확히 일치(C 제거로 50GB 확보 필요).

**메모리 예산(A, 12GB)**: api 4G + pg 3G + ui 1.5G + redis .25G + caddy .06G + OS ~1.5G ≈ 10.3G. Supabase Free 크기 DB(≤500MB)면 페이지캐시 여유 충분. 유일한 실질 스파이크는 pg_dump가 앱·DB와 동시에 도는 백업 시각(02:00 KST 유지로 회피).

---

## 민감정보 관리 설계 (public + Actions Secrets 일원화 + 보완)

**전제 교정**: 레포 public/private은 Actions Secrets 기밀성과 무관(Secrets는 항상 암호화 저장·런타임 주입, 공개 노출 없음). 또한 **SSH 배포키가 이미 Actions에 있어 Actions는 이미 서버 `.env`를 읽을 수 있으므로**, 시크릿을 Actions로 일원화해도 신뢰 경계가 넓어지지 않는다(서버 `.env` 수기 이원화는 실효 없는 중복).

**설계**:
- 각 레포에 **단일 `*_ENV` secret**(레포별 `.env` 전체 내용)을 둔다 — 30여 개 개별 secret 관리·드리프트 회피, 원자적 렌더링.
  - `kista-infra` → `INFRA_ENV`: `DB_PASSWORD`, `API_DOMAIN`, `UI_DOMAIN`, `BACKUP_ENCRYPTION_KEY`, OCI Object Storage 크리덴셜
  - `kista-api` → `API_ENV`: 앱 시크릿 전부(`AES_ENCRYPTION_KEY`/`JWT_SIGNING_KEY`/`KAKAO_*`/`TELEGRAM_*`/`INTERNAL_API_TOKEN`/`TOSS_ADMIN_*`/`ALPACA_*`/`FIREBASE_*`/`GRAFANA_*`/`HEARTBEAT_*`/`CORS_ALLOWED_ORIGINS`) + `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`
  - `kista-ui` → `UI_ENV`: `UI_DOMAIN`/`API_BASE_URL`(런타임), `NEXT_PUBLIC_*`는 기존대로 빌드 인자 secret
- 배포 job이 `*_ENV` secret을 서버 `${DEPLOY_PATH}/.env`로 write → 기존 "필수 키 검증" 스텝 유지.
- `DB_PASSWORD`는 `INFRA_ENV`(postgres 생성)와 `API_ENV`(DB_URL)에 **동일 값** 필요 — 값 일치가 조율 포인트.

**4개 보완책 (반드시 함께)**:
1. **모든 action을 SHA로 핀** — kista-api는 이미 적용됨. **kista-ui는 `@v4`/`@v3` 가변 태그 → SHA 핀으로 교체.** 신규 kista-infra도 SHA 핀. (오염된 action의 시크릿 유출 방어 — 핵심 방어)
2. **GitHub Environments(`production`)로 시크릿 스코프** — deploy job만 시크릿 접근. `environment: production` 이미 사용 중.
3. **fork PR 시크릿 차단 확인** — deploy는 `push:main` 전용(fork push 불가). ci.yml/react-doctor.yml이 `pull_request_target`+시크릿 조합을 쓰지 않는지 점검.
4. **오프라인 키 백업** — Secrets는 읽기 불가(write-only). `AES_ENCRYPTION_KEY`(분실 시 전 계좌 자격증명 복구 불가)·`JWT_SIGNING_KEY`·`BACKUP_ENCRYPTION_KEY`는 Secrets와 별도로 오프라인 안전 보관.
- **승인 게이트**: push 승인은 제거하되, 원하면 `production` Environment의 required reviewers로 이동(오염 action·실수 배포 방어의 값싼 보험 — 금융 앱이라 유지 권장, 선택).

---

## Phase 0 — 인스턴스 재편 (비파괴적 사전 준비)

1. **인스턴스 C 종료** → 블록스토리지 50GB 반환.
2. **A·B 스펙 조정**: OCPU/메모리 `oci compute instance update --shape-config '{"ocpus":2,"memoryInGBs":12}'`(재부팅, IP·볼륨 유지). 부트 볼륨 50→100GB는 **온라인 확장**(축소만 재생성 필요) → 볼륨 확장 후 `sudo growpart` + `resize2fs`. 재생성 불필요.
3. **SSH 키 분리**: A·B 각각 새 keypair, B는 미래 서비스 전용 키로 완전 격리.
4. **정적 공인 IP**: A는 Reserved Public IP(무중단 호스트 교체 대비), 도메인 A레코드 확인.

검증: `oci compute instance get` shape 반영, `df -h` 볼륨 확장, 새 키 SSH 접속.

---

## Phase 1 — kista-infra 레포 신설 (플랫폼)

신규 레포 `kista-infra` → `/opt/kista-infra/`. **edge Caddy + postgres + redis + shared_net + 백업 cron** 소유.

- `docker-compose.yml`:
  - **caddy**(edge): 80/443, `networks: [shared_net]`, 최상위 `networks: shared_net: {external: true}`. 두 앱 도메인 라우팅.
  - **postgres:17**: `POSTGRES_DB=kistadb`, `POSTGRES_USER=kista`, `POSTGRES_PASSWORD=${DB_PASSWORD}`, `postgres_data` named volume, `mem_limit: 3072m`, `shm_size: 256m`, `pg_isready` healthcheck. **5432 외부 미노출**(방화벽·`ports` 금지, `expose`도 불필요 — shared_net으로 앱만 접근).
  - **redis**: 기존 kista-api의 redis 이관(AOF `appendonly yes`, `redis_data` volume, `mem_limit: 256m`).
  - shared_net은 이 compose가 external network로 참조하되, 최초 `docker network create shared_net` 1회 수동 생성.
- `Caddyfile`(infra 소유, 두 upstream + 두 도메인):
  ```caddyfile
  {$API_DOMAIN} {
  	encode zstd gzip
  	reverse_proxy kista-api:8080 {
  		lb_try_duration 120s
  		lb_try_interval 500ms
  		health_uri /actuator/health/liveness
  		health_interval 10s
  		health_timeout 5s
  	}
  }
  {$UI_DOMAIN} {
  	encode zstd gzip
  	reverse_proxy kista-ui:3000 {
  		lb_try_duration 120s
  		lb_try_interval 500ms
  		health_uri /api/health
  		health_interval 10s
  		health_timeout 5s
  	}
  }
  ```
- `server-deploy.yml`(infra): `INFRA_ENV` → `.env` 렌더링, `docker network create shared_net`(멱등), `docker compose up -d`(caddy/postgres/redis). SHA 핀·`environment: production` 적용.
- 백업 cron 스크립트(Phase 4).

검증: A에서 infra compose 기동 → `docker network inspect shared_net`, `pg_isready`, caddy 기동(앱 미기동 시 502는 정상).

---

## Phase 2 — 앱 레포에서 Caddy 제거 + shared_net join

- **kista-api** (`deploy/server/docker-compose.yml`): `caddy`·`redis` 서비스 **삭제**(infra로 이관), `caddy_data`/`caddy_config`/redis 볼륨 삭제. `kista-api`에 `networks: [shared_net]`(external) 추가. `REDIS_URL: redis://redis:6379`는 shared_net의 infra redis를 컨테이너명으로 참조(유지). `server-deploy.yml`: `docker compose up -d --no-deps kista-api`만.
- **kista-ui** (`deploy/server/docker-compose.yml`): `caddy` 서비스·볼륨 삭제. `kista-ui`에 `networks: [shared_net]` 추가. `server-deploy.yml`에서 `docker compose up -d caddy` 라인 제거.
- 두 앱 `container_name`(`kista-api`/`kista-ui`)이 shared_net DNS 이름 제공 → infra Caddy가 컨테이너명으로 프록시.

검증: infra + 두 앱 기동 후 `curl -I https://{API_DOMAIN}/actuator/health`·`https://{UI_DOMAIN}` 각각 200, Caddy 로그 두 사이트 인증서 발급.

---

## Phase 3 — 데이터 이관 (Supabase → self-host) + 배포 타깃 이전

market-closed 주말(매매시간대 배포 가드 밖) 단일 정비 창에서 수행.

1. **덤프 전 사전 점검(가정 금지)**: Supabase에서 `SELECT pg_database_size(current_database());`, `SELECT extname FROM pg_extension;`, non-public 스키마·앱 테이블 확장 의존 여부 확인(앱이 `public`+`flyway_schema_history`만 쓰는지).
2. **이관**: `pg_dump "$SUPABASE_DB_URL" --no-owner --no-privileges -Fc -f kista.dump`(session mode 5432) → infra postgres에 `CREATE DATABASE kistadb OWNER kista;` → `pg_restore --no-owner --no-privileges -d kistadb kista.dump`. **`flyway_schema_history` 이관·최신 버전이 배포 이미지 마이그레이션 버전과 일치 확인**.
3. **`DB_URL` 재지정**: `jdbc:postgresql://postgres:5432/kistadb`(pgbouncer 제거 — 단일 인스턴스+HikariCP라 pooler 불필요). `API_ENV`의 DB 값 갱신.
4. **kista-api 배포 타깃 B→A**: kista-api GitHub Secrets `SERVER_HOST`/`SERVER_SSH_KEY`를 A로 변경. B의 기존 스택은 롤백 대비 잠시 유지 후 정리, B는 예약 상태로 비움.
5. **커트오버 연동 점검**(도메인 유지 시 대부분 무변경): CORS·카카오 OAuth·Telegram webhook·FIDA 내부호출 URL·UptimeRobot·healthchecks.io.

검증: api 기동 후 `/actuator/health`(DB 포함) 200, 로그인·전략 read/write 스모크, 스케쥴러 수동 트리거 DB 왕복.

---

## Phase 4 — 백업 재설계 (infra 소유, 반드시 박스 밖으로)

GitHub 러너(외부)는 비공개 5432 접근 불가 → 기존 `db-backup.yml` 깨짐. **infra 서버 cron으로 이관 + 외부 반출**(on-box는 VM 손실 시 함께 소멸).

- infra 서버 cron: `docker exec kista-postgres pg_dump -U kista kistadb -Fc` → GPG 암호화(`BACKUP_ENCRYPTION_KEY`) → **OCI Object Storage(Always Free 20GB)** 업로드(`oci os object put`) 또는 rclone. 매일 02:00 KST, 보존 30일 롤링.
- kista-api의 `db-backup.yml`: 삭제 또는 `workflow_dispatch` 수동 보조로 격하(스케줄 제거).
- 복구 runbook: 복호화 → `pg_restore` → flyway 버전 확인 → 헬스 확인.

검증: cron 1회 수동 실행 → 외부 저장소 암호화 파일 도착 → 복호화·`pg_restore --list` 무결성.

---

## Phase 5 — 민감정보 일원화 적용 + 보완책

- 3개 레포에 `*_ENV` 단일 secret 구성, 각 `server-deploy.yml`이 서버 `.env` 렌더링(위 "민감정보 관리 설계").
- **kista-ui·kista-infra 워크플로 action SHA 핀** 적용(kista-api는 기존 유지). fork PR 시크릿 노출 점검. `production` Environment 스코프 확인.
- 오프라인 키 백업(AES/JWT/BACKUP) 별도 보관 확인.
- (선택) push 승인 → `production` Environment required reviewers로 이동.

검증: secret 변경 후 재배포 → 서버 `.env` 의도대로 렌더링·앱 정상 기동, Actions 로그 평문 시크릿 미노출.

---

## 인스턴스 B

앱·러너 없이 **순수 예약** 상태로 비워둠(별도 SSH 키로 격리). 미래 서비스 전용. self-hosted 러너는 도입하지 않음 — public 레포라 Actions 무료 분이 무제한이라 비용 명분이 없고, 상시 러너 관리 부담 + B의 순수 예약 성격 훼손을 감수할 이득(빌드 속도)이 부족.

---

## 문서 반영 (구현과 동일 작업에서)

- `kista-api/docs/agents/docker-infra.md`: DB 자체 호스팅·백업 재설계·infra 레포 분리·단일 Caddy·인스턴스 재편·Actions Secrets 일원화 반영. "DB 백업(Supabase 운영)"·"운영→로컬 마이그레이션" 절 갱신.
- `kista-api/deploy/server/README.md`·`kista-ui/deploy/server/README.md`: Caddy·redis 제거, shared_net join, `.env` Actions 렌더링 반영.
- `kista-infra/README.md`(신규): 플랫폼 레이아웃·기동 순서·백업 runbook·롤백.
- 양 레포 `README.md`: 배포 파이프라인·아키텍처 다이어그램에 DB 위치·호스트·레포 구조 변경 반영(드리프트 감지 규칙).

---

## 고려했으나 채택하지 않은 대안

- **private repo + self-hosted 러너**: 코드 비공개(금융 앱 정찰 방어)에 유리하나, 사용자가 public+Actions 일원화를 선택 → 미채택.
- **api 레포가 플랫폼(caddy/db/redis)까지 소유**: 레포 2개로 조율은 적지만 ui가 api Caddy에 결합·api 책임 비대 → 별도 infra 레포 채택.
- **풀 Supabase 스택 self-host**: 컨테이너 ~10개·수 GB RAM 낭비, 고유 기능 미사용 → 순수 postgres:17.
- **SOPS/age 암호화 .env를 레포 커밋**: 버전관리 SSOT지만 사용자가 Actions Secrets 일원화 선택 → 미채택.

---

## End-to-end 검증 (전체 커트오버 후)

1. `curl -I https://{API_DOMAIN}/actuator/health` 200(DB·Redis) + `https://{UI_DOMAIN}` 200.
2. 카카오 OAuth 로그인 → 대시보드 → 전략 read/write 왕복.
3. 스케쥴러 수동 트리거(또는 다음 개장/마감)로 DB·브로커·리포트 경로 + healthchecks.io ping.
4. 백업 cron 1회 → 외부 저장소 암호화 파일 → 복호화·`pg_restore --list` 무결성.
5. `main` push 자동 배포(3레포) → 헬스 게이트 통과 → 롤백 리허설.
6. 인스턴스 B는 앱·러너 없이 순수 예약, SSH 키 분리 확인.

---

## 다음 단계

이 설계를 기반으로 실제 구현(compose·Caddyfile·워크플로 YAML·백업 스크립트 작성, 데이터 이관)은 **OCI SSH 키와 `.env` 시크릿이 있는 PC**에서 진행한다. 구현 착수 시 `superpowers:writing-plans`로 페이즈별 실행 플랜을 작성한 뒤 진행할 것.
