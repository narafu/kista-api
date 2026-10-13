-- DCA(적립식 분할매매) 전략 — 버전별 설정 + 회차 실행 이력. 조건 테이블(strategy_dca_condition)은 단계 ②에서 추가한다.
-- trading-core 단독 스키마라 root expand/contract 제약 밖. 신규 테이블만 만들어 기존 이미지와 호환된다.

CREATE TABLE trading.strategy_dca_version (
    strategy_version_id uuid NOT NULL,
    direction character varying(20) NOT NULL,
    amount_usd numeric(20,2) NOT NULL,
    order_type character varying(20) NOT NULL,
    frequency character varying(20) NOT NULL,
    day_of_week character varying(20),
    day_of_month integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_dca_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_dca_version_strategy_version_id_fkey FOREIGN KEY (strategy_version_id)
        REFERENCES trading.strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT strategy_dca_version_amount_usd_check CHECK (amount_usd > 0),
    CONSTRAINT strategy_dca_version_day_of_month_check CHECK (day_of_month BETWEEN 1 AND 31),
    CONSTRAINT strategy_dca_version_weekly_check CHECK ((frequency = 'WEEKLY') = (day_of_week IS NOT NULL)),
    CONSTRAINT strategy_dca_version_monthly_check CHECK (frequency = 'MONTHLY' OR day_of_month IS NULL)
);

CREATE TABLE trading.dca_execution (
    id uuid NOT NULL,
    strategy_id uuid NOT NULL,
    trade_date date NOT NULL,
    status character varying(20) NOT NULL,
    skip_reason character varying(30),
    order_id uuid,
    quantity integer,
    reference_price numeric(12,2),
    detail character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dca_execution_pkey PRIMARY KEY (id),
    CONSTRAINT dca_execution_strategy_id_fkey FOREIGN KEY (strategy_id)
        REFERENCES trading.strategy(id) ON DELETE CASCADE,
    CONSTRAINT uq_dca_execution_strategy_trade_date UNIQUE (strategy_id, trade_date)
);

COMMENT ON COLUMN trading.dca_execution.trade_date IS 'KST 거래일 — orders.trade_date와 동일 기준';
