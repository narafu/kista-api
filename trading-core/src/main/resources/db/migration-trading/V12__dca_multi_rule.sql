-- DCA 다중 규칙 — 버전별 단일 설정(strategy_dca_version)을 규칙 테이블(strategy_dca_rule 1:N)로 대체하고 회차 이력을 규칙 단위로 기록한다.
-- 기존 DCA는 테스트 데이터 — 사용자 결정으로 정리(이관하지 않음, 하드 삭제), 이미지 롤백 불가(구 이미지는 strategy_dca_version 엔티티로 ddl-auto validate 실패).
-- trading-core 단독 스키마라 root expand/contract 제약 밖(kista-trading stop-first 단일 인스턴스).
-- 운영 적용 전 대상 건수 확인: SELECT count(*) FROM trading.strategy WHERE type = 'DCA';

-- 1. 기존 DCA 전략과 종속 행 정리 — FK는 전부 ON DELETE CASCADE지만 의도를 드러내려 잎부터 명시 삭제(soft-deleted 전략 포함)
DELETE FROM trading.dca_execution
WHERE strategy_id IN (SELECT id FROM trading.strategy WHERE type = 'DCA');

-- FK 없는 배치 리포트 행(trade_date, strategy_id) — CASCADE 대상이 아니라 반드시 명시 삭제
DELETE FROM trading.trading_batch_report
WHERE strategy_id IN (SELECT id FROM trading.strategy WHERE type = 'DCA');

DELETE FROM trading.orders
WHERE strategy_cycle_id IN (SELECT c.id FROM trading.strategy_cycle c
                            JOIN trading.strategy s ON s.id = c.strategy_id WHERE s.type = 'DCA');

DELETE FROM trading.cycle_position
WHERE strategy_cycle_id IN (SELECT c.id FROM trading.strategy_cycle c
                            JOIN trading.strategy s ON s.id = c.strategy_id WHERE s.type = 'DCA');

DELETE FROM trading.strategy_cycle
WHERE strategy_id IN (SELECT id FROM trading.strategy WHERE type = 'DCA');

DELETE FROM trading.strategy_dca_condition
WHERE strategy_version_id IN (SELECT v.id FROM trading.strategy_version v
                              JOIN trading.strategy s ON s.id = v.strategy_id WHERE s.type = 'DCA');

DELETE FROM trading.strategy_dca_version
WHERE strategy_version_id IN (SELECT v.id FROM trading.strategy_version v
                              JOIN trading.strategy s ON s.id = v.strategy_id WHERE s.type = 'DCA');

DELETE FROM trading.strategy_version
WHERE strategy_id IN (SELECT id FROM trading.strategy WHERE type = 'DCA');

DELETE FROM trading.strategy WHERE type = 'DCA';

-- 2. 단일 설정 테이블 제거 — 조건이 버전 테이블을 참조하므로 조건 먼저
DROP TABLE trading.strategy_dca_condition;
DROP TABLE trading.strategy_dca_version;

-- 3. 규칙 테이블 — 버전별 1~10행, sort_order로 입력 순서 보존, rule_key는 버전 간 안정 식별자(주문 leg "DCA_" + rule_key)
CREATE TABLE trading.strategy_dca_rule (
    id uuid NOT NULL,
    strategy_version_id uuid NOT NULL,
    rule_key character varying(36) NOT NULL,
    sort_order integer NOT NULL,
    direction character varying(20) NOT NULL,
    frequency character varying(20) NOT NULL,
    day_of_week character varying(20),
    day_of_month integer,
    order_type character varying(20) NOT NULL,
    execution_hour smallint,
    sizing_type character varying(20) NOT NULL,
    amount numeric(20,2),
    percent numeric(5,2),
    shortfall_policy character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_dca_rule_pkey PRIMARY KEY (id),
    CONSTRAINT strategy_dca_rule_strategy_version_id_fkey FOREIGN KEY (strategy_version_id)
        REFERENCES trading.strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT uq_strategy_dca_rule_version_rule_key UNIQUE (strategy_version_id, rule_key),
    CONSTRAINT uq_strategy_dca_rule_version_sort UNIQUE (strategy_version_id, sort_order),
    CONSTRAINT strategy_dca_rule_day_of_month_check CHECK (day_of_month BETWEEN 1 AND 31),
    CONSTRAINT strategy_dca_rule_weekly_check CHECK ((frequency = 'WEEKLY') = (day_of_week IS NOT NULL)),
    CONSTRAINT strategy_dca_rule_monthly_check CHECK (frequency = 'MONTHLY' OR day_of_month IS NULL),
    CONSTRAINT strategy_dca_rule_execution_hour_check CHECK (execution_hour BETWEEN 0 AND 23),
    CONSTRAINT strategy_dca_rule_sizing_check CHECK ((sizing_type = 'AMOUNT') = (amount IS NOT NULL AND percent IS NULL)),
    CONSTRAINT strategy_dca_rule_amount_check CHECK (amount > 0),
    CONSTRAINT strategy_dca_rule_percent_check CHECK (percent > 0 AND percent <= 100)
);

COMMENT ON COLUMN trading.strategy_dca_rule.rule_key IS '규칙 식별자(UUID 문자열) — 수정 시 보존, 주문 leg DCA_{rule_key}·dca_execution.rule_key';
COMMENT ON COLUMN trading.strategy_dca_rule.execution_hour IS '코인 DCA 실행 시각(KST 시 0~23) — 주식은 NULL';

-- 4. 규칙별 조건 — 규칙 1:N, AND 결합 (컬럼·CHECK는 V7과 동일, FK만 규칙으로)
CREATE TABLE trading.strategy_dca_condition (
    id uuid NOT NULL,
    rule_id uuid NOT NULL,
    sort_order integer NOT NULL,
    indicator character varying(20) NOT NULL,
    operator character varying(20) NOT NULL,
    sma_period integer,
    threshold numeric(10,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_dca_condition_pkey PRIMARY KEY (id),
    CONSTRAINT strategy_dca_condition_rule_id_fkey FOREIGN KEY (rule_id)
        REFERENCES trading.strategy_dca_rule(id) ON DELETE CASCADE,
    CONSTRAINT uq_strategy_dca_condition_rule_sort UNIQUE (rule_id, sort_order),
    CONSTRAINT strategy_dca_condition_sma_period_check CHECK ((indicator = 'SMA') = (sma_period IS NOT NULL)),
    CONSTRAINT strategy_dca_condition_threshold_check CHECK ((indicator = 'SMA') = (threshold IS NULL))
);

-- 5. 회차 이력을 규칙 단위로 — 1번에서 DCA 이력을 비웠으므로 NOT NULL 즉시 추가 가능.
-- 컬럼 순서 규칙 예외: ADD COLUMN은 항상 맨 뒤(created_at 뒤)이므로 Entity 필드도 물리 순서대로 마지막에 둔다(V10과 같은 수용 예외)
ALTER TABLE trading.dca_execution ADD COLUMN rule_key character varying(36) NOT NULL;
ALTER TABLE trading.dca_execution DROP CONSTRAINT uq_dca_execution_strategy_trade_date;
ALTER TABLE trading.dca_execution ADD CONSTRAINT uq_dca_execution_strategy_rule_trade_date UNIQUE (strategy_id, rule_key, trade_date);
