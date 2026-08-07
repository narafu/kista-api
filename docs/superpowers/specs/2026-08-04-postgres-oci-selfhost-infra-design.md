# PostgreSQL 자체 호스팅 + kista 올인원 통합 — 구현 플랜

- **작성일**: 2026-08-04 (v2 — 전면 재검토 반영, 구현 중심 재작성)
- **상태**: Phase 0(볼륨 확장·인스턴스 통합)·1·2·4·5 완료. Phase 3(Supabase→자체호스팅 DB 실제 데이터 이관)은 사전 점검·리허설·롤백 경로 준비까지 완료, 실제 정지→덤프→복원→전환(2~6번)만 대기 — DB는 여전히 Supabase. 다음 안전 창: 토요일 06:20 KST 이후 ~ 월요일 22:20 KST 이전
- **실행 환경 전제**: 이 문서를 작성한 PC에는 oci-cli·OCI SSH 키·운영 환경변수가 없음 → **구현은 해당 자원이 있는 PC에서 진행**. 실행 계정은 Fable 한도 없음 → 오케스트레이터 = **Opus 4.8**(플랜·검토·판단) + **Sonnet 5**(실행, opusplan 기본). 구현 서브에이전트 = Sonnet 5, 문서 = Haiku 4.5, 검토자 = Opus 4.8

## 확정 사항

- DB: **순수 `postgres:17` 단일 컨테이너** (Supabase 스택 아님 — 앱은 JDBC만 사용). `DB_URL=jdbc:postgresql://postgres:5432/kistadb` (pgbouncer 제거 — 단일 인스턴스 + HikariCP라 pooler 불필요)
- 레포: 신규 **`kista-infra`(private)** + `kista-api`(public) + `kista-ui`(public)
- 시크릿: 암호화 `.gpg` 파일을 kista-infra에 커밋, passphrase(`openssl rand -base64 32` 수준 무작위)만 GitHub Secrets(`ENV_PASSPHRASE`) + 비밀번호 관리자 이중 보관. `.env`=로컬 전용(gitignore), `.env.example`=키 목록 커밋
- 인스턴스: **A = kista 올인원**(ui+api+db+redis+caddy), **B = fida 등 향후 서비스**(SSH 키 분리), **C = 제거**
- fida: B에서 독자 운영. kista-infra는 A만 담당. fida→api `/api/internal/**`는 공인 도메인 경유(무변경)
- 유출 대응 원칙: passphrase 유출 의심 시 재암호화가 아니라 **실제 시크릿 전부 로테이션**

## 목표 상태 (인스턴스 A)

```
/opt/kista-infra/   caddy(edge 80/443) + postgres + redis + 백업 cron
/opt/kista-api/     kista-api 컨테이너만
/opt/kista-ui/      kista-ui 컨테이너만

shared_net (external): caddy ↔ kista-api / kista-ui   (HTTP)
data_net   (external): postgres·redis ↔ kista-api만    (ui는 미가입)
```

메모리(12GB): api 4G + pg 3G + ui 1.5G + redis .25G + caddy .06G + OS ≈ 10.3G. 프리티어: A+B = 4 OCPU/24GB·볼륨 200GB 한도 정확히 일치.

---

## Phase 0 — 인스턴스 재편

1. 인스턴스 C 종료 (블록스토리지 50GB 반환)
2. A·B: `oci compute instance update --shape-config '{"ocpus":2,"memoryInGBs":12}'` (재부팅, IP·볼륨 유지)
3. A·B 부트 볼륨 50→100GB 온라인 확장 → 인스턴스에서 `sudo growpart` + `sudo resize2fs`
4. SSH 키 분리: A·B 각각 새 keypair, B는 향후 서비스 전용
5. A는 Reserved Public IP 확인
6. **API 도메인 DNS TTL → 60초 선인하** (현재 API A레코드는 B의 IP — Phase 3 대비)

검증: shape 반영, `df -h`, 새 키 SSH 접속.

**실행 완료(2026-08-04~08-07, 계획과 다르게 진행)**: 사전 조사 결과 실제 OCI 인스턴스는 계획 당시 이미 3대(`kista-api-server` 2 OCPU/12GB, `kista-ui-server` 1 OCPU/6GB, `fida-server` 1 OCPU/6GB)로 분리되어 있어 "인스턴스 C 종료" 전제가 맞지 않았다. 실제로는 `kista-api-server`를 그대로 인스턴스 A로 채택(부트 볼륨만 50→100GB 온라인 확장, `growpart`+`resize2fs`, 재부팅 없이 완료 — OCPU/메모리는 이미 목표 스펙과 일치해 변경 불필요)하고, `kista-ui-server`를 kista-api-server로 컨테이너 이전 후 종료했다(= 계획의 "C 제거"에 해당, 다만 실제로는 UI가 있던 인스턴스가 C 역할). SSH 키 분리·DNS TTL 사전 인하는 이미 기존 값(TTL 300초)이 충분해 별도 조치 불필요했다. `fida-server`(B)는 변경 없음.

