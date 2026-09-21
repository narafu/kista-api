# DB 스키마 재편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Phase 2·3은 사용자가 수동 SSH로 실행하는 런북이다 — 에이전트는 운영 DB·서버를 직접 건드리지 않는다(스크립트·체크리스트 작성까지만).**

**Goal:** `kista`→`trading` 스키마 개명, `reference`를 `trading_ref`/`kista_ref`로 분할, `broker_tokens`를 `trading`으로 이동하고, root·trading-core가 각자 Flyway 이력 테이블과 새 V1 baseline을 소유하도록 재편한다.

**Architecture:** 운영 이행은 Flyway가 아닌 수동 SQL 런북(이름 변경·소유 이동만, 데이터 복사 없음)이고, 새 이미지는 첫 기동에만 baseline-on-migrate로 새 이력 테이블을 채운다. baseline V1은 옛 마이그레이션→런북 SQL→`pg_dump --schema-only` 로 기계 생성해 운영 경로와 fresh 경로의 동치를 구조적으로 보장한다.

**Tech Stack:** PostgreSQL 17, Flyway 12(Spring Boot 4), Spring Data JPA/Hibernate(`ddl-auto: validate`), Gradle, GitHub Actions, docker compose(OCI 단일 인스턴스).

**Spec:** `docs/superpowers/specs/2026-09-21-db-schema-reorg-design.md` (결정·실측 근거·롤백 논리는 전부 여기 — 실행자는 반드시 먼저 읽을 것)

## Global Constraints

- 적용된 마이그레이션 파일 수정 금지 — 옛 V1~V23은 **수정하지 않고 삭제**(새 V1 스쿼시), 옛 `public.flyway_schema_history`(22행)는 운영 DB에서 건드리지 않는다.
- 문서에 특정 V 번호를 근거로 서술 금지("어떤 컬럼/제약이 어느 파일에 있는지"만 현재 파일 기준으로).
- `git push`는 사용자가 요청할 때만. author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit, 끝에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.
- 로직 변경(Phase 0의 쿼리 교체, Phase 1의 코드·SQL)은 커밋 직전 리뷰어 검수 필수. 문서만 수정은 예외.
- **Phase 1 브랜치(`release/schema-reorg`)는 컷오버 창 전까지 `main`에 머지·푸시 금지** — `server-deploy.yml`이 `main` push로 자동 배포하고, 새 이미지는 이행 SQL 없이는 기동 즉시 실패한다.
- Flyway V1은 `IF NOT EXISTS`를 쓰지 않는다(잘못 실행되면 기동 시 시끄럽게 실패해야 함). baseline DDL은 전부 스키마 한정.
- 빌드·테스트 전체 스위트는 최종 1회만, 그 전엔 `--tests`로 좁혀 실행. Bash 환경은 `bash gradlew`.
- 테스트 DB: 로컬 docker postgres(`localhost:5432`, `kistadb_test`, user/pw `kista`/`kista`). 옛 레이아웃이 남아 있으면 Phase 1 Task 4 Step 1에서 `DROP DATABASE kistadb_test; CREATE DATABASE kistadb_test OWNER kista;`.

## 스키마·테이블 소유 매핑 (이 표가 SSOT)

| 새 스키마 | 소유 서비스 | 테이블 |
|---|---|---|
| `public` | root | users, refresh_tokens, user_settings, user_notification_prefs, admin_runtime_settings, audit_logs, app_error_logs, fcm_device_tokens, scheduler_locks, event_publication |
| `finance` | root | finance_accounts, finance_asset_snapshots, finance_budgets, finance_categories, finance_group_invitations, finance_group_members, finance_groups, finance_monthly_closings, finance_transactions |
| `kista_ref` | root | housing_benchmark_prices, housing_price_indices, market_index_prices, fear_greed_snapshots |
| `trading` | trading-core | accounts, broker_tokens, strategy, strategy_version, strategy_infinite_version, strategy_vr_version, strategy_cycle, strategy_cycle_vr, cycle_position, cycle_position_infinite, orders, user_notify_profile, **scheduler_locks(사본), event_publication(사본)** |
| `trading_ref` | trading-core | us_market_holidays, privacy_trade_bases, privacy_trade_base_orders |

## File Structure

- Create `deploy/server/schema-reorg/01-forward.sql` — 운영 이행(정방향) SQL. 이름 변경·소유 이동만.
- Create `deploy/server/schema-reorg/02-rollback.sql` — 대칭 역방향 SQL.
- Create `deploy/server/schema-reorg/schema-dump.sh` — baseline 생성·diff 게이트 공용 `pg_dump --schema-only` 래퍼.
- Create `deploy/server/schema-reorg/RUNBOOK.md` — 리허설·컷오버·롤백 절차(사용자가 수동 실행).
- Replace `src/main/resources/db/migration/*` → 새 `V1__init.sql` 1개(root 소유).
- Replace `trading-core/src/main/resources/db/migration-trading/.gitkeep` → 새 `V1__init.sql`(trading 소유).
- Modify `src/main/resources/application.yml`, `trading-core/src/main/resources/application.yml`.
- Modify `@Table` 18곳 엔티티, `deploy/server/docker-compose.yml`(scheduler EPR), `.github/scripts/detect-deploy-scope.sh`, `.github/workflows/server-deploy.yml`(Phase 0).
- Modify `trading-core/src/testFixtures/resources/application-test.yml`, trading-core 테스트 ≈10개.
- Modify 문서: `docs/agents/{architecture,constraints,docker-infra,testing,commands,modulith-migration-history}.md`, `deploy/server/README.md`, `README.md`(해당 시), `.claude/skills/flyway-migration/SKILL.md`, `.agents/skills/flyway-migration/SKILL.md`, `../kista-infra/scripts/backup.sh`(복원 주석), `../kista-infra/README.md`(해당 시).

---

# Phase 0 — 선행 릴리스 (평상시 배포 경로, 스키마 변경 없음)

## Task 0.1: 스케쥴러 활성 전략 조회에서 `public.users` 의존 제거

trading-core `findAllActiveStrategies()`가 root 소유 `users`를 JOIN한다. 복제본 `user_notify_profile.is_active`(= `UserStatus.ACTIVE` 여부, 소프트 삭제된 사용자는 `UserDeletedEvent`로 행 삭제)로 동치 교체한다.

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/StrategyJpaRepository.java:54-63`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/out/persistence/StrategyPersistenceAdapterTest.java` (findAllActiveStrategies를 다루는 테스트)

