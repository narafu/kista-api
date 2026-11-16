-- 15 단계 A: 코인(업비트·바이낸스) 매매 삭제 — 코인 계좌와 그 종속 행을 완전 삭제하고 정책 JSON의 코인 키·허용 종목을 정리한다
-- 사용자 승인 2026-11-16(삭제 SQL·행 수·덤프), 덤프 kista-server:/home/ubuntu/backups/n18-crypto-2026-11-16/
-- 운영 예상 행(2026-11-16 조회, 삭제 표시 포함): accounts 2, strategy 2, strategy_version 6, strategy_dca_rule 20,
--   strategy_dca_condition 14, strategy_cycle 2, cycle_position 66, cycle_capital_event 4, orders 65, dca_execution 79,
--   trading_batch_report 0(FK 없음), broker_tokens 0, scheduler_locks 1
-- accounts 하위 FK는 전부 ON DELETE CASCADE라 accounts 삭제로 전략·사이클·포지션·주문·DCA 회차가 함께 지워진다
-- StrategyTicker BTC·ETH, OrderType MARKET, Broker UPBIT·BINANCE, DcaSkipReason 코인 3상수가 사라져 남은 값은 읽는 순간 예외 — 단언으로 막는다

-- 가드: 살아 있는 코인 계좌·전략이 있으면 중단
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trading.accounts WHERE broker IN ('BINANCE', 'UPBIT') AND deleted_at IS NULL) THEN
        RAISE EXCEPTION '활성 코인 계좌가 남아 있어 마이그레이션을 중단합니다. 계좌를 먼저 삭제하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM trading.strategy WHERE ticker IN ('BTC', 'ETH') AND deleted_at IS NULL) THEN
        RAISE EXCEPTION '활성 코인 전략이 남아 있어 마이그레이션을 중단합니다. 전략을 먼저 삭제하세요.';
    END IF;
END $$;

-- FK 없는 자식 먼저
DELETE FROM trading.trading_batch_report
WHERE strategy_id IN (SELECT s.id FROM trading.strategy s JOIN trading.accounts a ON a.id = s.account_id
                      WHERE a.broker IN ('BINANCE', 'UPBIT'));

-- 코인 계좌 완전 삭제 — 나머지 자식은 전부 ON DELETE CASCADE
DELETE FROM trading.accounts WHERE broker IN ('BINANCE', 'UPBIT');

-- 단언: 지운 enum 상수 값이 남은 행이 없다(남으면 읽는 순간 예외) — 실패하면 위 DELETE까지 함께 롤백
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trading.accounts WHERE broker IN ('BINANCE', 'UPBIT'))
        OR EXISTS (SELECT 1 FROM trading.strategy WHERE ticker IN ('BTC', 'ETH'))
        OR EXISTS (SELECT 1 FROM trading.orders WHERE ticker IN ('BTC', 'ETH') OR order_type = 'MARKET')
        OR EXISTS (SELECT 1 FROM trading.strategy_dca_rule WHERE order_type = 'MARKET')
        OR EXISTS (SELECT 1 FROM trading.dca_execution
                   WHERE skip_reason IN ('BELOW_MIN_ORDER_AMOUNT', 'EXCHANGE_API_ERROR', 'API_KEY_EXPIRED'))
        OR EXISTS (SELECT 1 FROM trading_ref.privacy_trade_base_orders WHERE order_type = 'MARKET')
        OR EXISTS (SELECT 1 FROM trading_ref.privacy_trade_bases WHERE ticker IN ('BTC', 'ETH')) THEN
        RAISE EXCEPTION '삭제된 코인 값을 가진 행이 남아 있어 마이그레이션을 중단합니다.';
    END IF;
END $$;

-- 정책 JSON: brokers의 KIS·UPBIT·BINANCE 키, 각 브로커의 allowedIp, DCA 허용 종목의 BTC·ETH
UPDATE trading.trading_runtime_settings
SET setting_value = jsonb_set(setting_value, '{brokers}',
        COALESCE((SELECT jsonb_object_agg(k, v - 'allowedIp')
                  FROM jsonb_each((setting_value -> 'brokers') - 'KIS' - 'UPBIT' - 'BINANCE') AS b(k, v)), '{}'::jsonb)),
    updated_at = now()
WHERE setting_key = 'trading-policy' AND jsonb_typeof(setting_value -> 'brokers') = 'object';

UPDATE trading.trading_runtime_settings
SET setting_value = jsonb_set(setting_value, '{strategies,DCA,ticker,allowedValues}',
        (setting_value #> '{strategies,DCA,ticker,allowedValues}') - 'BTC' - 'ETH'),
    updated_at = now()
WHERE setting_key = 'trading-policy'
  AND jsonb_typeof(setting_value #> '{strategies,DCA,ticker,allowedValues}') = 'array';

-- 단언: DCA 허용 종목이 비지 않고 기본값을 포함한다(StrategyFieldSettings 생성자와 같은 조건)
DO $$
DECLARE t jsonb;
BEGIN
    SELECT setting_value #> '{strategies,DCA,ticker}' INTO t
    FROM trading.trading_runtime_settings WHERE setting_key = 'trading-policy';
    IF t IS NOT NULL AND (jsonb_array_length(t -> 'allowedValues') = 0
                          OR NOT (t -> 'allowedValues') @> jsonb_build_array(t -> 'defaultValue')) THEN
        RAISE EXCEPTION 'DCA 허용 종목이 비었거나 기본값을 포함하지 않아 마이그레이션을 중단합니다.';
    END IF;
END $$;

-- 고아 스케줄러 락
DELETE FROM trading.scheduler_locks WHERE name = 'crypto-dca';
