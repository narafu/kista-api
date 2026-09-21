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