**Interfaces:**
- Consumes: 테이블 `kista.user_notify_profile(user_id UUID PK, is_active BOOLEAN NOT NULL)`, `accounts(user_id, deleted_at)`, `strategy(status, deleted_at)`.
- Produces: `List<StrategyEntity> findAllActiveStrategies()` 시그니처 불변. 이후 Task는 이 쿼리가 root 테이블을 참조하지 않는다고 가정한다.

- [ ] **Step 1: 복제본 정합성 사전 점검 SQL을 사용자에게 요청(운영 조회, 읽기 전용)**

`docker exec kista-postgres psql -U kista -d kistadb -c "..."`로 아래 두 쿼리를 실행해 **둘 다 0**임을 확인받는다. 0이 아니면 이 Task 진행 중단 — `UserSyncBackfillService.runOnce()` 관리자 엔드포인트로 재동기화 후 재확인.

```sql
-- 활성 사용자 중 복제본 누락/상태 불일치
SELECT count(*) FROM public.users u
LEFT JOIN kista.user_notify_profile p ON p.user_id = u.id
WHERE u.deleted_at IS NULL AND (p.user_id IS NULL OR p.is_active <> (u.status = 'ACTIVE'));
-- 삭제된 사용자의 잔존 복제본이 활성으로 남은 경우
SELECT count(*) FROM kista.user_notify_profile p
LEFT JOIN public.users u ON u.id = p.user_id AND u.deleted_at IS NULL
WHERE u.id IS NULL AND p.is_active;
```

- [ ] **Step 2: 기존 테스트 확인 후 실패하는 테스트 작성**

`grep -n "findAllActiveStrategies\|findAllActive" trading-core/src/test/java/com/kista/trading/adapter/out/persistence/StrategyPersistenceAdapterTest.java`로 대상 테스트를 찾는다. 그 테스트의 사용자 준비를 `INSERT INTO users ...` 에서 복제본 행 삽입으로 바꾼다(`users`엔 더 이상 행을 넣지 않는다 — 쿼리가 users를 안 읽음을 증명).

```java
// 활성 사용자 = user_notify_profile.is_active. users 테이블은 조회 경로에서 제외됐다
jdbcTemplate.update("""
        INSERT INTO kista.user_notify_profile (user_id, notification_prefs, balance_check_enabled, is_active, updated_at)
        VALUES (?, '{}', TRUE, ?, now())
        """, userId, active);
```

비활성(`is_active=false`) 사용자·프로필 없는 사용자의 전략이 결과에서 제외되고, 활성 사용자의 ACTIVE 전략만 나오는 세 케이스를 단언한다. (accounts 삽입은 기존 FK 때문에 `users` 행이 아직 필요 — 이 Task에서는 accounts용 users 삽입은 그대로 두고, **조회 대상 판정에 쓰이는 값만** 프로필로 옮긴다. `users.status`를 `SUSPENDED`로 두고 프로필만 `is_active=true`로 넣어 "users를 보지 않음"을 증명해도 좋다.)

- [ ] **Step 3: 실패 확인**

Run: `bash gradlew :trading-core:test --tests "com.kista.trading.adapter.out.persistence.StrategyPersistenceAdapterTest" 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: FAIL (현 쿼리는 `users`를 JOIN)

- [ ] **Step 4: 쿼리 교체**

```java
// ACTIVE 사용자의 ACTIVE 전략 전체 조회 (스케쥴러용) — 소프트 삭제 행 명시적 제외
// 사용자 활성 여부는 trading 소유 복제본(user_notify_profile.is_active)만 본다 — root 소유 users 비의존
@Query(value = """
        SELECT s.* FROM strategy s
        JOIN accounts a ON s.account_id = a.id
        JOIN user_notify_profile p ON p.user_id = a.user_id
        WHERE p.is_active AND s.status = 'ACTIVE'
          AND s.deleted_at IS NULL AND a.deleted_at IS NULL
        """, nativeQuery = true)
List<StrategyEntity> findAllActiveStrategies();
```

- [ ] **Step 5: 통과 확인** (Step 3과 동일 명령, Expected: PASS)

- [ ] **Step 6: 커밋 전 리뷰어 검수**

리뷰어 서브에이전트(Opus — 스케쥴러 핵심 쿼리의 의미 동치 판단)에게 diff와 "users 삭제 ↔ 프로필 행 삭제(`UserNotifyProfileSyncListener.onUserDeleted`), `is_active` ↔ `status='ACTIVE'`" 동치 논거를 검토시킨다. 지적 사항 수정 후 커밋.

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/out/persistence/StrategyJpaRepository.java trading-core/src/test/java/com/kista/trading/adapter/out/persistence/StrategyPersistenceAdapterTest.java
git commit -m "refactor(trading): 활성 전략 조회의 root users 의존을 user_notify_profile로 교체

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 7: 다른 root 테이블 참조 부재 게이트**

Run: `grep -rnwE "users|user_settings|user_notification_prefs|audit_logs|app_error_logs|refresh_tokens|fcm_device_tokens|admin_runtime_settings|finance_[a-z_]+|housing_[a-z_]+|market_index_prices|fear_greed_snapshots" --include=*.java trading-core/src/main shared/src/main | grep -iE "select|from|join|into|update |delete"`
Expected: 출력 없음(주석·식별자만 매칭되면 무시). 나오면 그 참조도 이 Task에서 끊는다.

## Task 0.2: 배포 워크플로에 `build_only` 수동 실행 모드 추가

컷오버 창에 "이미지만 빌드·푸시하고 배포는 안 하는" 경로가 필요하다(`release/schema-reorg` 브랜치에서 `workflow_dispatch`).

**Files:**
- Modify: `.github/workflows/server-deploy.yml` (`workflow_dispatch.inputs`, `deploy-api`/`deploy-scheduler`/`deploy-trading`의 `if`)

- [ ] **Step 1: 입력 추가**

`workflow_dispatch.inputs`에 `force` 아래 추가:

```yaml
      build_only:
        description: '이미지 빌드·푸시만 하고 서버 배포는 건너뜀 (수동 SSH 런북용)'
        required: false
        default: 'false'
```

- [ ] **Step 2: 배포 잡 3개 조건에 가드 추가**

각 잡의 기존 `if:`에 `&& github.event.inputs.build_only != 'true'`를 붙인다(push 이벤트에서는 `inputs`가 비어 있어 `!= 'true'`가 참 → 기존 동작 불변).

```yaml
  deploy-api:
    if: needs.changes.outputs.api == 'true' && github.event.inputs.build_only != 'true'
  deploy-scheduler:
    if: needs.changes.outputs.scheduler == 'true' && github.event.inputs.build_only != 'true'
  deploy-trading:
    if: needs.changes.outputs.trading == 'true' && github.event.inputs.build_only != 'true'