## Phase 1 — kista-infra 레포 신설 (private)

배포 경로 `/opt/kista-infra/`. 구성 파일:

**`docker-compose.yml`**:
- `caddy`(caddy:2.9-alpine): 80/443, `networks: [shared_net]`, `env`로 `API_DOMAIN`/`UI_DOMAIN`만 주입, Caddyfile ro 마운트, caddy_data/caddy_config 볼륨, mem_limit 64m
- `postgres`(postgres:17): `POSTGRES_DB=kistadb`/`POSTGRES_USER=kista`/`POSTGRES_PASSWORD=${DB_PASSWORD}`, postgres_data 볼륨, mem_limit 3072m, shm_size 256m, `pg_isready` healthcheck, **`networks: data_net: {aliases: [postgres]}`**, 호스트 포트 미개방
- `redis`(redis:7-alpine): `--appendonly yes --appendfsync everysec`, redis_data 볼륨, mem_limit 256m, **`networks: data_net: {aliases: [redis]}`**
- 최상위 `networks:` shared_net/data_net 둘 다 `external: true`
- ⚠️ **alias 필수** — `container_name`은 그 이름 그대로만 DNS 해석됨. 앱이 쓰는 `postgres:5432`/`redis:6379`는 alias로 보장

**`Caddyfile`** (기존 두 레포 Caddyfile 병합):
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

**`secrets/`**: `kista-api.env.gpg` / `kista-ui.env.gpg` / `infra.env.gpg` (GPG 대칭 AES-256)
- kista-api.env: 기존 `/opt/kista-api/.env` 전체 + `DB_URL`(신규 로컬 pg)/`DB_USERNAME`/`DB_PASSWORD`
- kista-ui.env: `UI_DOMAIN`/`API_BASE_URL`
- infra.env: `DB_PASSWORD`/`API_DOMAIN`/`UI_DOMAIN`/`BACKUP_ENCRYPTION_KEY`/OCI Object Storage 크리덴셜

**`scripts/env.sh`**: `decrypt|edit|encrypt` 헬퍼 (복호화→편집→재암호화)

**`scripts/backup.sh`**: Phase 4 참고

**`.github/workflows/server-deploy.yml`**: SHA 핀 + `environment: production`. 순서: checkout → `ENV_PASSPHRASE`로 3파일 복호화 → scp로 compose·Caddyfile 업로드 + 3개 서버 경로에 `.env` 렌더링(`/opt/kista-api/.env`, `/opt/kista-ui/.env`, `/opt/kista-infra/.env`) → `docker network create shared_net` + `docker network create data_net`(각각 `2>/dev/null || true`로 멱등) → `docker compose up -d`
- GitHub Secrets(infra): `ENV_PASSPHRASE`, `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_SSH_PORT`

검증: infra 기동 → `docker network inspect`로 alias, `pg_isready`, caddy 기동(앱 미기동 502 정상), `.env` 3개 렌더링.

**실행 완료(2026-08-04)**: kista-infra `a4894b2`(레포 신설) → `a38842a`(리뷰 수정).

## Phase 2 — 앱 레포 수정

**kista-api** (`deploy/server/docker-compose.yml` + `server-deploy.yml`):
- `caddy`·`redis` 서비스와 볼륨(caddy_data/caddy_config/redis_data) 삭제
- `kista-api`에 `networks: [shared_net, data_net]` (둘 다 external) + 최상위 networks 선언
- `REDIS_URL: redis://redis:6379` 유지 (data_net alias)
- 워크플로: `.env` 렌더링 없음(infra 담당), "필수 키 검증" 스텝 유지, `up -d --no-deps kista-api`만
- `db-backup.yml`: Phase 3 후 삭제(또는 workflow_dispatch 전용 격하)

