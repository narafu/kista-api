# kista-trading 4b-2 (DB 컷오버) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kista-api(root)와 kista-trading(trading-core)이 공유하던 단일 PostgreSQL을 물리적으로 분리한다 — trading 소유 16개 테이블을 신규 `kista-trading-postgres` 컨테이너로 이관하고, `accounts.user_id → users(id)` 참조 무결성 FK는 컷오버 이후 선언하지 않는다(컬럼은 유지).

**Architecture:** 신규 postgres 컨테이너(`kista-trading-postgres`, kista-infra 소유)를 기존 `kista-postgres`와 같은 OCI 인스턴스에 추가하고, `kista-trading` 컨테이너만 이 신규 DB를 가리키도록 접속 정보를 분리한다. trading-core 전용 Flyway 베이스라인(`trading-core/src/main/resources/db/migration-trading/V1__init.sql`)을 신규 작성해 신규 DB에 스키마를 부트스트랩하고, `pg_dump --table`(FK 의존 순서) + `event_publication` 필터 이관으로 데이터를 옮긴다. 컷오버는 토요일 주간(매매 시간대 밖) 1회 수동 실행이며, 이 플랜은 그 실행에 필요한 마이그레이션 파일·compose 배선·이관/검증 스크립트·런북을 준비한다 — 실제 프로덕션 컷오버 실행은 이 세션이 하지 않는다(스펙 원칙).

**Tech Stack:** PostgreSQL 17, Flyway(trading-core 별도 `migration-trading` 로케이션, 이미 `application.yml`에 배선됨), Docker Compose(kista-infra + kista-api 두 레포), bash(`pg_dump`/`pg_restore`/`psql`).

**Spec:** `docs/superpowers/specs/2026-09-14-kista-trading-stage4-db-split-design.md`("4b단계 — DB 분리 + 컷오버" 절 전체, 특히 "4b-2 — DB 컷오버"/"4b-2 게이트"/"사전 확인 완료") — 이 플랜은 그 절이 확정한 16개 테이블 목록·FK 미선언 결정·이관 순서·롤백 윈도(1주일)를 그대로 구현한다.

**전제 조건(이 플랜 실행 전 확인 필수):** 4b-1(Redis Stream 배선)이 프로덕션에 배포되어 며칠간 실트래픽(가입·승인·설정변경·탈퇴)으로 `user_notify_profile` 동기화가 정상 관측된 상태 — 4b-1 게이트가 아직 통과 전이면 이 플랜의 스크립트·마이그레이션 파일은 준비해두되 Task 7(프로덕션 컷오버 런북)의 실제 실행 체크박스는 미루고 관측 결과만 기다린다.

## Global Constraints

- 커밋 author: `narafu <narafu@kakao.com>` (프로젝트 CLAUDE.md)
- `git push`는 사용자가 명시적으로 요청할 때만
- 신규 SQL 주석은 자유 형식(Flyway 파일은 코드 주석 규칙 대상 아님), Java 신규 코드는 `//` 인라인만(이 플랜엔 Java 코드 변경 없음 — 순수 인프라/마이그레이션)
- 운영 DB에 이미 적용된 Flyway 파일(V1~V23)은 절대 수정 금지 — 신규는 root 다음 버전(V24)만, trading-core는 별도 `migration-trading` 히스토리라 자체 V1부터 시작
- Entity ↔ SQL 크로스체크: trading-core 각 Entity의 `nullable`/`length`/`precision`/`scale`을 신규 V1__init.sql과 반드시 대조(flyway-migration 스킬 규칙)
- PostgreSQL 네이티브 ENUM 금지 — VARCHAR(20), `@Enumerated(STRING)`
- 암호화 컬럼(`account_no`/`app_key`/`secret_key`/`telegram_bot_token`)은 VARCHAR(512) 이상
- FK는 반드시 명시적 이름(`CONSTRAINT <table>_<col>_fkey`), `ON DELETE` 명시
- 컷오버는 되돌릴 수 없는 작업 — Task 7의 실제 실행 스텝은 이 세션이 직접 수행하지 않고, 사용자가 SSH로 수동 실행할 런북/스크립트만 준비한다
- 배포 순서: kista-infra(신규 postgres 컨테이너 기동) → kista-api(trading-core 신규 이미지, DB_URL override) 순 — 반대 순서면 `kista-trading` 컨테이너가 존재하지 않는 호스트로 접속 시도 후 기동 실패

---

### Task 1: trading-core Flyway 베이스라인 작성

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V1__init.sql`
- Delete: `trading-core/src/main/resources/db/migration-trading/.gitkeep`

**Interfaces:**
- Consumes: root `src/main/resources/db/migration/V1__init.sql`(16개 테이블 중 15개 DDL 원본)·`V21__event_publication_registry.sql`(event_publication)·`V22__create_user_notify_profile.sql`(user_notify_profile, 백필 INSERT는 컷오버 스크립트가 별도 처리하므로 이 파일엔 포함하지 않음)·`V23__add_telegram_to_user_notify_profile.sql`(telegram 컬럼 2개) — 4개 파일의 DDL을 그대로 가져오되 `accounts.user_id → users(id)` FK만 제외
- Produces: 신규 trading DB에 적용되는 스키마 16개 테이블(`kista`/`reference`/`public` 3개 스키마에 분산) — Task 4(데이터 이관 스크립트)가 이 스키마 위에 데이터를 적재

- [ ] **Step 1: root V1/V21/V22/V23의 관련 DDL 재확인**

Run: `sed -n '1,60p' src/main/resources/db/migration/V1__init.sql` (accounts 테이블 정의 확인 — `user_id` 컬럼은 유지하되 FK 절만 제거 대상)

- [ ] **Step 2: V1__init.sql 작성**

`trading-core/src/main/resources/db/migration-trading/V1__init.sql`:
```sql
-- trading-core 전용 Flyway 베이스라인 — kista-api(root)와 공유하던 단일 DB에서 분리된
-- 신규 kista-trading-postgres 컨테이너에 최초 적용되는 스키마. root V1/V21/V22/V23에서
-- trading-core 소유 16개 테이블의 DDL을 그대로 가져오되, accounts.user_id -> users(id) FK는
-- 선언하지 않는다(users 테이블이 이 DB에 없음, 컬럼 자체는 유지 — 참조 무결성은 애플리케이션이 보증).
-- 데이터 자체는 이 파일이 채우지 않는다 — deploy/server/scripts/cutover-migrate.sh가 컷오버
-- 당일 pg_dump/pg_restore로 이관한다.
-- Column order: pk -> fk -> business columns -> created_at -> updated_at -> deleted_at

