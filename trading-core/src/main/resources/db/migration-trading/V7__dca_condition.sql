-- DCA 회차 조건 — 버전별(strategy_dca_version 1:N), AND 결합, sort_order로 입력 순서 보존.
-- trading-core 단독 스키마라 root expand/contract 제약 밖. 신규 테이블만 만들어 기존 이미지와 호환된다.

CREATE TABLE trading.strategy_dca_condition (
    id uuid NOT NULL,
    strategy_version_id uuid NOT NULL,
    sort_order integer NOT NULL,
    indicator character varying(20) NOT NULL,
    operator character varying(20) NOT NULL,
    sma_period integer,
    threshold numeric(10,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_dca_condition_pkey PRIMARY KEY (id),
    CONSTRAINT strategy_dca_condition_strategy_version_id_fkey FOREIGN KEY (strategy_version_id)
        REFERENCES trading.strategy_dca_version(strategy_version_id) ON DELETE CASCADE,
    CONSTRAINT uq_strategy_dca_condition_version_sort UNIQUE (strategy_version_id, sort_order),
    CONSTRAINT strategy_dca_condition_sma_period_check CHECK ((indicator = 'SMA') = (sma_period IS NOT NULL)),
    CONSTRAINT strategy_dca_condition_threshold_check CHECK ((indicator = 'SMA') = (threshold IS NULL))
);