```

- [ ] **Step 3: 문법 점검**

Run: `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/server-deploy.yml'))" && echo OK` (yaml 모듈이 없으면 `actionlint`가 있을 때 그것으로, 둘 다 없으면 육안 확인)
Expected: OK

- [ ] **Step 4: 리뷰 후 커밋** (로직 변경 아님 — CI 설정이라 가벼운 diff 재검토로 충분)

```bash
git add .github/workflows/server-deploy.yml
git commit -m "ci(deploy): workflow_dispatch build_only 모드 추가 — 이미지만 빌드하고 배포 생략

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Phase 0 배포**

사용자가 push를 요청하면 평상시 워크플로로 배포(매매 시간대 밖). 배포 후 다음 개장 사이클에서 활성 전략이 정상 조회되는지(로그의 "전략 N건") 확인한 뒤 Phase 1로 넘어간다. **Phase 1은 Phase 0이 운영에 반영된 뒤에만 시작.**

---

# Phase 1 — 릴리스 브랜치 `release/schema-reorg` (main 머지·푸시 금지)

- [ ] **Branch:** `git checkout -b release/schema-reorg main` (Phase 0 반영 후 최신 main 기준)

## Task 1: 이행 SQL(정방향·역방향) 작성

**Files:**
- Create: `deploy/server/schema-reorg/01-forward.sql`
- Create: `deploy/server/schema-reorg/02-rollback.sql`

**Interfaces:**
- Produces: `01-forward.sql`은 옛 레이아웃(V1~V23 적용 결과) 위에서 실행 가능하고 트랜잭션 제어(BEGIN/COMMIT)를 포함하지 **않는다**(호출자가 감싼다). Task 2가 이 SQL을 스크래치 DB에 적용해 baseline을 뽑는다.

- [ ] **Step 1: 정방향 SQL 작성**

`deploy/server/schema-reorg/01-forward.sql`:

```sql
-- 스키마 재편 정방향: kista→trading, reference→trading_ref/kista_ref, broker_tokens→trading,
-- accounts→users FK 제거, event_publication/scheduler_locks trading 사본 생성.
-- 이름 변경·소유 이동만 수행하고 데이터는 복사하지 않는다(event_publication 리스너 행 이동 제외).
-- 트랜잭션 제어 없음 — 호출자가 BEGIN … COMMIT 으로 감싼다(RUNBOOK.md 참고).

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname IN ('trading', 'trading_ref', 'kista_ref')) THEN
        RAISE EXCEPTION '대상 스키마가 이미 존재 — 이미 적용됐거나 부분 적용 상태';
    END IF;
    IF (SELECT count(*) FROM pg_namespace WHERE nspname IN ('kista', 'reference')) <> 2 THEN
        RAISE EXCEPTION '원본 스키마(kista/reference) 누락';
    END IF;
END $$;

-- 1) kista → trading (테이블·인덱스·제약·FK 전부 보존)
ALTER SCHEMA kista RENAME TO trading;

-- 2) reference 분할: trading 소유 3개 → trading_ref, 나머지(root 소유) → kista_ref
CREATE SCHEMA trading_ref;
ALTER TABLE reference.us_market_holidays        SET SCHEMA trading_ref;
ALTER TABLE reference.privacy_trade_bases       SET SCHEMA trading_ref;
ALTER TABLE reference.privacy_trade_base_orders SET SCHEMA trading_ref;
ALTER SCHEMA reference RENAME TO kista_ref;

-- 3) broker_tokens → trading (accounts FK가 같은 스키마 안으로 들어옴)
ALTER TABLE public.broker_tokens SET SCHEMA trading;

-- 4) trading이 root(public.users)에 의존하지 않도록 cross-schema FK 제거
--    users/accounts는 소프트 삭제라 ON DELETE CASCADE 실사용 경로 없음(설계 문서 "FK 제거 근거")
ALTER TABLE trading.accounts DROP CONSTRAINT accounts_user_id_fkey;

-- 5) trading 전용 공유 인프라 테이블 사본 — trading baseline V1과 동일 DDL
CREATE TABLE trading.event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON trading.event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON trading.event_publication (completion_date);

CREATE TABLE trading.scheduler_locks (
    name       VARCHAR(100) PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
CREATE INDEX idx_scheduler_locks_lock_until ON trading.scheduler_locks(lock_until);

-- 6) trading 리스너 소속 event_publication 행만 이동 (listener_id = 클래스FQCN#메서드)
--    root 소유 리스너 행은 public에 남는다. 이동 전후 합계는 RUNBOOK 검증 쿼리로 대조.
WITH moved AS (
    DELETE FROM public.event_publication
    WHERE listener_id ~ '^com\.kista\.(trading|matching|broker|account|privacy|marketcalendar)\.'
    RETURNING *
)
INSERT INTO trading.event_publication SELECT * FROM moved;

-- 7) role 기본 search_path 제거 — 앱은 Hikari connection-init-sql로 자체 지정한다
ALTER ROLE kista RESET search_path;
```

- [ ] **Step 2: 역방향 SQL 작성**

`deploy/server/schema-reorg/02-rollback.sql`:

```sql
-- 스키마 재편 역방향: 01-forward.sql의 대칭. 새 이미지에서 쓰기가 발생한 뒤에도 안전하다
-- (데이터를 복사하지 않고 이름·소유만 되돌리며, 사후 발생한 trading.event_publication 행은 public으로 병합).
-- 새 이력 테이블(flyway_schema_history_api / _trading)은 제거한다 — 옛 flyway_schema_history는 원본 그대로.
-- 트랜잭션 제어 없음 — 호출자가 BEGIN … COMMIT 으로 감싼다.

DO $$
BEGIN
    IF (SELECT count(*) FROM pg_namespace WHERE nspname IN ('trading', 'trading_ref', 'kista_ref')) <> 3 THEN
        RAISE EXCEPTION '정방향 적용 상태가 아님(trading/trading_ref/kista_ref 중 누락)';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname IN ('kista', 'reference')) THEN
        RAISE EXCEPTION '원본 스키마명(kista/reference)이 이미 존재';
    END IF;
END $$;

-- 1) trading 사본 → public 병합 후 제거
INSERT INTO public.event_publication SELECT * FROM trading.event_publication ON CONFLICT (id) DO NOTHING;
DROP TABLE trading.event_publication;
DROP TABLE trading.scheduler_locks;

-- 2) 새 이력 테이블 제거 (재정방향 시 baseline을 다시 만든다)
DROP TABLE IF EXISTS public.flyway_schema_history_api;
DROP TABLE IF EXISTS trading.flyway_schema_history_trading;