CREATE SCHEMA IF NOT EXISTS kista;
CREATE SCHEMA IF NOT EXISTS reference;
-- finance 스키마는 trading-core가 소유 테이블이 없어 생성하지 않는다 — connection-init-sql의
-- search_path에 finance가 남아있어도 존재하지 않는 스키마는 조용히 스킵되므로 문제 없음.

CREATE TABLE kista.accounts (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL,
    nickname            VARCHAR(100) NOT NULL,
    broker              VARCHAR(20)  NOT NULL DEFAULT 'KIS',
    account_no          VARCHAR(512) NOT NULL,
    broker_account_code VARCHAR(10),
    app_key             VARCHAR(512) NOT NULL,
    secret_key          VARCHAR(512) NOT NULL,
    account_no_hash     VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
    -- user_id -> public.users(id) FK 없음: users는 root DB 소유, 참조 무결성은 애플리케이션이 보증
    -- (UserDeletedEvent 구독 cascade로 소프트 삭제 동기화)
);

CREATE INDEX idx_accounts_user_id ON kista.accounts(user_id);

CREATE UNIQUE INDEX uq_accounts_account_no_hash
    ON kista.accounts(account_no_hash)
    WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL;

CREATE TABLE public.broker_tokens (
    account_id   UUID        NOT NULL,
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT broker_tokens_pkey PRIMARY KEY (account_id),
    CONSTRAINT broker_tokens_account_id_fkey FOREIGN KEY (account_id) REFERENCES kista.accounts(id) ON DELETE CASCADE
);