**kista-ui** (`deploy/server/docker-compose.yml` + `server-deploy.yml`):
- `caddy` 서비스·볼륨 삭제, `up -d caddy` 라인 제거
- `kista-ui`에 `networks: [shared_net]` (data_net 미가입)
- `NEXT_PUBLIC_*` 9개는 공개값(클라이언트 번들 노출 설계값) → 평문 파일(`.env.production.public`) 커밋으로 전환, GitHub Secrets 9개 삭제, 워크플로 build-args를 파일 참조로 교체
- **action `@v4`류 가변 태그 → SHA 핀**

**공통 규약**: `.gitignore` = `.env*` 차단 + `!.env.example`(+ui는 `!.env.production.public`). `.env.example`은 `.gpg` 내용과 키 목록 동기.

검증: 3레포 compose config 정합(alias·네트워크·도메인·키 목록 크로스 대조 — Opus 검토자).

**실행 완료(2026-08-04)**: kista-api 워크트리 `infra/pg-oci-selfhost` `6e0e445f`, `dbe78717` / kista-ui 워크트리 `infra/pg-oci-selfhost` `bc587e8`, `109b288`(Task 2·3 구현 커밋).

**문서 반영(Task 4, 2026-08-04)**: kista-api 워크트리 `infra/pg-oci-selfhost` `81fb7448` / kista-ui 워크트리 `infra/pg-oci-selfhost` `cdd0196` / kista-infra `4ccf172`(README 포함).

## Phase 3 — 데이터 이관 + 커트오버 (휴장 주말 단일 정비 창)

> ⚠️ **토폴로지 정정(2026-08-07)**: 원안은 B(별도 인스턴스에서 Supabase를 보는 kista-api)가 남아있어 DNS 플립으로 롤백하는 시나리오였다. 실제로는 인스턴스 통합·kista-ui-server 삭제가 먼저 끝나 kista-api는 처음부터 A 하나에서만 실행 중이고 B는 존재하지 않는다 — 아래 5/7/10번(DNS 플립, GitHub Secrets 서버 변경, B 스택 정리)과 "이중 스케쥴러 금지" 경고는 모두 무의미해졌다. 남은 절차를 실제 토폴로지 기준으로 재작성했다.
>
> ⚠️ **쓰기 유실 구간 주의**: dump 시점 이후 Supabase에 들어오는 쓰기는 복원본에 반영되지 않고 유실된다 — 반드시 **kista-api 정지 → dump → restore → DB_URL 전환 → 기동** 순서를 지킨다. 살아있는 DB를 먼저 dump하고 나중에 전환하는 순서 금지.

1. 사전 점검 (실행 완료 — 아래 "사전 점검 + 리허설 실행 결과" 참고)
2. **A의 kista-api `docker compose stop`**
3. 덤프: `pg_dump "$SUPABASE_DB_URL" --no-owner --no-privileges -n public -Fc -f dump.custom` (session mode 5432, postgres:17 클라이언트). **`-n public` 필수** — Supabase는 `auth`/`storage`/`realtime`/`vault`/`graphql`/`supabase_migrations` 등 관리 스키마를 함께 갖고 있어 스키마 미지정 시 vanilla postgres에 없는 스키마 복원을 시도해 실패한다(앱 테이블은 전부 `public`, 사전 점검에서 확인)
4. 복원: A postgres는 kista-infra `docker-compose.yml`의 `POSTGRES_DB=kistadb`/`POSTGRES_USER=kista`가 최초 기동 시 이미 `kistadb`를 생성해뒀으므로 `CREATE DATABASE` 없이 바로 `pg_restore --no-owner --no-privileges -U kista -d kistadb dump.custom` 실행. `schema "public" already exists` 오류 1건은 정상(모든 신규 DB에 기본 존재)이며 그 외 오류가 나오면 중단. `flyway_schema_history` 최신 버전은 로컬 코드·Supabase 모두 8(사전 점검에서 확인, 드리프트 없음)이므로 배포 이미지 재확인은 형식적 절차
5. `infra.env`의 `DB_PASSWORD`를 확인해 `scripts/env.sh edit kista-api`로 `kista-api.env`의 `DB_URL=jdbc:postgresql://postgres:5432/kistadb`·`DB_USERNAME=kista`·`DB_PASSWORD=<infra.env와 동일값>`으로 교체 → commit/push → kista-infra Actions 배포(급하면 서버에서 `.env` 직접 편집 후 `docker compose up -d --force-recreate kista-api`로 즉시 반영하고, 사후에 kista-infra 레포 시크릿도 동일 값으로 맞춘다)
6. A에서 kista-api 재기동 확인 후 검증: `/actuator/health` 200(DB·Redis), 로그인·전략 read/write, 스케쥴러 수동 트리거, Telegram webhook·CORS·카카오 OAuth·FIDA URL 정상 동작(도메인 불변이라 대부분 무변경 확인 절차)

