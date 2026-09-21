# 스키마 재편 런북 (kista → trading 등) — 수동 실행

설계·근거: `docs/superpowers/specs/2026-09-21-db-schema-reorg-design.md`. **자동 롤백은 불가능하다** — 이 문서의 롤백 절차만 유효하다.

로컬 사전 검증(스크래치 DB, 이 브랜치 커밋 기준): 정방향→역방향 왕복 덤프 diff 0(`ROUNDTRIP_OK`), 옛 마이그레이션 적용+정방향 결과 vs 새 V1 두 개 fresh 적용 결과 스키마 diff 0(`SCHEMA_EQUAL`), 시스템 카테고리·런타임 설정 시드 동치. 운영 리허설은 같은 게이트를 **운영 덤프**로 다시 통과시키는 것이다.

## 0. 사전 점검 (T-1일 이전, 읽기 전용)

```bash
cd /opt/kista-api
# (docker compose는 KISTA_API_IMAGE 미설정 시 interpolation 오류 — 조회는 plain docker 사용)
docker logs kista-trading 2>&1 | grep -i flyway | tail -20   # 예상: ERROR 'Schema "public" has version 23, but no migration could be resolved' — 옛 이력 22행이 future로 무시돼 기동은 정상(2026-09-21 운영 로그로 확인됨)
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3"
# event_publication 분류 — 어느 쪽에도 안 걸리는 listener_id가 있으면 01-forward.sql 정규식을 보강
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT (listener_id ~ '^com\.kista\.(trading|matching|broker|account|privacy|marketcalendar)\.') AS trading_owned, count(*), count(*) FILTER (WHERE completion_date IS NULL) AS incomplete FROM event_publication GROUP BY 1"
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT DISTINCT split_part(listener_id,'#',1) FROM event_publication ORDER BY 1"
# accounts.user_id 고아 행 — 롤백 시 FK 재추가가 통과하려면 0이어야 한다(컷오버 직전에도 재확인)
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT count(*) FROM kista.accounts a LEFT JOIN public.users u ON u.id = a.user_id WHERE u.id IS NULL"
# root 소유 미완료 EPR triage — 4a 이후 public.event_publication을 재발행하는 프로세스가 없었으므로 백로그가 쌓여 있을 수 있다.
# 컷오버 후 kista-scheduler가 재발행을 되켜면(EPR=true) 이 행들이 한꺼번에 재발행돼 텔레그램/FCM이 폭주하거나, 옛 이벤트 FQCN이면 매 재기동마다 ClassNotFoundException으로 실패한다.
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT event_type, count(*), min(publication_date) FROM event_publication WHERE completion_date IS NULL AND listener_id !~ '^com\.kista\.(trading|matching|broker|account|privacy|marketcalendar)\.' GROUP BY 1 ORDER BY 2 DESC"
docker exec kista-postgres psql -U kista -d kistadb -Atc "SELECT current_user"   # 01/02 SQL이 role명 kista를 하드코딩 — kista가 아니면 SQL의 ALTER ROLE 수정 필요
docker ps --format '{{.Names}} {{.Image}} {{.Status}}'                       # 롤백용 현재 이미지 기록(세 서비스는 같은 이미지 태그)
```
- [ ] 위 결과와 이미지 3개(kista-api / kista-scheduler / kista-trading) 태그를 메모했다.
- [ ] root 소유 미완료 EPR 행(위 triage 결과)을 **purge 또는 수용**하기로 결정했다 — 수용하면 컷오버 후 kista-scheduler 첫 기동 때 일괄 재발행된다. purge는 `DELETE FROM event_publication WHERE completion_date IS NULL AND <조건>`(컷오버 직전 서비스 정지 상태에서, 유실되는 알림을 공지).
- [ ] `.env`에 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 정상. 원격 백업(`kista-infra/scripts/backup.sh`) 최근 성공 확인.
- [ ] **매매 시간대 밖 창 확정**: 토요일 06:20 KST 이후 ~ 일요일(마감 배치는 화~토 04:30~06:20, 개장은 월~금 22:30~). 다음 개장(월 22:30) 전에 끝낸다.
- [ ] Phase 0(활성 전략 조회의 `users` 의존 제거)이 운영에 배포돼 첫 개장 사이클이 정상이다.

## 1. 리허설 (운영 덤프 복제 — 운영 DB 무접촉)

