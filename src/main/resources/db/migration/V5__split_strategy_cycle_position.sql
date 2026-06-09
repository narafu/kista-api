-- V5: trading_cycle / trading_cycle_position → strategy / strategy_cycle / cycle_position 3계층 재설계
--
-- 변경 이유:
--   trading_cycle 은 전략 설정(account+type+ticker+status)과 매매 사이클(initialUsdDeposit, 한 라운드)이
--   혼재해 사이클 종료 후 재시작을 in-place 덮어쓰기로 처리해야 했음.
--   strategy → strategy_cycle → cycle_position 3계층으로 분리하여 사이클 이력을 자연스럽게 누적.
--
-- 주의: 클린 재생성 — 기존 데이터 이관 없음

-- ============================================================
-- 1. 기존 테이블 제거 (FK 의존 순서: position → cycle)
-- ============================================================
DROP TABLE IF EXISTS trading_cycle_position;
DROP TABLE IF EXISTS trading_cycle;

-- ============================================================
-- 2. strategy (전략 설정 — 계좌별 영속, 여러 사이클을 거느림)
-- ============================================================
CREATE TABLE strategy (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,             -- INFINITE | PRIVACY
    ticker          VARCHAR(20)  NOT NULL,             -- TQQQ | SOXL | …
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | PAUSED
    cycle_seed_type VARCHAR(20)  NOT NULL DEFAULT 'NONE',    -- NONE | MAINTAIN | MAX
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT strategy_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_account_id ON strategy(account_id);

-- ============================================================
-- 3. strategy_cycle (매매 한 라운드 — 매수 시작 → holdings 0 청산까지)
-- ============================================================
CREATE TABLE strategy_cycle (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id         UUID          NOT NULL,
    initial_usd_deposit NUMERIC(20,2) NOT NULL,   -- 이 사이클의 시작 시드(USD)
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT strategy_cycle_strategy_id_fkey
        FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

CREATE INDEX idx_strategy_cycle_strategy_id ON strategy_cycle(strategy_id);

-- ============================================================
-- 4. cycle_position (체결마다 append 되는 포지션 스냅샷)
-- ============================================================
CREATE TABLE cycle_position (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,   -- 통합주문가능금액(공식 B 기준)
    closing_price     NUMERIC(12,2),             -- 종가 (PRIVACY·초기 등록 시 null)
    avg_price         NUMERIC(20,2),             -- 평균 매입 단가 (holdings 0이면 null)
    holdings          INT           NOT NULL,    -- 보유 수량
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

CREATE INDEX idx_cycle_position_strategy_cycle_id ON cycle_position(strategy_cycle_id);