-- 3) broker_tokens → public, accounts→users FK 복원 (고아 행이 있으면 여기서 실패 — 원인 조사)
ALTER TABLE trading.broker_tokens SET SCHEMA public;
ALTER TABLE trading.accounts
    ADD CONSTRAINT accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- 4) reference 복원: kista_ref → reference, trading_ref 3개 되돌림
ALTER SCHEMA kista_ref RENAME TO reference;
ALTER TABLE trading_ref.us_market_holidays        SET SCHEMA reference;
ALTER TABLE trading_ref.privacy_trade_bases       SET SCHEMA reference;
ALTER TABLE trading_ref.privacy_trade_base_orders SET SCHEMA reference;
DROP SCHEMA trading_ref;

-- 5) trading → kista, role search_path 복원
ALTER SCHEMA trading RENAME TO kista;
ALTER ROLE kista SET search_path = kista, finance, reference, public;
```

- [ ] **Step 3: 로컬 왕복 검증** (Task 2 Step 1~2의 스크래치 DB `reorg_old`를 재사용 — Task 2에서 함께 수행)

- [ ] **Step 4: 커밋은 Task 2와 묶는다** (SQL 단독으로는 검증 불가)

## Task 2: baseline V1 기계 생성 + 옛 마이그레이션 제거

**Files:**
- Create: `deploy/server/schema-reorg/schema-dump.sh`
- Delete: `src/main/resources/db/migration/V1__init.sql` 외 `V2`~`V23` 전부(`git rm`)
- Create: `src/main/resources/db/migration/V1__init.sql`(root 소유)
- Delete: `trading-core/src/main/resources/db/migration-trading/.gitkeep`
- Create: `trading-core/src/main/resources/db/migration-trading/V1__init.sql`(trading 소유)

**Interfaces:**
- Consumes: Task 1의 `01-forward.sql`, `02-rollback.sql`.
- Produces: root V1(= `public`/`finance`/`kista_ref` 소유 테이블), trading V1(= `trading`/`trading_ref` 소유 테이블). Task 3·4가 이 파일 경로와 `CREATE TABLE trading.<table>`/`public.<table>` 스키마 한정 형태를 가정한다.

- [ ] **Step 1: 덤프 래퍼 작성**

`deploy/server/schema-reorg/schema-dump.sh`:

```bash
#!/usr/bin/env bash
# 사용법: [SCHEMAS="public finance kista_ref"] schema-dump.sh <postgres-컨테이너> <db> <출력.sql>
# baseline 생성과 리허설 diff 게이트가 같은 덤프 형식을 쓰도록 공용화. 이력 테이블은 항상 제외한다.
set -euo pipefail
container=$1; db=$2; out=$3
schemas=${SCHEMAS:-"public finance kista_ref trading trading_ref"}
args=()
for s in $schemas; do args+=(-n "$s"); done
docker exec "$container" pg_dump -U kista -d "$db" --schema-only --no-owner --no-privileges "${args[@]}" \
  -T public.flyway_schema_history -T public.flyway_schema_history_api -T trading.flyway_schema_history_trading > "$out"
```

`chmod +x deploy/server/schema-reorg/schema-dump.sh`

- [ ] **Step 2: 옛 레이아웃 스크래치 DB 준비 + 정방향 적용**

`PG=<로컬 postgres 컨테이너명>` 으로 두고(`docker ps`로 확인, 예: `kista-trading-gradle-split-postgres-1`) 실행:

```bash
docker exec $PG psql -U kista -d postgres -c "DROP DATABASE IF EXISTS reorg_old" -c "CREATE DATABASE reorg_old OWNER kista"
# 옛 V1~V23 적용 (Docker Desktop이면 host.docker.internal, Linux면 --network host + localhost)
docker run --rm -v "$PWD/src/main/resources/db/migration:/flyway/sql" flyway/flyway:12 \
  -url=jdbc:postgresql://host.docker.internal:5432/reorg_old -user=kista -password=kista -defaultSchema=public migrate
# 옛 레이아웃 덤프(참고용 보존) 후 정방향 적용
SCHEMAS="public finance kista reference" bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_old /tmp/old-layout.sql
docker cp deploy/server/schema-reorg/01-forward.sql $PG:/tmp/01-forward.sql
docker exec $PG psql -U kista -d reorg_old -v ON_ERROR_STOP=1 -1 -f /tmp/01-forward.sql
```
Expected: `migrate` 끝에 `Successfully applied 22 migrations`(V10 결번 — 실제 수는 `ls`로 확인), 정방향 psql이 오류 없이 종료.

- [ ] **Step 3: 역방향 왕복 검증**

```bash
SCHEMAS="public finance kista_ref trading trading_ref" bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_old /tmp/after-forward.sql
docker cp deploy/server/schema-reorg/02-rollback.sql $PG:/tmp/02-rollback.sql
docker exec $PG psql -U kista -d reorg_old -v ON_ERROR_STOP=1 -1 -f /tmp/02-rollback.sql
SCHEMAS="public finance kista reference" bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_old /tmp/after-rollback.sql
diff /tmp/old-layout.sql /tmp/after-rollback.sql && echo "ROUNDTRIP_OK"
docker exec $PG psql -U kista -d reorg_old -v ON_ERROR_STOP=1 -1 -f /tmp/01-forward.sql   # 다시 정방향
```
Expected: `ROUNDTRIP_OK`(diff 없음; 있다면 무엇이 안 돌아오는지 조사 후 SQL 수정). `-T`로 옛 이력 테이블은 제외된다. FK 복원 이름·`ON DELETE` 동일 여부가 여기서 검증된다.

- [ ] **Step 4: baseline 덤프 생성·정제**

```bash
SCHEMAS="public finance kista_ref"  bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_old /tmp/root-v1.raw.sql
SCHEMAS="trading trading_ref"       bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_old /tmp/trading-v1.raw.sql
# Flyway가 실행하면 안 되는 줄 제거: SET/set_config(search_path 비우기), 기본 스키마 CREATE(Flyway가 만듦), psql 메타 커맨드
for f in root trading; do
  grep -vE '^(SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict|CREATE SCHEMA (public|trading);|COMMENT ON SCHEMA public)' \
    /tmp/$f-v1.raw.sql > /tmp/$f-v1.sql