1. 최신 백업을 별도 postgres 컨테이너에 복원: `backup.sh --restore <dump.gpg>` 절차 → `docker cp` + `pg_restore` (대상: 임시 컨테이너 `rehearsal-pg`의 DB `kistadb`).
2. 정방향 적용(단일 트랜잭션, 확인 후 COMMIT):
   ```bash
   docker cp deploy/server/schema-reorg/01-forward.sql rehearsal-pg:/tmp/
   docker exec -it rehearsal-pg psql -U kista -d kistadb -v ON_ERROR_STOP=1
   kistadb=# BEGIN;
   kistadb=# \i /tmp/01-forward.sql
   kistadb=# -- 검증 쿼리(아래 §3-2) 실행
   kistadb=# COMMIT;
   ```
3. 새 이미지 두 개(api, trading)를 이 DB에 붙여 기동(`SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`, `SPRING_FLYWAY_BASELINE_VERSION=1`). 헬스 OK, 두 이력 테이블에 `BASELINE` 1행 확인.
4. **동치 게이트**: fresh DB(`fresh`)에 새 V1 두 개를 각자 적용한 결과와 리허설 DB의 스키마가 같다.
   ```bash
   bash schema-dump.sh rehearsal-pg kistadb /tmp/rehearsal.sql
   # fresh DB 준비: docker exec <pg> psql -U kista -d postgres -c "CREATE DATABASE fresh OWNER kista" 후 두 V1을 각자 적용
   docker run --rm -v "$PWD/src/main/resources/db/migration:/flyway/sql" flyway/flyway:12 \
     -url=jdbc:postgresql://host.docker.internal:5432/fresh -user=kista -password=kista -defaultSchema=public -table=flyway_schema_history_api migrate
   docker run --rm -v "$PWD/trading-core/src/main/resources/db/migration-trading:/flyway/sql" flyway/flyway:12 \
     -url=jdbc:postgresql://host.docker.internal:5432/fresh -user=kista -password=kista -defaultSchema=trading -table=flyway_schema_history_trading migrate
   bash schema-dump.sh <fresh-pg> fresh /tmp/fresh.sql
   diff /tmp/rehearsal.sql /tmp/fresh.sql && echo SCHEMA_EQUAL     # 차이 0이어야 다음 단계 진행
   ```
   `schema-dump.sh`는 스키마 DDL만 비교한다 — **확장(extension)과 시드 데이터는 diff에 잡히지 않으므로 별도 확인**:
   ```sql
   SELECT extname FROM pg_extension ORDER BY 1;                          -- 두 DB 모두 btree_gist, plpgsql
   SELECT md5(string_agg(id::text||coalesce(parent_id::text,'')||type||name||sort_order::text, ',' ORDER BY id))
     FROM finance.finance_categories WHERE user_id IS NULL;              -- 시스템 카테고리(33행) — 두 DB 값이 같아야 한다
   ```
5. 역방향 리허설: `02-rollback.sql`을 같은 방식으로 적용 → 옛 이미지 3개 기동 → 헬스 OK, 옛 `flyway_schema_history` 검증 통과.
6. row count 게이트: 정방향 전후 전 테이블 건수 동일(§3-2 쿼리).

## 2. 컷오버

1. **이미지 빌드(배포 없이)**: GitHub Actions → Server Deploy → Run workflow → 브랜치 `release/schema-reorg`, `build_only=true`. 산출 이미지 태그(`ghcr.io/<repo>:<sha>`)를 메모(`NEW_IMAGE`).
2. 서버에서 전 서비스 정지: `docker stop kista-trading kista-scheduler kista-api` (compose 대신 plain docker — KISTA_API_IMAGE 불필요)
3. 직전 백업(원본 보존): `docker exec kista-postgres pg_dump -U kista kistadb -Fc > /opt/kista-api/pre-reorg-$(date +%Y%m%d-%H%M).dump` (크기 확인, 0바이트면 중단)
4. 정방향 SQL(리허설과 동일, 트랜잭션 안에서 검증 후 COMMIT):
   ```bash
   docker cp 01-forward.sql kista-postgres:/tmp/
   docker exec -it kista-postgres psql -U kista -d kistadb -v ON_ERROR_STOP=1
   kistadb=# BEGIN;
   kistadb=# \i /tmp/01-forward.sql
   kistadb=# -- §3-2 검증 쿼리. 이상 시 ROLLBACK;
   kistadb=# COMMIT;
   ```