**롤백**: DNS 플립은 B가 없어 더 이상 유효하지 않다. 대신 전환 전 `.env`를 kista-infra 배포 워크플로가 덮어쓰지 않는 경로 `/opt/kista-api/.env.supabase-rollback`에 미리 백업해뒀다(2026-08-07 스테이징 완료) — 롤백은 `cp /opt/kista-api/.env.supabase-rollback /opt/kista-api/.env && cd /opt/kista-api && docker compose up -d --force-recreate kista-api` 한 번으로 GitHub Actions 경유 없이 즉시 Supabase 연결로 복귀한다. **Supabase는 이관 후 최소 1주 보존.**

검증 후 정리: `db-backup.yml`(현재 workflow_dispatch 전용으로 격하) 완전 삭제, Supabase 자격증명 로테이션 또는 폐기 검토.

**사전 점검 + 리허설 실행 완료(2026-08-07)**:
- Supabase DB 크기 16.7MB — A postgres 여유 공간(91GB) 대비 여유 충분
- 확장기능 5개(`pg_stat_statements`/`pgcrypto`/`plpgsql`/`supabase_vault`/`uuid-ossp`) 설치돼 있으나 마이그레이션 전체 검색 결과 앱 스키마는 `gen_random_uuid()`만 사용 — PG13+ 코어 내장 함수(`pg_catalog.gen_random_uuid`, pgcrypto 함수 아님)로 확인돼 **확장기능 의존 없음**, vanilla `postgres:17`에서 그대로 동작
- 앱 테이블 27개 전부 `public` 스키마(auth/storage/realtime/vault 등은 Supabase 관리 스키마, 앱 데이터 없음)
- 실제 덤프(689KB, `-n public`)를 A의 `kista-postgres` 컨테이너에 `kistadb_rehearsal` 스크래치 DB로 복원 리허설 — 오류 1건(`schema "public" already exists`, 무해)만 발생, 27개 테이블·`flyway_schema_history`(버전 8)·users(2)/accounts(5)/orders(811) 로우 수 정상 확인. 리허설 DB·덤프 파일(로컬·서버 양쪽)은 정리 완료
- 롤백용 `/opt/kista-api/.env.supabase-rollback` 스테이징 완료(현재 운영 `.env`와 동일, Supabase 연결 유지 상태)
- 인스턴스 통합·DNS 컷오버는 이미 완료된 상태: `api.kista-app.com`은 애초에 A를 가리키고 있어 DNS 변경 불필요했고, `kista-app.com`(UI)만 구 `kista-ui-server`에서 A로 전환(Cloudflare A레코드, TTL 300초). Let's Encrypt 인증서는 전환 직후 `docker compose restart caddy`로 재발급 확인. `kista-ui-server` 인스턴스와 미사용 Reserved IP(`134.185.118.35`)는 삭제 완료
- **아직 실행 안 함**: 위 2~6번(실제 정지→덤프→복원→전환→재기동) — 매매 시간대(월~금 22:20~23:40, 화~토 04:20~06:20 KST) 회피 필요, 다음 안전 창은 토요일 06:20 이후 ~ 월요일 22:20 이전

## Phase 4 — 백업 (서버 cron → OCI Object Storage)

기존 `db-backup.yml`은 외부 러너가 비공개 5432에 접근 불가 → 무력화. on-box 백업은 VM 손실 시 함께 소멸하므로 외부 반출이 단일 호스트 구성의 허용 조건.

- `scripts/backup.sh`: `docker exec kista-postgres pg_dump -U kista kistadb -Fc` → `gpg --symmetric`(`BACKUP_ENCRYPTION_KEY`) → `oci os object put`(Always Free 20GB), 30일 롤링 삭제
- cron: 매일 02:00 KST (매매·pg 부하 없는 시각)
- 복구: 다운로드 → 복호화 → `pg_restore` → flyway 버전 확인 → 헬스 확인

검증: 수동 1회 실행 → Object Storage 도착 → 복호화·`pg_restore --list`.

