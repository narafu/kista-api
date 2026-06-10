-- V6: strategy_cycle 사이클 생명주기 컬럼 재구성
--
-- 변경 내용:
--   initial_usd_deposit → start_amount 리네임 (사이클 시작금액)
--   end_amount(종료금액)·start_date(시작일자)·end_date(종료일자) 추가
--   컬럼 순서 규칙 적용: pk, fk, 비즈니스 컬럼…, created_at, deleted_at
--
-- 컬럼 순서 변경이 필요하므로 테이블 재생성 패턴 사용 (기존 데이터 이관)

-- ============================================================
-- 1. 의존 FK 제거 + 기존 테이블 리네임 (인덱스명은 스키마 전역 — 충돌 방지용 정리)
-- ============================================================
ALTER TABLE cycle_position DROP CONSTRAINT cycle_position_strategy_cycle_id_fkey;
ALTER TABLE strategy_cycle RENAME TO strategy_cycle_old;
ALTER INDEX strategy_cycle_pkey RENAME TO strategy_cycle_old_pkey;
DROP INDEX idx_strategy_cycle_strategy_id;

-- ============================================================
-- 2. 새 컬럼 순서로 재생성 (Entity 필드 선언 순서와 일치)
-- ============================================================
CREATE TABLE strategy_cycle (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id  UUID          NOT NULL,
    start_amount NUMERIC(20,2) NOT NULL,   -- 사이클 시작금액 (USD 시드)
    end_amount   NUMERIC(20,2),            -- 사이클 종료금액 (청산 후 USD, 진행 중이면 null)
    start_date   DATE          NOT NULL,   -- 사이클 시작일자 (KST)
    end_date     DATE,                     -- 사이클 종료일자 (KST, 진행 중이면 null)
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT strategy_cycle_strategy_id_fkey
        FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

-- ============================================================
-- 3. 데이터 이관 — start_date는 생성 시각의 KST 일자로 보정
-- ============================================================
INSERT INTO strategy_cycle (id, strategy_id, start_amount, end_amount, start_date, end_date, created_at, deleted_at)
SELECT id, strategy_id, initial_usd_deposit, NULL,
       (created_at AT TIME ZONE 'Asia/Seoul')::date, NULL,
       created_at, deleted_at
FROM strategy_cycle_old;

-- ============================================================
-- 4. 구 테이블 제거 + 인덱스/FK 복원
-- ============================================================
DROP TABLE strategy_cycle_old;
CREATE INDEX idx_strategy_cycle_strategy_id ON strategy_cycle(strategy_id);
ALTER TABLE cycle_position ADD CONSTRAINT cycle_position_strategy_cycle_id_fkey
    FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE;