done
```
`/tmp/root-v1.sql` 앞에 헤더 주석을 붙여 `src/main/resources/db/migration/V1__init.sql`로, `/tmp/trading-v1.sql`도 같은 방식으로 `trading-core/src/main/resources/db/migration-trading/V1__init.sql`로 저장한다. 헤더:

```sql
-- 스키마 재편(서비스별 소유 분리) 후 baseline. 옛 마이그레이션을 스쿼시해 pg_dump --schema-only로 기계 생성했다
-- (deploy/server/schema-reorg/ 참고). 이 파일은 <root|trading> 소유 스키마만 만든다: <public/finance/kista_ref | trading/trading_ref>.
-- 기본 스키마(public | trading)는 Flyway가 생성한다. 운영 DB는 이행 SQL 적용 후 baseline-on-migrate로 이 버전을 건너뛴다.
```
그리고 기존 파일 정리: `git rm src/main/resources/db/migration/V*.sql` 후 새 V1 추가, `git rm trading-core/src/main/resources/db/migration-trading/.gitkeep`.

- [ ] **Step 5: 새 V1이 fresh DB에서 실행되는지 확인 + 동치 게이트**

```bash
docker exec $PG psql -U kista -d postgres -c "DROP DATABASE IF EXISTS reorg_fresh" -c "CREATE DATABASE reorg_fresh OWNER kista"
docker run --rm -v "$PWD/src/main/resources/db/migration:/flyway/sql" flyway/flyway:12 \
  -url=jdbc:postgresql://host.docker.internal:5432/reorg_fresh -user=kista -password=kista -defaultSchema=public -table=flyway_schema_history_api migrate
docker run --rm -v "$PWD/trading-core/src/main/resources/db/migration-trading:/flyway/sql" flyway/flyway:12 \
  -url=jdbc:postgresql://host.docker.internal:5432/reorg_fresh -user=kista -password=kista -defaultSchema=trading -table=flyway_schema_history_trading migrate
bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_fresh /tmp/fresh.sql
bash deploy/server/schema-reorg/schema-dump.sh $PG reorg_old   /tmp/rehearsal.sql
diff /tmp/rehearsal.sql /tmp/fresh.sql && echo "SCHEMA_EQUAL"
```
Expected: 두 `migrate` 모두 성공, `SCHEMA_EQUAL`. 차이가 있으면 baseline 정제(Step 4 grep) 또는 정방향 SQL을 수정. 이 diff가 Phase 2 리허설 게이트의 로컬 축소판이다.

- [ ] **Step 6: 스크래치 DB 정리 + 커밋**

```bash
docker exec $PG psql -U kista -d postgres -c "DROP DATABASE reorg_old" -c "DROP DATABASE reorg_fresh"
git add deploy/server/schema-reorg src/main/resources/db/migration trading-core/src/main/resources/db/migration-trading
git commit -m "feat(db): 스키마 재편 이행 SQL 및 서비스별 baseline V1 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
(리뷰어 검수는 Task 5 통합 리뷰에서 일괄 — 이 커밋은 브랜치 중간 상태.)

## Task 3: 엔티티 `@Table`·설정·compose 변경

**Files:**
- Modify: 엔티티 18곳(아래 매핑), `src/main/resources/application.yml`, `trading-core/src/main/resources/application.yml`, `deploy/server/docker-compose.yml`, `.github/scripts/detect-deploy-scope.sh`

- [ ] **Step 1: `@Table` 스키마 치환**

```bash
# trading-core: kista→trading, reference→trading_ref, broker_tokens(public)→trading
grep -rl 'schema = "kista"'      trading-core/src/main | xargs sed -i 's/schema = "kista"/schema = "trading"/'
grep -rl 'schema = "reference"'  trading-core/src/main | xargs sed -i 's/schema = "reference"/schema = "trading_ref"/'
sed -i 's/schema = "public"/schema = "trading"/' trading-core/src/main/java/com/kista/broker/adapter/out/persistence/KisTokenEntity.java
# root: reference→kista_ref (housing×2, market_index_prices, fear_greed_snapshots)
grep -rl 'schema = "reference"'  src/main | xargs sed -i 's/schema = "reference"/schema = "kista_ref"/'
```

- [ ] **Step 2: 잔존 옛 스키마명 게이트**

Run: `grep -rnE 'schema *= *"(kista|reference)"|"kista\.|"reference\.|kista\.[a-z_]+ |reference\.[a-z_]+ ' --include=*.java --include=*.yml src trading-core shared | grep -v "com\.kista"`
Expected: 출력 없음(`UserSyncBackfillService`의 주석은 Task 5에서 정리). 그리고 18곳 확인:
`grep -rhoE 'schema *= *"[a-z_]+"' --include=*.java src/main trading-core/src/main | sort | uniq -c`
Expected: `public` 8, `finance` 9, `kista_ref` 4, `trading` 12(엔티티 11 + KisTokenEntity), `trading_ref` 3 — 합 36(옛 집계 kista 11 + reference 7 + public 9 + finance 9 = 36과 같다). 옛 스키마명 grep의 오탐(설정 키 문자열 등)은 판단해 무시한다. **테스트 코드의 `kista.`/`reference.` 스키마 리터럴(예: Phase 0에서 추가한 `kista.user_notify_profile` INSERT)도 이 Step에서 새 이름으로 함께 치환한다.**

- [ ] **Step 3: root `application.yml`**

```yaml
      connection-init-sql: "SET search_path TO finance, kista_ref, public"
  ...
  flyway:
    enabled: true
    locations: classpath:db/migration
    table: flyway_schema_history_api   # 옛 flyway_schema_history(V1~V23)는 롤백 증거로 보존
    baseline-on-migrate: false          # 운영 첫 기동에만 SPRING_FLYWAY_BASELINE_ON_MIGRATE=true 로 override(RUNBOOK)
    validate-on-migrate: true
    default-schema: public
```
`spring.modulith.events.jdbc.schema: public`은 유지하고 주석의 "(V21)" 표현을 "baseline V1이 소유"로 바꾼다.

- [ ] **Step 4: trading-core `application.yml`**

```yaml
      connection-init-sql: "SET search_path TO trading, trading_ref, public"   # public 제거는 Task 3 Step 6 게이트 통과 후
  ...
  flyway:
    enabled: true
    locations: classpath:db/migration-trading
    table: flyway_schema_history_trading
    baseline-on-migrate: false          # 운영 첫 기동에만 env로 override(RUNBOOK)
    validate-on-migrate: true
    default-schema: trading             # 서비스 소유 스키마여야 baseline 판정(비어있음 여부)이 정확하다 — 설계 문서 "실측 근거"
  modulith:
    events:
      jdbc:
        schema: trading                 # trading 전용 event_publication — root(public)와 분리
        schema-initialization:
          enabled: false                # 테이블은 Flyway baseline이 소유
```