CREATE TABLE kista.strategy (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID        NOT NULL,
    type            VARCHAR(20) NOT NULL,
    ticker          VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    cycle_seed_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT strategy_account_id_fkey FOREIGN KEY (account_id) REFERENCES kista.accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_account_id ON kista.strategy(account_id);

CREATE TABLE kista.strategy_version (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID        NOT NULL,
    version_no  INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT strategy_version_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES kista.strategy(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_strategy_version_strategy_version_no
    ON kista.strategy_version(strategy_id, version_no);

CREATE TABLE kista.strategy_infinite_version (
    strategy_version_id UUID        NOT NULL,
    division_count      INTEGER     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT strategy_infinite_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_infinite_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES kista.strategy_version(id) ON DELETE CASCADE
);

CREATE TABLE kista.strategy_vr_version (
    strategy_version_id     UUID           NOT NULL,
    interval_weeks          INTEGER        NOT NULL,
    band_width              NUMERIC(20, 2) NOT NULL,
    recurring_amount        INTEGER        NOT NULL,
    initial_gradient        INTEGER        NOT NULL,
    g_grace_weeks           INTEGER        NOT NULL,
    g_step_weeks            INTEGER        NOT NULL,
    g_max                   INTEGER        NOT NULL,
    initial_pool_limit_rate NUMERIC(6, 4)  NOT NULL,
    p_grace_weeks           INTEGER        NOT NULL,
    p_step_weeks            INTEGER        NOT NULL,
    pool_limit_floor        NUMERIC(6, 4)  NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT strategy_vr_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_vr_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES kista.strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT strategy_vr_version_interval_weeks_check CHECK (interval_weeks > 0),
    CONSTRAINT strategy_vr_version_g_step_weeks_check CHECK (g_step_weeks > 0),
    CONSTRAINT strategy_vr_version_p_step_weeks_check CHECK (p_step_weeks > 0),
    CONSTRAINT strategy_vr_version_initial_gradient_check CHECK (initial_gradient > 0),
    CONSTRAINT strategy_vr_version_g_max_check CHECK (g_max >= initial_gradient),
    CONSTRAINT strategy_vr_version_pool_limit_floor_check CHECK (pool_limit_floor > 0),
    CONSTRAINT strategy_vr_version_pool_limit_floor_le_initial_check CHECK (pool_limit_floor <= initial_pool_limit_rate),
    CONSTRAINT strategy_vr_version_initial_pool_limit_rate_check CHECK (initial_pool_limit_rate <= 1)
);

CREATE TABLE kista.strategy_cycle (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id         UUID          NOT NULL,
    strategy_version_id UUID          NOT NULL,
    start_amount        NUMERIC(20,2) NOT NULL,
    end_amount          NUMERIC(20,2),
    start_date          DATE          NOT NULL,
    end_date            DATE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT strategy_cycle_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES kista.strategy(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_strategy_version_id_fkey FOREIGN KEY (strategy_version_id) REFERENCES kista.strategy_version(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_cycle_strategy_id ON kista.strategy_cycle(strategy_id);

CREATE TABLE kista.strategy_cycle_vr (
    strategy_cycle_id UUID           NOT NULL,
    value             NUMERIC(20, 2) NOT NULL,
    gradient          INTEGER        NOT NULL,
    pool_limit_rate   NUMERIC(6, 2)  NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT strategy_cycle_vr_pkey PRIMARY KEY (strategy_cycle_id),
    CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES kista.strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT strategy_cycle_vr_gradient_check CHECK (gradient > 0),
    CONSTRAINT strategy_cycle_vr_pool_limit_rate_check CHECK (pool_limit_rate > 0 AND pool_limit_rate <= 1)
);

CREATE TABLE kista.cycle_position (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,
    closing_price     NUMERIC(12,2),
    avg_price         NUMERIC(20,2),
    holdings          INTEGER       NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES kista.strategy_cycle(id) ON DELETE CASCADE
);

CREATE INDEX idx_cycle_position_strategy_cycle_id ON kista.cycle_position(strategy_cycle_id);

CREATE TABLE kista.cycle_position_infinite (
    cycle_position_id UUID        NOT NULL,
    is_reverse_mode   BOOLEAN     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_infinite_pkey PRIMARY KEY (cycle_position_id),
    CONSTRAINT cycle_position_infinite_cycle_position_id_fkey
        FOREIGN KEY (cycle_position_id) REFERENCES kista.cycle_position(id) ON DELETE CASCADE
);

CREATE TABLE kista.orders (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        UUID          NOT NULL,
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,
    ticker            VARCHAR(20)   NOT NULL,
    order_type        VARCHAR(10)   NOT NULL,
    timing            VARCHAR(20)   NOT NULL DEFAULT 'AT_CLOSE',
    direction         VARCHAR(5)    NOT NULL,
    order_leg         VARCHAR(50)   NOT NULL DEFAULT 'UNKNOWN',
    price             NUMERIC(12,2) NOT NULL,
    quantity          INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    external_order_id VARCHAR(255),
    filled_quantity   INTEGER,
    filled_price      NUMERIC(12,2),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_account_id_fkey FOREIGN KEY (account_id) REFERENCES kista.accounts(id) ON DELETE CASCADE,
    CONSTRAINT orders_strategy_cycle_id_fkey FOREIGN KEY (strategy_cycle_id) REFERENCES kista.strategy_cycle(id) ON DELETE CASCADE
);

COMMENT ON COLUMN kista.orders.trade_date IS 'KST 거래일 — 매매가 실행·정산되는 KST 아침이 속한 날';

CREATE INDEX idx_orders_cycle_date_status
    ON kista.orders(strategy_cycle_id, trade_date, status);

CREATE INDEX idx_orders_cycle_date_timing_status
    ON kista.orders(strategy_cycle_id, trade_date, timing, status);

CREATE INDEX idx_orders_account_date_status_direction
    ON kista.orders(account_id, trade_date, status, direction);

CREATE INDEX idx_orders_account_date_ticker_direction_status
    ON kista.orders(account_id, trade_date, ticker, direction, status);

-- trading-core가 소유하는 사용자 알림·잔고검증·활성여부 읽기 전용 복제본(원본은 root public.users 등).
-- 동기화는 4b-1의 Redis Stream 경유 UserNotifyProfileChangedEvent/UserDeletedEvent 구독으로만 이뤄진다.
CREATE TABLE kista.user_notify_profile (
    user_id               UUID        NOT NULL,
    notification_prefs    TEXT        NOT NULL DEFAULT '{}',
    balance_check_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    is_active             BOOLEAN     NOT NULL DEFAULT FALSE,
    telegram_bot_token    VARCHAR(512),
    chat_id               VARCHAR(64),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_notify_profile_pkey PRIMARY KEY (user_id)
);

CREATE TABLE public.event_publication
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
CREATE INDEX event_publication_serialized_event_hash_idx ON public.event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON public.event_publication (completion_date);

CREATE TABLE reference.privacy_trade_bases (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    release_date               DATE          NOT NULL,
    ticker                     VARCHAR(20)   NOT NULL,
    current_cycle_start        NUMERIC(12,2) NOT NULL,
    current_cycle_realized_pnl NUMERIC(12,2) NOT NULL,
    avg_price                  NUMERIC(12,2),
    holdings                   INTEGER       NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_privacy_trade_bases_release_date_ticker UNIQUE (release_date, ticker)
);

CREATE TABLE reference.privacy_trade_base_orders (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    privacy_trade_id UUID          NOT NULL,
    direction        VARCHAR(5)    NOT NULL,
    order_type       VARCHAR(10)   NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    quantity         INTEGER,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT privacy_trade_base_orders_privacy_trade_id_fkey
        FOREIGN KEY (privacy_trade_id) REFERENCES reference.privacy_trade_bases(id) ON DELETE CASCADE
);

CREATE TABLE reference.us_market_holidays (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT us_market_holidays_trade_date_key UNIQUE (trade_date)
);

-- :shared 소유 인프라 테이블 — 양쪽 DB가 각자 보유(데이터 이관 대상 아님, 신규 생성만)
CREATE TABLE public.scheduler_locks (
    name       VARCHAR(100) PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

CREATE INDEX idx_scheduler_locks_lock_until ON public.scheduler_locks(lock_until);
```

- [ ] **Step 3: `.gitkeep` 삭제**

Run: `rm trading-core/src/main/resources/db/migration-trading/.gitkeep`

- [ ] **Step 4: 로컬 신규 DB에 적용 확인**

```bash
docker run -d --name kista-trading-db-test -e POSTGRES_DB=kistatradingtest -e POSTGRES_USER=kista -e POSTGRES_PASSWORD=test -p 15432:5432 postgres:17
sleep 3
DB_URL=jdbc:postgresql://localhost:15432/kistatradingtest DB_USERNAME=kista DB_PASSWORD=test \
  JWT_SIGNING_KEY='{}' AES_ENCRYPTION_KEY='0000000000000000000000000000000000000000000000000000000000000000000000000000' \
  ./gradlew :trading-core:flywayMigrate -Dflyway.url=jdbc:postgresql://localhost:15432/kistatradingtest -Dflyway.user=kista -Dflyway.password=test -Dflyway.locations=classpath:db/migration-trading
docker exec kista-trading-db-test psql -U kista -d kistatradingtest -c "\dt kista.*; \dt reference.*; \dt public.*"
docker rm -f kista-trading-db-test
```
Expected: Flyway `Successfully applied 1 migration`, `\dt` 출력에 kista 11개(accounts/strategy/strategy_version/strategy_infinite_version/strategy_vr_version/strategy_cycle/strategy_cycle_vr/cycle_position/cycle_position_infinite/orders/user_notify_profile) + reference 3개(privacy_trade_bases/privacy_trade_base_orders/us_market_holidays) + public 2개(broker_tokens/event_publication) — scheduler_locks는 `:shared`가 별도 관리하는 `public.scheduler_locks`라 public 3개(broker_tokens/event_publication/scheduler_locks) 총 16개 테이블

- [ ] **Step 5: Entity ↔ SQL 크로스체크**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL (기존 Entity 그대로라 컴파일 자체는 이미 통과하지만, 신규 SQL의 `nullable`/`length`/`precision`/`scale`이 기존 Entity와 여전히 일치하는지 육안 재확인 — 이 파일은 기존 root DDL을 그대로 복사했으므로 불일치 없어야 정상)

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src/main/resources/db/migration-trading/V1__init.sql
git rm trading-core/src/main/resources/db/migration-trading/.gitkeep
git commit -m "$(cat <<'EOF'
feat(trading): trading-core 전용 DB 베이스라인 Flyway 마이그레이션 작성

4b-2 DB 컷오버를 위한 신규 kista-trading-postgres 스키마 정의 — root
V1/V21/V22/V23에서 trading 소유 16개 테이블 DDL을 그대로 가져오되
accounts.user_id -> users(id) FK만 제외(컬럼은 유지, 참조 무결성은
UserDeletedEvent cascade로 애플리케이션이 보증). 데이터 자체는 이 파일이
채우지 않음 — 컷오버 당일 별도 스크립트가 pg_dump/restore로 이관.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: kista-infra — 신규 trading postgres 컨테이너

**Files (kista-infra 레포):**
- Modify: `docker-compose.yml`
- Modify: `.env.example`

**Interfaces:**
- Produces: `kista-trading-postgres` 컨테이너 — `data_net`에 alias `kista-trading-postgres`로 조인, `kista-trading` 컨테이너(kista-api 레포 소유)가 이 DNS 이름으로 접속. Task 3이 이 컨테이너명을 `TRADING_DB_URL`에 참조.

- [ ] **Step 1: docker-compose.yml에 서비스 추가**

`docker-compose.yml`의 `postgres:` 서비스 블록 뒤에 추가:
```yaml
  kista-trading-postgres:
    image: postgres:17
    container_name: kista-trading-postgres   # kista-api 레포 kista-trading 컨테이너가 이 이름으로 접속 (jdbc:postgresql://kista-trading-postgres:5432/...)
    environment:
      POSTGRES_DB: kistatradingdb
      POSTGRES_USER: kista_trading
      POSTGRES_PASSWORD: ${TRADING_DB_PASSWORD}
    volumes:
      - trading_postgres_data:/var/lib/postgresql/data
    networks:
      data_net:
        aliases:
          - kista-trading-postgres
    ports:
      - "127.0.0.1:5433:5432"   # 기존 postgres(5432)와 호스트 포트 충돌 방지, 로컬 SSH 터널 접근용
    mem_limit: 1024m            # trading 전용 16개 테이블만 — 기존 postgres(3072m)보다 작게
    shm_size: 128m
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U kista_trading -d kistatradingdb"]
      interval: 10s
      timeout: 5s
      retries: 5
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

`volumes:` 블록에 `trading_postgres_data:` 추가:
```yaml
volumes:
  caddy_data:
  caddy_config:
  postgres_data:
  trading_postgres_data:
  redis_data:
```

- [ ] **Step 2: `.env.example`에 신규 변수 추가**

`.env.example`의 `DB_PASSWORD=` 줄 근처에 추가:
```
# trading DB(kista-trading-postgres, 4b-2 컷오버 신설) 전용 — kista-postgres와 별도 비밀번호
TRADING_DB_PASSWORD=
```

- [ ] **Step 3: 로컬 docker compose config 문법 검증**

Run: `docker compose config -q`
Expected: 에러 없음(exit 0) — 문법·변수 참조 오류만 잡는 정적 검증, 실제 기동은 하지 않음

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.yml .env.example
git commit -m "$(cat <<'EOF'
feat(infra): trading DB 분리용 kista-trading-postgres 컨테이너 추가

4b-2 컷오버 대상 신규 postgres 컨테이너 — 기존 kista-postgres와 별도
비밀번호(TRADING_DB_PASSWORD), data_net에 alias kista-trading-postgres로
조인해 kista-api 레포의 kista-trading 컨테이너가 접속.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: kista-api 배포 — trading-core DB 접속 정보 분리

**Files:**
- Modify: `deploy/server/docker-compose.yml`
- Modify: `.env.example`

**Interfaces:**
- Consumes: Task 2가 만든 `kista-trading-postgres` 호스트명(`data_net` DNS alias)
- Produces: `kista-trading` 컨테이너만 신규 DB를 가리키는 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 환경변수 override — `kista-api`/`kista-scheduler`는 기존 `.env`의 값(기존 `kista-postgres`) 그대로 유지

- [ ] **Step 1: `kista-trading` 서비스에 DB 환경변수 override 추가**

`deploy/server/docker-compose.yml`의 `kista-trading:` 서비스 `environment:` 블록에 추가(기존 `APP_JAR`/`SERVER_PORT` 등 유지, 아래 3줄만 신규):
```yaml
      DB_URL: jdbc:postgresql://kista-trading-postgres:5432/kistatradingdb   # 4b-2 컷오버 — kista-api/kista-scheduler는 여전히 kista-postgres(.env DB_URL)를 가리킴
      DB_USERNAME: kista_trading
      DB_PASSWORD: ${TRADING_DB_PASSWORD}
```

- [ ] **Step 2: `.env.example`에 신규 변수 추가**

`.env.example`의 `DB_PASSWORD=` 줄 근처에 추가:
```
# trading DB(kista-trading-postgres, 4b-2 컷오버) 전용 — kista-infra .env의 TRADING_DB_PASSWORD와 동일 값이어야 함
TRADING_DB_PASSWORD=
```

- [ ] **Step 3: compose 문법 검증**

Run: `docker compose -f deploy/server/docker-compose.yml config -q`
Expected: 에러 없음 — `KISTA_API_IMAGE`/`TRADING_DB_PASSWORD` 등 필수 변수 미설정 경고는 로컬 검증 단계에서 정상(실제 `.env` 없이 문법만 확인)

- [ ] **Step 4: 커밋**

```bash
git add deploy/server/docker-compose.yml .env.example
git commit -m "$(cat <<'EOF'
feat(deploy): kista-trading 컨테이너 DB 접속 정보를 신규 trading DB로 분리

4b-2 컷오버 — kista-trading 서비스만 DB_URL/DB_USERNAME/DB_PASSWORD를
override해 kista-trading-postgres(kista-infra 신설)를 가리키게 함.
kista-api/kista-scheduler는 기존 .env의 kista-postgres 값 그대로 유지.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 데이터 이관 스크립트

**Files:**
- Create: `deploy/server/scripts/cutover-migrate.sh`

**Interfaces:**
- Consumes: 기존 `kista-postgres`(소스), Task 2의 `kista-trading-postgres`(대상) — 둘 다 같은 OCI 인스턴스에서 `docker exec`로 접근
- Produces: `deploy/server/scripts/cutover-migrate.sh <소스컨테이너> <대상컨테이너>` — Task 5(검증 스크립트)가 이관 완료 여부를 이 스크립트 실행 이후 상태로 확인

- [ ] **Step 1: 스크립트 작성**

`deploy/server/scripts/cutover-migrate.sh`:
```bash
#!/usr/bin/env bash
# 4b-2 DB 컷오버 — kista-postgres(소스)의 trading 소유 16개 테이블을
# kista-trading-postgres(대상, Task 1의 V1__init.sql로 스키마 부트스트랩 완료 상태)로 이관한다.
#
# 사전 조건:
#   - 대상 DB에 trading-core Flyway(V1__init.sql)가 이미 적용되어 16개 빈 테이블이 존재해야 함
#     (kista-trading 컨테이너를 최초 1회 기동해 자동 적용하거나 수동으로 flywayMigrate 실행)
#   - 컷오버 창(토요일 주간, 매매 시간대 22:30~04:30 MON-SAT 바깥)에 실행 — kista-api/kista-scheduler/
#     kista-trading 컨테이너를 모두 내린 상태에서 실행해야 이관 중 신규 쓰기가 섞이지 않음
#
# 사용법: ./cutover-migrate.sh <소스 컨테이너명> <대상 컨테이너명>
#   예: ./cutover-migrate.sh kista-postgres kista-trading-postgres
#
# 복구(문제 발생 시): 대상 DB는 신규 컨테이너이므로 문제 발생 시 컨테이너 자체를 내리고 볼륨
# 삭제 후 재시작 -- 소스(kista-postgres)는 이 스크립트가 절대 쓰지 않으므로(읽기 전용 dump) 항상 안전.

set -euo pipefail

SRC="${1:?사용법: $0 <소스 컨테이너명> <대상 컨테이너명>}"
DST="${2:?사용법: $0 <소스 컨테이너명> <대상 컨테이너명>}"
SRC_DB=kistadb
SRC_USER=kista
DST_DB=kistatradingdb
DST_USER=kista_trading

# 15개 매매 테이블 — FK 의존 순서(부모 먼저)
TRADING_TABLES=(
  kista.accounts
  public.broker_tokens
  kista.strategy
  kista.strategy_version
  kista.strategy_infinite_version
  kista.strategy_vr_version
  kista.strategy_cycle
  kista.strategy_cycle_vr
  kista.cycle_position
  kista.cycle_position_infinite
  kista.orders
  kista.user_notify_profile
  reference.privacy_trade_bases
  reference.privacy_trade_base_orders
  reference.us_market_holidays
)

echo "=== [1/3] 매매 테이블 ${#TRADING_TABLES[@]}개 이관 ==="
for table in "${TRADING_TABLES[@]}"; do
  echo "-- ${table}"
  docker exec "$SRC" pg_dump -U "$SRC_USER" -d "$SRC_DB" --data-only --table="$table" \
    | docker exec -i "$DST" psql -U "$DST_USER" -d "$DST_DB" -v ON_ERROR_STOP=1 -q
done

echo "=== [2/3] event_publication 필터 이관 (listener_id LIKE 'com.kista.trading.%' 등 trading-core 소유 row만) ==="
# root/trading-core 공유 시절 물리 테이블 1개에 양쪽 리스너 행이 섞여 있으므로, trading-core
# 패키지(com.kista.trading/com.kista.matching/com.kista.broker/com.kista.account/com.kista.privacy/
# com.kista.marketcalendar) 소속 listener_id만 골라 이관한다 — root 소유 리스너 행은 남긴다.
docker exec "$SRC" psql -U "$SRC_USER" -d "$SRC_DB" -At -c \
  "COPY (SELECT * FROM public.event_publication WHERE listener_id LIKE 'com.kista.trading.%' OR listener_id LIKE 'com.kista.matching.%' OR listener_id LIKE 'com.kista.broker.%' OR listener_id LIKE 'com.kista.account.%' OR listener_id LIKE 'com.kista.privacy.%' OR listener_id LIKE 'com.kista.marketcalendar.%') TO STDOUT" \
  | docker exec -i "$DST" psql -U "$DST_USER" -d "$DST_DB" -v ON_ERROR_STOP=1 -q -c \
    "COPY public.event_publication FROM STDIN"

echo "=== [3/3] 시퀀스 없음 확인 — 전 테이블 UUID PK(gen_random_uuid())라 setval 불필요 ==="

echo "이관 완료. deploy/server/scripts/cutover-verify.sh $SRC $DST 로 건수 검증할 것."
```

- [ ] **Step 2: 실행 권한 부여**

Run: `chmod +x deploy/server/scripts/cutover-migrate.sh`

- [ ] **Step 3: shellcheck 검증**

Run: `shellcheck deploy/server/scripts/cutover-migrate.sh` (설치 안 돼 있으면 `bash -n deploy/server/scripts/cutover-migrate.sh`로 문법만 확인)
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add deploy/server/scripts/cutover-migrate.sh
git commit -m "$(cat <<'EOF'
feat(deploy): 4b-2 DB 컷오버 데이터 이관 스크립트 추가

kista-postgres -> kista-trading-postgres로 매매 15개 테이블(FK 순서)
+ event_publication(listener_id 필터) 이관. pg_dump --data-only per
table 방식 — 소스는 읽기 전용이라 실패해도 소스 DB 무손실.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 이관 검증 스크립트

**Files:**
- Create: `deploy/server/scripts/cutover-verify.sh`

**Interfaces:**
- Consumes: Task 4 실행 이후 상태의 두 컨테이너
- Produces: exit code 0(전 테이블 건수 일치) / 1(불일치 테이블 목록 출력) — Task 7 런북의 게이트 조건으로 사용

- [ ] **Step 1: 스크립트 작성**

`deploy/server/scripts/cutover-verify.sh`:
```bash
#!/usr/bin/env bash
# 4b-2 컷오버 검증 — 소스(kista-postgres)와 대상(kista-trading-postgres)의 이관 대상 테이블
# 건수가 일치하는지 확인. event_publication은 필터 이관이라 별도 조건으로 비교.
#
# 사용법: ./cutover-verify.sh <소스 컨테이너명> <대상 컨테이너명>

set -euo pipefail

SRC="${1:?사용법: $0 <소스 컨테이너명> <대상 컨테이너명>}"
DST="${2:?사용법: $0 <소스 컨테이너명> <대상 컨테이너명>}"
SRC_DB=kistadb
SRC_USER=kista
DST_DB=kistatradingdb
DST_USER=kista_trading

TRADING_TABLES=(
  kista.accounts
  public.broker_tokens
  kista.strategy
  kista.strategy_version
  kista.strategy_infinite_version
  kista.strategy_vr_version
  kista.strategy_cycle
  kista.strategy_cycle_vr
  kista.cycle_position
  kista.cycle_position_infinite
  kista.orders
  kista.user_notify_profile
  reference.privacy_trade_bases
  reference.privacy_trade_base_orders
  reference.us_market_holidays
)

fail=0

for table in "${TRADING_TABLES[@]}"; do
  src_count=$(docker exec "$SRC" psql -U "$SRC_USER" -d "$SRC_DB" -At -c "SELECT count(*) FROM ${table}")
  dst_count=$(docker exec "$DST" psql -U "$DST_USER" -d "$DST_DB" -At -c "SELECT count(*) FROM ${table}")
  if [ "$src_count" != "$dst_count" ]; then
    echo "불일치: ${table} — 소스 ${src_count} / 대상 ${dst_count}"
    fail=1
  else
    echo "일치: ${table} — ${src_count}건"
  fi
done

src_ep_count=$(docker exec "$SRC" psql -U "$SRC_USER" -d "$SRC_DB" -At -c \
  "SELECT count(*) FROM public.event_publication WHERE listener_id LIKE 'com.kista.trading.%' OR listener_id LIKE 'com.kista.matching.%' OR listener_id LIKE 'com.kista.broker.%' OR listener_id LIKE 'com.kista.account.%' OR listener_id LIKE 'com.kista.privacy.%' OR listener_id LIKE 'com.kista.marketcalendar.%'")
dst_ep_count=$(docker exec "$DST" psql -U "$DST_USER" -d "$DST_DB" -At -c "SELECT count(*) FROM public.event_publication")
if [ "$src_ep_count" != "$dst_ep_count" ]; then
  echo "불일치: event_publication(필터) — 소스 ${src_ep_count} / 대상 ${dst_ep_count}"
  fail=1
else
  echo "일치: event_publication(필터) — ${src_ep_count}건"
fi

if [ "$fail" -eq 0 ]; then
  echo "=== 전체 일치 — 컷오버 데이터 검증 통과 ==="
else
  echo "=== 불일치 발견 — 위 목록 확인 후 재이관 또는 원인 조사 ===" >&2
fi
exit "$fail"
```

- [ ] **Step 2: 실행 권한 부여**

Run: `chmod +x deploy/server/scripts/cutover-verify.sh`

- [ ] **Step 3: shellcheck 검증**

Run: `shellcheck deploy/server/scripts/cutover-verify.sh` (미설치 시 `bash -n deploy/server/scripts/cutover-verify.sh`)
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add deploy/server/scripts/cutover-verify.sh
git commit -m "$(cat <<'EOF'
feat(deploy): 4b-2 DB 컷오버 이관 건수 검증 스크립트 추가

15개 매매 테이블 + event_publication(필터) 소스/대상 건수 비교,
불일치 시 exit 1 — 런북의 컷오버 게이트 조건으로 사용.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 컷오버 후 root DB drop 마이그레이션 (즉시 배포 금지)

**Files:**
- Create: `src/main/resources/db/migration/V24__drop_trading_tables_after_cutover.sql`

**Interfaces:**
- Consumes: 없음(독립 실행 — 배포 시점은 사람이 판단)
- Produces: root DB에서 16개 trading 테이블 제거 — **이 파일은 컷오버 후 1주일 관측 기간이 끝나야 배포 가능** (Global Constraints·아래 Step 2 경고 참고)

- [ ] **Step 1: drop 마이그레이션 작성**

`src/main/resources/db/migration/V24__drop_trading_tables_after_cutover.sql`:
```sql
-- ⚠⚠⚠ 즉시 배포 금지 — 4b-2 컷오버(Task 4/5, deploy/server/scripts/cutover-*.sh) 완료 후
-- 최소 1주일 관측 기간이 지나 문제 없음을 확인한 뒤에만 배포한다(스펙 "4b-2 게이트"
-- "1주일 관측 기간 종료 후 drop 마이그레이션 배포" 참고). 롤백 윈도 동안은 이 파일을
-- 커밋만 해두고 merge/배포하지 않는다 — 배포되면 root DB에서 데이터가 영구 삭제된다.
--
-- 컷오버로 kista-trading-postgres(신규 DB)가 이 16개 테이블의 유일한 소스오브트루스가 된
-- 이후, root(kista-api) DB에 남아있던 사본을 제거한다. FK 의존 자식 테이블부터 순서대로 DROP.

DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS cycle_position_infinite;
DROP TABLE IF EXISTS cycle_position;
DROP TABLE IF EXISTS strategy_cycle_vr;
DROP TABLE IF EXISTS strategy_cycle;
DROP TABLE IF EXISTS strategy_vr_version;
DROP TABLE IF EXISTS strategy_infinite_version;
DROP TABLE IF EXISTS strategy_version;
DROP TABLE IF EXISTS strategy;
DROP TABLE IF EXISTS broker_tokens;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS kista.user_notify_profile;
DROP TABLE IF EXISTS privacy_trade_base_orders;
DROP TABLE IF EXISTS privacy_trade_bases;
DROP TABLE IF EXISTS us_market_holidays;
-- event_publication은 drop하지 않는다 — root 자신의 EPR 리스너(finance cascade 등)가 계속 사용 중
```

- [ ] **Step 2: 커밋 (배포 금지 주석 포함 — CI가 자동 적용하지 않도록 별도 브랜치 또는 draft PR로 관리할 것을 커밋 메시지에 명시)**

```bash
git add src/main/resources/db/migration/V24__drop_trading_tables_after_cutover.sql
git commit -m "$(cat <<'EOF'
feat(db): 4b-2 컷오버 후 root DB trading 테이블 drop 마이그레이션 작성 (배포 보류)

컷오버 1주일 관측 기간 종료 후에만 배포 — 그 전에 main에 정상 머지되면
다음 kista-api 배포 시 즉시 적용돼 롤백 데이터가 사라진다. 배포 시점은
사용자가 직접 판단(런북 Task 7 참고).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

이 태스크 완료 후 **사용자에게 명시적으로 알릴 것**: "V24는 커밋됐지만 배포 금지 상태 — 1주일 관측 기간 전까지 이 커밋을 포함한 배포를 하지 말 것."

---

### Task 7: 컷오버 런북 (문서 — 이 세션은 실행하지 않음)

**Files:**
- Create: `docs/agents/db-cutover-runbook.md`

**Interfaces:**
- Consumes: Task 1~6의 산출물(마이그레이션 파일·compose 변경·스크립트 3개) 전부
- Produces: 사용자가 SSH로 직접 실행할 체크리스트 — 코드 산출물 없음

- [ ] **Step 1: 런북 작성**

`docs/agents/db-cutover-runbook.md`:
```markdown
# kista-trading 4b-2 DB 컷오버 런북

이 문서는 사람이 SSH로 직접 실행한다 — Claude Code가 프로덕션 컷오버를 자동 실행하지 않는다.

## 사전 조건
- [ ] 4b-1(Redis Stream)이 프로덕션 배포되어 최소 며칠간 실트래픽(가입/승인/설정변경/탈퇴)으로
      `user_notify_profile` 정상 동기화 관측 완료 (스펙 "4b-1 게이트")
- [ ] kista-infra Task 2, kista-api Task 3~6이 각 레포 main에 머지·배포 완료
      (단 Task 6의 V24는 이 시점엔 배포하지 않음 — main에 커밋만 있고 아직 프로덕션 미반영 상태 유지)
- [ ] 컷오버 창 확보: 토요일 주간(매매 22:30~04:30 MON-SAT 바깥)

## 0. 스테이징 리허설 (프로덕션 컷오버 전 별도 1회)
- [ ] 스테이징 환경(또는 프로덕션과 동일 구성의 임시 인스턴스)에서 아래 1~5단계를 동일하게 실행
- [ ] 리허설 중 발견된 문제는 이 런북·스크립트를 수정한 뒤 다시 리허설 — 문제 없이 통과할 때까지 프로덕션 컷오버 진행 금지

## 1. 신규 DB 기동
```bash
ssh kista-api-server
cd /opt/kista-infra
git pull
./scripts/env.sh edit infra   # TRADING_DB_PASSWORD 값 채우기
docker compose up -d kista-trading-postgres
docker compose ps kista-trading-postgres   # healthy 확인
```

## 2. 신규 DB 스키마 부트스트랩
```bash
cd /opt/kista-api
git pull   # Task 3 배포분(V24 제외)
./scripts/env.sh edit kista-api   # TRADING_DB_PASSWORD 값 채우기 (infra와 동일 값)
# kista-trading 컨테이너를 최초 1회 기동해 Flyway가 V1__init.sql을 자동 적용하게 함
docker compose -f deploy/server/docker-compose.yml up -d kista-trading
docker compose -f deploy/server/docker-compose.yml logs kista-trading | grep -i flyway
# "Successfully applied 1 migration" 확인 후 즉시 중지 — 아직 데이터 이관 전이라 매매 실행하면 안 됨
docker compose -f deploy/server/docker-compose.yml stop kista-trading
```

## 3. 트래픽 중단 + 데이터 이관
```bash
# 세 컨테이너 모두 중지 — 이관 중 신규 쓰기 유입 차단
docker compose -f deploy/server/docker-compose.yml stop kista-api kista-scheduler kista-trading

cd /opt/kista-api/deploy/server/scripts
./cutover-migrate.sh kista-postgres kista-trading-postgres
./cutover-verify.sh kista-postgres kista-trading-postgres
# exit 0(전체 일치) 확인 — 불일치 시 원인 조사 전까지 4단계 진행 금지
```

## 4. 재기동 + 첫 매매 사이클 관측
```bash
docker compose -f deploy/server/docker-compose.yml up -d kista-api kista-scheduler kista-trading
docker compose -f deploy/server/docker-compose.yml ps   # 전부 healthy 확인
curl -f https://${API_DOMAIN}/actuator/health
```
- [ ] 컷오버 후 첫 개장(22:30 KST) 정상 관측(로그 + healthchecks.io heartbeat + 텔레그램 리포트)
- [ ] 컷오버 후 첫 마감(04:30 KST, 화~토) 정상 관측

## 5. 1주일 관측
- [ ] root(`kista-postgres`) DB에 16개 테이블 데이터를 그대로 둔 채 1주일 운영 관측
- [ ] 매일 `cutover-verify.sh` 재실행해 drift 없는지 확인(선택 — 자동화 안 돼 있음, 수동 재실행)
- [ ] 문제 발견 시 롤백: `deploy/server/docker-compose.yml`의 `kista-trading` DB_URL override를 되돌리고
      재배포(root DB가 아직 살아있으므로 물리 데이터 손실 없음) — 경과 시간이 길수록 그 사이
      `kista-trading-postgres`에 쌓인 신규 데이터를 역방향 이관해야 해 롤백 비용이 커짐(스펙 참고)

## 6. Contract 마이그레이션 배포 (1주일 경과 후)
- [ ] `src/main/resources/db/migration/V24__drop_trading_tables_after_cutover.sql`을 포함한
      kista-api 배포 실행 — 이 시점부터 root DB의 16개 테이블 영구 삭제, 롤백 불가
```

- [ ] **Step 2: 커밋**

```bash
git add docs/agents/db-cutover-runbook.md
git commit -m "$(cat <<'EOF'
docs: 4b-2 DB 컷오버 프로덕션 실행 런북 작성

스테이징 리허설 -> 신규 DB 기동 -> 스키마 부트스트랩 -> 트래픽 중단+이관 ->
재기동+매매사이클 관측 -> 1주일 관측 -> V24 contract 배포 순서의 SSH 수동
실행 체크리스트. 프로덕션 컷오버 자체는 이 세션이 실행하지 않음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 체크리스트 (계획 작성자용, 실행자는 무시)
- 스펙 "사전 확인 완료" 16개 테이블 목록 — Task 1 V1__init.sql이 전부 포함(accounts/strategy/strategy_version/strategy_infinite_version/strategy_vr_version/strategy_cycle/strategy_cycle_vr/cycle_position/cycle_position_infinite/orders/user_notify_profile/broker_tokens/event_publication/privacy_trade_bases/privacy_trade_base_orders/us_market_holidays = 15 + scheduler_locks는 데이터 이관 대상 아니고 신규 생성만, 스펙 문구 그대로 반영).
- `accounts.user_id → users(id)` FK 미선언 — Task 1에서 명시적으로 제외, 주석으로 이유 기록.
- Redis Stream 2종 신뢰성 요건(4b-1) — 이 플랜 범위 밖(전제 조건 절에 게이트로 명시).
- 배포 순서(kista-infra → kista-api) — Global Constraints에 명시, Task 2/3 순서로 반영.
- 1주일 롤백 윈도 + contract 마이그레이션 분리 배포 — Task 6에서 "즉시 배포 금지" 강조 + Task 7 런북 6단계에 시점 명시.
- reconciliation 배치(스펙 106줄)는 컷오버 이후 별도 태스크로 명시 유예 — 이 플랜에 포함하지 않음(스펙과 동일 결정).
- 스테이징 리허설(스펙 "4b-2 게이트" 1번) — Task 7 런북 0단계로 반영.
- 전체 실행은 사람이 SSH로 — Task 1~6은 코드/스크립트 산출물(이 세션이 작성), Task 7은 순수 체크리스트로 분리해 "이 세션이 프로덕션을 직접 건드리지 않는다"는 스펙 원칙을 구조적으로 강제.

**Plan complete and saved to `docs/superpowers/plans/2026-09-16-kista-trading-stage4b2.md`.**
