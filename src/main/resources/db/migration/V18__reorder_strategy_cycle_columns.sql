-- V18: strategy_cycle 테이블 컬럼 순서 규칙 준수
--   규칙: pk, fk, 비즈니스 컬럼…, created_at, deleted_at
--   위반: is_reverse_mode(V13), seed_resolved_by(V15) — ADD COLUMN으로 deleted_at 뒤에 위치 → created_at 앞으로 이동
--
-- 컬럼 순서 변경이 필요하므로 테이블 재생성 패턴 사용 (기존 데이터 이관)

-- ============================================================
-- 1. strategy_cycle을 참조하는 FK 제거
-- ============================================================
ALTER TABLE cycle_position DROP CONSTRAINT cycle_position_strategy_cycle_id_fkey;
ALTER TABLE orders DROP CONSTRAINT orders_strategy_cycle_id_fkey;

-- ============================================================
-- 2. 기존 테이블 리네임 + named 제약/인덱스 정리
-- ============================================================
ALTER TABLE strategy_cycle RENAME TO strategy_cycle_old;
ALTER INDEX strategy_cycle_pkey RENAME TO strategy_cycle_old_pkey;
DROP INDEX idx_strategy_cycle_strategy_id;

-- ============================================================
-- 3. 새 컬럼 순서로 재생성 (is_reverse_mode, seed_resolved_by를 created_at 앞으로)
-- ============================================================
CREATE TABLE strategy_cycle (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id     UUID          NOT NULL,
    start_amount    NUMERIC(20,2) NOT NULL,
    end_amount      NUMERIC(20,2),
    start_date      DATE          NOT NULL,
    end_date        DATE,
    is_reverse_mode BOOLEAN       NOT NULL DEFAULT FALSE,
    seed_resolved_by VARCHAR(20)  NOT NULL DEFAULT 'BROKER_VERIFIED',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT strategy_cycle_strategy_id_fkey
        FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

-- ============================================================
-- 4. 데이터 이관
-- ============================================================
INSERT INTO strategy_cycle (id, strategy_id, start_amount, end_amount, start_date, end_date,
                             is_reverse_mode, seed_resolved_by, created_at, deleted_at)
SELECT id, strategy_id, start_amount, end_amount, start_date, end_date,
       is_reverse_mode, seed_resolved_by, created_at, deleted_at
FROM strategy_cycle_old;

DROP TABLE strategy_cycle_old;

-- ============================================================
-- 5. 인덱스/FK 복원
-- ============================================================
CREATE INDEX idx_strategy_cycle_strategy_id ON strategy_cycle(strategy_id);

ALTER TABLE cycle_position ADD CONSTRAINT cycle_position_strategy_cycle_id_fkey
    FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE;
ALTER TABLE orders ADD CONSTRAINT orders_strategy_cycle_id_fkey
    FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE;