**실행 완료(2026-08-07)**: 서버에 `oci` CLI 설치(`/usr/local/bin/oci` 심볼릭 링크로 cron PATH 문제 회피), `scripts/backup.sh`를 `/opt/kista-infra/scripts/`에 배포, IAM 정책 문법 수정(따옴표 누락으로 최초 `BucketNotFound` 발생 — `target.bucket.name='kista-infra-backups'`로 수정 후 정상화) 후 수동 실행으로 실제 업로드까지 검증. `crontab -e`로 `0 2 * * * /opt/kista-infra/scripts/backup.sh >> /var/log/kista-backup.log 2>&1` 등록 완료. 현재 로컬 postgres는 Phase 3 이관 전이라 실질 데이터는 비어있음(백업 메커니즘 자체만 검증된 상태) — Phase 3 완료 후 실데이터 백업이 의미를 가짐.

## Phase 5 — 마무리

- kista-api·ui의 기존 운영 GitHub Secrets 정리(SSH 계열만 잔존)
- SHA 핀·`production` Environment 스코프·fork PR(`pull_request_target`) 부재 점검
- `AES_ENCRYPTION_KEY`/`JWT_SIGNING_KEY`/`BACKUP_ENCRYPTION_KEY`/`ENV_PASSPHRASE` 오프라인 보관 확인
- (선택) push 승인 → `production` Environment required reviewers

**실행 완료(2026-08-07)**: kista-api `FLY_API_TOKEN`(Fly.io 폐지로 미참조), kista-ui `NEXT_PUBLIC_*` 9개(`.env.production.public` 파일로 대체돼 미참조) 총 10개 미사용 GitHub Secrets 삭제 — 양 레포 모두 SSH/DB 관련 필수 값만 잔존. `server-deploy.yml`(시크릿을 다루는 워크플로) SHA 핀은 3레포 모두 이미 완료 상태 확인(`ci.yml`/`react-doctor.yml`은 이번 작업 범위 밖 기존 워크플로라 미대상). `pull_request_target` 미사용 확인. `production` Environment required reviewers: kista-api는 기존 설정 유지, kista-ui는 신규 추가(대소문자 무관 매칭 확인). **kista-infra는 private 레포라 GitHub Free 플랜 정책상 required reviewers 사용 불가**(Public 레포만 무료 지원) — 게이트 없이 push 즉시 배포로 남김, 필요 시 유료 플랜 전환 후 재검토. 오프라인 보관 대상 시크릿은 코드로 검증 불가하여 사용자 직접 확인 필요(비밀번호 관리자 저장 여부). 작업 중 발견한 로컬 임시 파일 잔재(`/private/tmp/filled.env`, `/private/tmp/kista-infra-secrets/`)는 확인 후 삭제 — 둘 다 실제 값 없는 테스트/placeholder 파일이었음.

## 문서 반영 (동일 작업에서)

- `kista-api/docs/agents/docker-infra.md`: DB 자체 호스팅·백업·infra 레포·이중 네트워크·시크릿 체계로 갱신 ("DB 백업(Supabase)" 절 교체, "fida 병행 배포(A shared_net)" 절은 fida=B 결정으로 갱신)
- `kista-api/deploy/server/README.md`·`kista-ui/deploy/server/README.md`: caddy/redis 제거·network join·시크릿은 infra 소유
- `kista-infra/README.md`(신규): 레이아웃·기동 순서·env.sh·백업/복구·롤백
- 양 레포 `README.md` 아키텍처·파이프라인 갱신
- 이 spec 문서를 구현 결과에 맞춰 최종 갱신

## 태스크·모델 배정

| 태스크 | 병렬 | 모델 |
|---|---|---|
| Phase 0·3·4(설치)·5 — 운영 명령(OCI·SSH·DNS·이관) | 순차 | 메인 직접 + 단계별 사용자 확인 |
| Phase 1 infra 파일 / Phase 2 api 수정 / Phase 2 ui 수정 / backup.sh | 4개 동시 (레포·파일 분리) | Sonnet 5 서브에이전트 |
| 병렬 산출물 크로스 검수 (alias·네트워크·키 목록) | 병렬 후 | Opus 4.8 검토자 |
| 문서 반영 | 구현 확정 후 | Haiku 4.5 (골자는 메인 제공) |
| 각 레포 커밋 직전 최종 검수 | 커밋 전 | Opus 4.8 검토자 / code-review |

## End-to-end 검증

1. 두 도메인 200 (`/actuator/health` DB·Redis 포함)
2. 카카오 로그인 → 전략 read/write
3. 스케쥴러 실사이클 1회 + healthchecks.io ping + 텔레그램 리포트
4. 백업 → Object Storage → 복원 리허설
5. 3레포 main push 배포 → 헬스 게이트 → 롤백 리허설
6. shared_net 임시 컨테이너에서 postgres·redis 접근 불가 확인 (data_net 격리)