- [ ] **Step 5: `deploy/server/docker-compose.yml` — kista-scheduler EPR 재발행 복원**

`kista-scheduler.environment`의 `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "false"`를 `"true"`로 바꾸고 주석을 교체한다:

```yaml
      # root 소유 event_publication(public)의 재발행 소유자 = kista-scheduler 단독(kista-api는 false — 둘 다 true면 이중 claim).
      # trading 리스너 행은 trading.event_publication으로 분리돼 kista-trading이 자기 것을 재발행한다.
      # 비-매매 스케쥴러만 돌아 배포 시간대 제약과 무관.
      SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"
```
`src/main/resources/application-prod.yml`의 주석("EPR 재발행 소유자 = kista-scheduler 단독")은 이미 이 설계와 일치하므로 그대로 둔다.

- [ ] **Step 6: trading search_path에서 `public` 제거 (게이트 통과 시)**

Task 0.1 Step 7의 grep을 다시 돌려 root 테이블 참조가 없고, `SchedulerLockService`(`scheduler_locks` → `trading` 사본)만 남았음을 확인하면 `connection-init-sql`을 `"SET search_path TO trading, trading_ref"`로 바꾼다. 확신이 없으면 `public`을 남기고 이 Step을 건너뛴다(잠금은 보너스, 이번 릴리스의 목표는 이름 변경).

- [ ] **Step 7: 배포 범위 판정 갱신**

`.github/scripts/detect-deploy-scope.sh`의 `src/main/resources/db/migration/*` 분기에서 `trading=true`를 제거한다(trading은 더 이상 root 스키마에 의존하지 않고 자체 `migration-trading`을 소유 — 이 경로는 이미 `trading-core/src/main/*` 분기로 trading만 켠다). 주석의 "root가 적용하고 trading-core는 validate로 검증하므로 전부" 서술도 정정한다. 이 스크립트에 대한 기존 테스트가 있으면(`ls .github/scripts`, `grep -rn detect-deploy-scope`) 함께 갱신한다.

- [ ] **Step 8: 컴파일 확인**

Run: `bash gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: 커밋**

```bash
git add -A src trading-core deploy .github
git commit -m "refactor(db): 엔티티 스키마·Flyway·search_path를 서비스별 소유 구조로 전환

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

## Task 4: 테스트를 새 스키마 구조에 맞춤

**Files:**
- Modify: `trading-core/src/testFixtures/resources/application-test.yml`
- Modify: `trading-core/src/test/java/com/kista/trading/adapter/out/persistence/{CyclePositionPersistenceAdapterTest,StrategyPersistenceAdapterTest,StrategyVrSchemaTest}.java`
- Modify: `INSERT INTO users`를 쓰는 trading-core 테스트들(아래 grep)

- [ ] **Step 1: 테스트 DB 재생성**

`docker exec $PG psql -U kista -d postgres -c "DROP DATABASE IF EXISTS kistadb_test" -c "CREATE DATABASE kistadb_test OWNER kista"`

- [ ] **Step 2: 테스트 설정에서 Flyway `locations` 제거**

`application-test.yml`의 `flyway:` 블록(`enabled`, `locations: classpath:db/migration`)을 삭제한다. 각 서비스의 main `application.yml`이 자기 `locations`/`table`/`default-schema`를 정의하므로 root 테스트와 trading-core 테스트가 각자 자기 baseline을 자기 이력 테이블로 적용한다(서로 독립 — 병렬 안전).

- [ ] **Step 3: V1 텍스트 assertion을 DB 기반 assertion으로 교체**

세 테스트는 `Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"))`로 옛 root V1의 문자열 포맷을 검사한다 — pg_dump 포맷과 위치가 바뀌므로 파일 읽기를 버리고 실제 DB 카탈로그로 같은 규약(컬럼 타입·NOT NULL·CHECK)을 검사한다. 기존 `information_schema.columns` 컬럼 순서 단언의 `WHERE`에 `table_schema = 'trading'`을 추가하고, 파일 기반 단언은 다음으로 대체한다. (`Files`/`Path` import 제거, `tableDdl` 헬퍼 삭제)

`StrategyVrSchemaTest`:
```java
assertThat(jdbcTemplate.queryForList("""
        SELECT column_name || ':' || data_type || ':' || is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'trading' AND table_name = 'strategy_vr_version'
          AND column_name IN ('recurring_amount', 'g_max')
        ORDER BY column_name
        """, String.class))
        .containsExactly("g_max:integer:NO", "recurring_amount:integer:NO");
assertThat(jdbcTemplate.queryForList("""
        SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'trading' AND t.relname = 'strategy_vr_version' AND c.contype = 'c'
        """, String.class))
        .anyMatch(d -> d.contains("interval_weeks > 0"))
        .anyMatch(d -> d.contains("g_max >= initial_gradient"));
```
`strategy_cycle_vr`도 동일 패턴: `pool_limit_rate:numeric:NO`, CHECK 정의에 `gradient > 0`, `pool_limit_rate > 0`/`pool_limit_rate <= 1`이 포함되는지. (`deleted_at` 부재는 기존 컬럼 순서 `containsExactly`가 이미 보장 — `doesNotContain("deleted_at")` 삭제.)
`CyclePositionPersistenceAdapterTest`: `is_reverse_mode:boolean:NO`, `deleted_at:timestamp with time zone:YES` 단언. `StrategyPersistenceAdapterTest`: `division_count:integer:NO`, `deleted_at:timestamp with time zone:YES` 단언(테이블 `strategy_infinite_version`).

- [ ] **Step 4: `users` 시드 제거 (FK 제거로 불필요, trading fresh DB엔 `users` 테이블 자체가 없음)**

Run: `grep -rln "INTO users\|public\.users" trading-core/src/test trading-core/src/testFixtures`
대상 파일(예상): `KisTokenPersistenceAdapterTest`, `CyclePositionPersistenceAdapterTest`, `OrderPersistenceAdapterDbTest`, `StrategyCyclePersistenceAdapterTest`, `StrategyCycleVrPersistenceAdapterTest`, `StrategyPersistenceAdapterTest`, `StrategyVersionPersistenceAdapterTest`, `StrategyVrDetailPersistenceAdapterTest`, `MarketEventNotifierTest`(단어만 매칭이면 무시). 각 파일에서 `INSERT INTO users …` 호출과 그에 딸린 정리(`DELETE FROM users` 등)를 삭제하고 `accounts.user_id`에는 임의 `UUID.randomUUID()`를 넣는다. 한 파일씩 좁혀 실행:
`bash gradlew :trading-core:test --tests "<FQCN>" 2>&1 | grep -E "FAILED|BUILD|tests completed"`

