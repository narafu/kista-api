-- 04 단계 A: KIS(한국투자증권) 연동 삭제 — KIS 계좌와 그 종속 행을 지우고 accounts.broker의 옛 기본값(KIS)을 없앤다
-- 운영 예상 행(2026-11-15 조회): accounts 1(soft deleted), strategy 6, strategy_version 6, strategy_infinite_version 4,
--   strategy_cycle 8, cycle_position 180, cycle_position_infinite 169, orders 925, trading_batch_report 31(FK 없음), broker_tokens 0
-- accounts 하위 FK는 전부 ON DELETE CASCADE라 accounts 삭제로 전략·사이클·포지션·주문이 함께 지워진다

-- 가드: 활성(미삭제) KIS 계좌가 남아 있으면 중단 — 사용자 계좌를 조용히 지우지 않는다
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trading.accounts WHERE broker = 'KIS' AND deleted_at IS NULL) THEN
        RAISE EXCEPTION '활성 KIS 계좌가 남아 있어 마이그레이션을 중단합니다. 계좌를 먼저 삭제하세요.';
    END IF;
END $$;

-- trading_batch_report는 FK가 없어 cascade가 닿지 않는다 — KIS 전략 행을 먼저 지운다
DELETE FROM trading.trading_batch_report
WHERE strategy_id IN (SELECT s.id FROM trading.strategy s JOIN trading.accounts a ON a.id = s.account_id WHERE a.broker = 'KIS');

-- KIS 계좌 삭제(soft delete 행 포함) — 종속 행은 FK CASCADE로 함께 삭제
DELETE FROM trading.accounts WHERE broker = 'KIS';

-- V1의 DEFAULT 'KIS' 제거 — broker는 항상 명시해 삽입한다
ALTER TABLE trading.accounts ALTER COLUMN broker DROP DEFAULT;