5. 새 compose·이미지로 기동. `.env`에 **임시로** 두 줄 추가:
   ```
   SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
   SPRING_FLYWAY_BASELINE_VERSION=1
   ```
   `docker-compose.yml`은 `release/schema-reorg`의 `deploy/server/docker-compose.yml`로 교체하고 `export KISTA_API_IMAGE=$NEW_IMAGE` 후 **순서대로** 한 서비스씩 기동·헬스 확인:
   `docker compose up -d --no-deps kista-api` → healthy 대기 → `kista-trading` → `kista-scheduler`.
   (kista-api와 kista-scheduler는 같은 root 이력 테이블을 쓴다 — 동시에 띄우면 baseline이 경합하므로 반드시 순차 기동.)
6. baseline 확인:
   ```sql
   SELECT version, type, success FROM public.flyway_schema_history_api;              -- 1 | BASELINE | t
   SELECT version, type, success FROM trading.flyway_schema_history_trading;         -- 1 | BASELINE | t
   ```
7. 임시 `.env` 두 줄 삭제(이력 테이블이 생긴 뒤라 효과 없음 — 다음 정상 배포 때 함께 반영돼도 무방).
8. **첫 사이클 관측**: 월 22:30 개장 → 화 04:30 마감. `docker logs kista-trading 2>&1 | grep -E "ERROR|Started"`, healthchecks.io heartbeat, 텔레그램 매매 리포트, `SELECT count(*) FROM trading.orders WHERE trade_date = current_date`, 미완료 EPR: `SELECT count(*) FROM trading.event_publication WHERE completion_date IS NULL` / `public.event_publication`. `kista-scheduler` 로그에 EPR 재발행이 root 미완료 행을 처리하는지도 확인.
9. 이상 없으면 **그때** `release/schema-reorg`를 main에 머지(자동 배포가 같은 내용으로 재배포 — 이미 baseline 완료 상태라 무해, 매매 시간대 가드 유의).

## 3. 검증 쿼리

1. `SELECT n.nspname, count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r' AND n.nspname IN ('public','finance','kista_ref','trading','trading_ref') GROUP BY 1 ORDER BY 1;` — 스키마별 테이블 수: public **11**(root 소유 10 + 옛 flyway_schema_history 1 — 새 이미지 기동 후엔 flyway_schema_history_api가 더해져 12; 다르면 원인 조사) / finance 9 / kista_ref 4 / trading 14 / trading_ref 3.
2. 이행 전후 건수 대조(전·후 각각 실행해 diff): 각 테이블 `SELECT '<schema.table>', count(*) FROM <schema.table>` — 정방향 전에는 옛 이름, 후에는 새 이름으로. `event_publication`은 `public` + `trading` 합계가 이행 전 `public` 총계와 같아야 한다.

## 4. 롤백

| 시점 | 절차 |
|---|---|
| §2-4 COMMIT 전 | `ROLLBACK;` — 아무것도 바뀌지 않음. 옛 이미지 3개 기동 |
| 새 이미지 기동 후(사이클 관측 전·후 무관) | 1) 전 서비스 정지 2) `02-rollback.sql`을 `BEGIN; \i …; COMMIT;`으로 적용(사후 `trading.event_publication` 행은 public으로 병합됨) 3) 메모한 옛 이미지 3개와 옛 `docker-compose.yml`로 기동 4) 헬스·옛 `flyway_schema_history` 검증 통과 확인 |
| 이행 SQL 자체가 비정상 종료 | 대화형 `BEGIN; \i …` 세션에서는 오류 후 트랜잭션이 aborted 상태로 열려 있다 — **반드시 `ROLLBACK;`을 입력**해 락을 풀고(커밋은 불가), 원인 조사 전 재시도 금지 |
- 되돌릴 수 없는 것: 없음(FK 재추가는 고아 행이 있으면 실패 — 그 경우 원인 조사 후 정리). 최후 수단은 `pre-reorg-*.dump` 복원(옛 레이아웃).
- 롤백 후 `.env`의 baseline 임시 줄이 남아 있다면 삭제.
- 옛 이미지는 `ALTER ROLE kista SET search_path`가 복원돼야 unqualified SQL이 동작한다(02-rollback.sql이 복원). 롤백 후 `SELECT setconfig FROM pg_db_role_setting` 으로 확인.

## 5. 후속 (≥1주 관측 후)
- 옛 `public.flyway_schema_history` 아카이브 덤프 후 DROP.
- 4b-2(물리 DB 분리) 계획 재작성 — `pg_dump --schema trading --schema trading_ref` 단위.