- [ ] **Step 5: root 쪽 잔존 참조 확인**

Run: `grep -rnE "kista\.|reference\.|schema = " --include=*.java src/test trading-core/src/test trading-core/src/testFixtures | grep -v "com\.kista"`
Expected: 출력 없음(있으면 새 스키마명으로 갱신).

- [ ] **Step 6: 커밋**

```bash
git add -A trading-core/src src/test
git commit -m "test(db): 서비스별 baseline 구조에 맞춰 테스트 설정·스키마 검증·시드 정리

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

## Task 5: 문서·kista-infra 주석 갱신, 최종 검증, 리뷰

**Files:** 문서 목록(File Structure 참조) + `docs/agents/modulith-migration-history.md`(이력 절 추가) + `src/main/java/com/kista/user/application/service/UserSyncBackfillService.java`(주석 1줄)

- [ ] **Step 1: 문서 갱신** — 각 항목은 "무엇을 바꾸는가"만(버전 번호 서술 금지):
  - `architecture.md`: "DB 스키마 3분리" 절을 새 5개 스키마·소유 표(위 매핑 표)로 교체. "`db/migration` 루트 전용" 서술 → 서비스별 마이그레이션 디렉토리·이력 테이블. trading `event_publication`/`scheduler_locks` 사본과 search_path 명시. `KisTokenEntity`(`broker_tokens`)가 `trading` 소유임.
  - `constraints.md`: Flyway 절 — 서비스별 `db/migration`(root)/`db/migration-trading`, 이력 테이블명, "다른 서비스 소유 스키마 테이블 참조 금지(FK 포함)", 2-role backward-compat은 root 마이그레이션에만 해당·trading은 독립, 이벤트 FQCN 절의 `event_publication`이 서비스별 2개임을 명시, "V1 스쿼시 이후 옛 V15 등 서술 금지". native SQL이 unqualified 이름으로 search_path에 의존한다는 문장의 스키마명 갱신.
  - `docker-infra.md`: "변경 경로 게이팅"의 db/migration 규칙 정정, 백업 복원 후 확인 대상 이력 테이블 두 개(`flyway_schema_history_api`, `flyway_schema_history_trading`), Flyway 롤백 주의 절 갱신, 스키마 재편 런북 링크.
  - `testing.md`: "테스트 DB" 절 — 서비스별 baseline이 각자 자기 스키마에 적용되므로 `kistadb_test`는 옛 레이아웃이면 재생성 필요, `workingDir` 서술.
  - `commands.md`: 로컬 DB 초기화(`docker compose down -v`) 한 줄.
  - `deploy/server/README.md`: Flyway 롤백 주의를 "이행 릴리스는 자동 롤백 불가, `schema-reorg/RUNBOOK.md`" 로.
  - `.claude/skills/flyway-migration/SKILL.md`, `.agents/skills/flyway-migration/SKILL.md`: 마이그레이션 위치를 소유 서비스별로(테이블 소유 표 기준으로 어느 디렉토리에 추가하는지), 이력 테이블명.
  - `README.md`: "스키마"/"Flyway"/배포 파이프라인 서술이 있으면 정정(`grep -n "스키마\|Flyway\|flyway" README.md`).
  - `../kista-infra/scripts/backup.sh`(복원 절차 5번 쿼리를 두 이력 테이블 조회로), `../kista-infra/README.md`(해당 서술이 있으면). **kista-infra는 별도 레포 — 별도 커밋(author 확인).**
  - `UserSyncBackfillService.java`: 주석의 "kista.user_notify_profile" → "trading.user_notify_profile"(코드 변경 없음).
  - `modulith-migration-history.md`: "스키마 재편" 항목 추가(결정 요약 + 설계 문서 링크).

- [ ] **Step 2: 통합 리뷰어 검수** (브랜치 전체 diff)

리뷰어(Opus — 마이그레이션 동치·롤백 대칭·검증 누락 판단) 지시 사항: (1) `01-forward.sql`↔`02-rollback.sql` 대칭성, (2) baseline과 forward 적용 결과의 동치(Task 2 Step 5 diff 재실행), (3) `@Table`·yml·compose가 소유 매핑 표와 일치, (4) 잔존 root 테이블 참조, (5) 문서가 특정 V 번호를 근거로 서술하지 않는지. 결함은 수정 후 재검증.

- [ ] **Step 3: 전체 스위트 최종 1회**

Run: `bash gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`. 실패 시 XML 리포트로 좁힌다: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml | grep -v ':0'`.

- [ ] **Step 4: 커밋(브랜치, push 금지)**

```bash
git add -A docs deploy README.md .claude .agents src
git commit -m "docs: 스키마 재편 후 소유 구조·Flyway·테스트 절차 문서 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

## Task 6: 리허설·컷오버·롤백 런북 작성 (문서)

**Files:** Create `deploy/server/schema-reorg/RUNBOOK.md` — 사용자가 수동 SSH로 실행. 에이전트는 작성만 하고 실행하지 않는다.

- [ ] **Step 1: 런북 작성** — 아래 내용을 그대로 파일에 넣는다(플레이스홀더 `<...>`는 실행 시점 값).

````markdown
# 스키마 재편 런북 (kista → trading 등) — 수동 실행

설계·근거: `docs/superpowers/specs/2026-09-21-db-schema-reorg-design.md`. **자동 롤백은 불가능하다** — 이 문서의 롤백 절차만 유효하다.

## 0. 사전 점검 (T-1일 이전, 읽기 전용)

```bash
cd /opt/kista-api
docker compose logs kista-trading 2>&1 | grep -i flyway | tail -20          # trading이 옛 이력을 어떻게 통과 중인지(future 무시 경고 예상)
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3"
# event_publication 분류 — 어느 쪽에도 안 걸리는 listener_id가 있으면 01-forward.sql 정규식을 보강
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT (listener_id ~ '^com\.kista\.(trading|matching|broker|account|privacy|marketcalendar)\.') AS trading_owned, count(*), count(*) FILTER (WHERE completion_date IS NULL) AS incomplete FROM event_publication GROUP BY 1"
docker exec kista-postgres psql -U kista -d kistadb -c "SELECT DISTINCT split_part(listener_id,'#',1) FROM event_publication ORDER BY 1"
docker compose ps --format '{{.Service}} {{.Image}}'                        # 롤백용 현재 이미지 3개 기록
```
- [ ] 위 결과와 이미지 3개(kista-api / kista-scheduler / kista-trading) 태그를 메모했다.
- [ ] `.env`에 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 정상. 원격 백업(`kista-infra/scripts/backup.sh`) 최근 성공 확인.
- [ ] **매매 시간대 밖 창 확정**: 토요일 06:20 KST 이후 ~ 일요일(마감 배치는 화~토 04:30~06:20, 개장은 월~금 22:30~). 다음 개장(월 22:30) 전에 끝낸다.

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
   # fresh DB 준비는 plan Task 2 Step 5와 동일
   bash schema-dump.sh <fresh-pg> fresh /tmp/fresh.sql
   diff /tmp/rehearsal.sql /tmp/fresh.sql && echo SCHEMA_EQUAL     # 차이 0이어야 다음 단계 진행
   ```
5. 역방향 리허설: `02-rollback.sql`을 같은 방식으로 적용 → 옛 이미지 3개 기동 → 헬스 OK, 옛 `flyway_schema_history` 검증 통과.
6. row count 게이트: 정방향 전후 전 테이블 건수 동일(§3-2 쿼리).

## 2. 컷오버

1. **이미지 빌드(배포 없이)**: GitHub Actions → Server Deploy → Run workflow → 브랜치 `release/schema-reorg`, `build_only=true`. 산출 이미지 태그(`ghcr.io/<repo>:<sha>`)를 메모(`NEW_IMAGE`).
2. 서버에서 전 서비스 정지: `cd /opt/kista-api && docker compose stop kista-trading kista-scheduler kista-api`
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
6. baseline 확인:
   ```sql
   SELECT version, type, success FROM public.flyway_schema_history_api;              -- 1 | BASELINE | t
   SELECT version, type, success FROM trading.flyway_schema_history_trading;         -- 1 | BASELINE | t
   ```
7. 임시 `.env` 두 줄 삭제(이력 테이블이 생긴 뒤라 효과 없음 — 다음 정상 배포 때 함께 반영돼도 무방).
8. **첫 사이클 관측**: 월 22:30 개장 → 화 04:30 마감. `docker compose logs kista-trading | grep -E "ERROR|Started"`, healthchecks.io heartbeat, 텔레그램 매매 리포트, `SELECT count(*) FROM trading.orders WHERE trade_date = current_date`, 미완료 EPR: `SELECT count(*) FROM trading.event_publication WHERE completion_date IS NULL` / `public.event_publication`.
9. 이상 없으면 **그때** `release/schema-reorg`를 main에 머지(자동 배포가 같은 내용으로 재배포 — 이미 baseline 완료 상태라 무해, 매매 시간대 가드 유의).

## 3. 검증 쿼리

1. `SELECT n.nspname, count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r' AND n.nspname IN ('public','finance','kista_ref','trading','trading_ref') GROUP BY 1 ORDER BY 1;` — 스키마별 테이블 수: public(옛 flyway_schema_history 포함) / finance 9 / kista_ref 4 / trading 14 / trading_ref 3.
2. 이행 전후 건수 대조(전·후 각각 실행해 diff): 각 테이블 `SELECT '<schema.table>', count(*) FROM <schema.table>` — 정방향 전에는 옛 이름, 후에는 새 이름으로. `event_publication`은 `public` + `trading` 합계가 이행 전 `public` 총계와 같아야 한다.

## 4. 롤백

| 시점 | 절차 |
|---|---|
| §2-4 COMMIT 전 | `ROLLBACK;` — 아무것도 바뀌지 않음. 옛 이미지 3개 기동 |
| 새 이미지 기동 후(사이클 관측 전·후 무관) | 1) 전 서비스 정지 2) `02-rollback.sql`을 `BEGIN; \i …; COMMIT;`으로 적용(사후 `trading.event_publication` 행은 public으로 병합됨) 3) 메모한 옛 이미지 3개와 옛 `docker-compose.yml`로 기동 4) 헬스·옛 `flyway_schema_history` 검증 통과 확인 |
| 이행 SQL 자체가 비정상 종료 | 트랜잭션이므로 자동 롤백. 원인 조사 전 재시도 금지 |
- 되돌릴 수 없는 것: 없음(FK 재추가는 고아 행이 있으면 실패 — 그 경우 원인 조사 후 정리). 최후 수단은 `pre-reorg-*.dump` 복원(옛 레이아웃).
- 롤백 후 `.env`의 baseline 임시 줄이 남아 있다면 삭제.

## 5. 후속 (≥1주 관측 후)
- 옛 `public.flyway_schema_history` 아카이브 덤프 후 DROP.
- 4b-2(물리 DB 분리) 계획 재작성 — `pg_dump --schema trading --schema trading_ref` 단위.
````

- [ ] **Step 2: 검토 후 커밋**

```bash
git add deploy/server/schema-reorg/RUNBOOK.md
git commit -m "docs(deploy): 스키마 재편 리허설·컷오버·롤백 런북 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

# Phase 2·3 — 사용자 수동 실행 (에이전트 미실행)

- Phase 2: `RUNBOOK.md` §1 리허설 — 게이트 통과 기준: SCHEMA_EQUAL, row count 일치, 역방향 후 옛 이미지 헬스 OK.
- Phase 3: `RUNBOOK.md` §2 컷오버 — 주말 매매 시간대 밖.
- 첫 개장·마감 사이클 관측 결과가 정상이면 Phase 4(브랜치 main 머지, 1주 후 옛 이력 테이블 DROP).

---

## Self-Review 메모 (계획 작성자용)

- **스펙 커버리지:** 스키마 개명·reference 분할(Task 1·3), broker_tokens 이동(Task 1·3), 서비스별 Flyway 이력·V1 스쿼시(Task 2·3), FK 처리(Task 1), 공유 테이블 분리(Task 1·2·3), search_path 3곳(Task 1 ALTER ROLE·Task 3 yml 2곳), `@Table` 18곳(Task 3), 테스트 영향(Task 4), 문서 목록(Task 5), 리허설·diff·첫 사이클 게이트(Task 6), 롤백(Task 1·6), EPR 공백(Task 3 Step 5), 워크플로 우회(Task 0.2·6), Phase 0 의존 제거(Task 0.1).
- **실행 시 재확인할 가정:** (1) `flyway/flyway:12` 이미지의 `-table` 옵션·`host.docker.internal` 사용 가능 여부(Docker Desktop), (2) pg_dump 17 출력의 `\restrict` 줄 유무(있으면 Step 4 grep이 제거), (3) 옛 마이그레이션 적용 수(V10 결번), (4) Task 4 Step 4의 `users` 삽입 사용 테스트 실제 목록.
- **범위 밖(스펙과 동일):** 물리 DB 분리, 옛 이력 DROP, DB 계정 분리.
