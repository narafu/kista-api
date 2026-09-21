-- 스키마 재편(서비스별 소유 분리) 후 baseline. 옛 마이그레이션을 스쿼시해 pg_dump --schema-only로 기계 생성했다
-- (deploy/server/schema-reorg/ 참고). 이 파일은 trading 소유 스키마만 만든다: trading/trading_ref.
-- 기본 스키마(trading)는 Flyway가 생성한다. 운영 DB는 이행 SQL 적용 후 baseline-on-migrate로 이 버전을 건너뛴다.

--
-- Name: trading_ref; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA trading_ref;


--
-- Name: accounts; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    nickname character varying(100) NOT NULL,
    broker character varying(20) DEFAULT 'KIS'::character varying NOT NULL,
    account_no character varying(512) NOT NULL,
    broker_account_code character varying(10),
    app_key character varying(512) NOT NULL,
    secret_key character varying(512) NOT NULL,
    account_no_hash character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: broker_tokens; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.broker_tokens (
    account_id uuid NOT NULL,
    access_token text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: cycle_position; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.cycle_position (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_cycle_id uuid NOT NULL,
    usd_deposit numeric(20,2) NOT NULL,
    closing_price numeric(12,2),
    avg_price numeric(20,2),
    holdings integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: cycle_position_infinite; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.cycle_position_infinite (
    cycle_position_id uuid NOT NULL,
    is_reverse_mode boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: event_publication; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.event_publication (
    id uuid NOT NULL,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamp with time zone NOT NULL,
    completion_date timestamp with time zone,
    status text,
    completion_attempts integer,
    last_resubmission_date timestamp with time zone
);


--
-- Name: orders; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    account_id uuid NOT NULL,
    strategy_cycle_id uuid NOT NULL,
    trade_date date NOT NULL,
    ticker character varying(20) NOT NULL,
    order_type character varying(10) NOT NULL,
    timing character varying(20) DEFAULT 'AT_CLOSE'::character varying NOT NULL,
    direction character varying(5) NOT NULL,
    order_leg character varying(50) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    price numeric(12,2) NOT NULL,
    quantity integer NOT NULL,
    status character varying(20) NOT NULL,
    external_order_id character varying(255),
    filled_quantity integer,
    filled_price numeric(12,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: COLUMN orders.trade_date; Type: COMMENT; Schema: trading; Owner: -
--

COMMENT ON COLUMN trading.orders.trade_date IS 'KST 거래일 — 매매가 실행·정산되는 KST 아침이 속한 날';


--
-- Name: scheduler_locks; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.scheduler_locks (
    name character varying(100) NOT NULL,
    lock_until timestamp with time zone NOT NULL,
    locked_at timestamp with time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);


--
-- Name: strategy; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.strategy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    account_id uuid NOT NULL,
    type character varying(20) NOT NULL,
    ticker character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    cycle_seed_type character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: strategy_cycle; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.strategy_cycle (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    strategy_version_id uuid NOT NULL,
    start_amount numeric(20,2) NOT NULL,
    end_amount numeric(20,2),
    start_date date NOT NULL,
    end_date date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: strategy_cycle_vr; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.strategy_cycle_vr (
    strategy_cycle_id uuid NOT NULL,
    value numeric(20,2) NOT NULL,
    gradient integer NOT NULL,
    pool_limit_rate numeric(6,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_cycle_vr_gradient_check CHECK ((gradient > 0)),
    CONSTRAINT strategy_cycle_vr_pool_limit_rate_check CHECK (((pool_limit_rate > (0)::numeric) AND (pool_limit_rate <= (1)::numeric)))
);


--
-- Name: strategy_infinite_version; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.strategy_infinite_version (
    strategy_version_id uuid NOT NULL,
    division_count integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: strategy_version; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.strategy_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    version_no integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: strategy_vr_version; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.strategy_vr_version (
    strategy_version_id uuid NOT NULL,
    interval_weeks integer NOT NULL,
    band_width numeric(20,2) NOT NULL,
    recurring_amount integer NOT NULL,
    initial_gradient integer NOT NULL,
    g_grace_weeks integer NOT NULL,
    g_step_weeks integer NOT NULL,
    g_max integer NOT NULL,
    initial_pool_limit_rate numeric(6,2) NOT NULL,
    p_grace_weeks integer NOT NULL,
    p_step_weeks integer NOT NULL,
    pool_limit_floor numeric(6,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_vr_version_g_max_check CHECK (((g_step_weeks = 0) OR (g_max >= initial_gradient))),
    CONSTRAINT strategy_vr_version_g_step_weeks_check CHECK ((g_step_weeks >= 0)),
    CONSTRAINT strategy_vr_version_initial_gradient_check CHECK ((initial_gradient > 0)),
    CONSTRAINT strategy_vr_version_initial_pool_limit_rate_check CHECK ((initial_pool_limit_rate <= (1)::numeric)),
    CONSTRAINT strategy_vr_version_interval_weeks_check CHECK ((interval_weeks > 0)),
    CONSTRAINT strategy_vr_version_p_step_weeks_check CHECK ((p_step_weeks >= 0)),
    CONSTRAINT strategy_vr_version_pool_limit_floor_check CHECK ((pool_limit_floor >= (0)::numeric)),
    CONSTRAINT strategy_vr_version_pool_limit_floor_le_initial_check CHECK ((pool_limit_floor <= initial_pool_limit_rate))
);


--
-- Name: user_notify_profile; Type: TABLE; Schema: trading; Owner: -
--

CREATE TABLE trading.user_notify_profile (
    user_id uuid NOT NULL,
    notification_prefs text DEFAULT '{}'::text NOT NULL,
    balance_check_enabled boolean DEFAULT true NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    telegram_bot_token character varying(512),
    chat_id character varying(64)
);


--
-- Name: privacy_trade_base_orders; Type: TABLE; Schema: trading_ref; Owner: -
--

CREATE TABLE trading_ref.privacy_trade_base_orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    privacy_trade_id uuid NOT NULL,
    direction character varying(5) NOT NULL,
    order_type character varying(10) NOT NULL,
    price numeric(12,2) NOT NULL,
    quantity integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: privacy_trade_bases; Type: TABLE; Schema: trading_ref; Owner: -
--

CREATE TABLE trading_ref.privacy_trade_bases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    release_date date NOT NULL,
    ticker character varying(20) NOT NULL,
    current_cycle_start numeric(12,2) NOT NULL,
    current_cycle_realized_pnl numeric(12,2) NOT NULL,
    avg_price numeric(12,2),
    holdings integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: us_market_holidays; Type: TABLE; Schema: trading_ref; Owner: -
--

CREATE TABLE trading_ref.us_market_holidays (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    trade_date date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: accounts accounts_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.accounts
    ADD CONSTRAINT accounts_pkey PRIMARY KEY (id);


--
-- Name: broker_tokens broker_tokens_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.broker_tokens
    ADD CONSTRAINT broker_tokens_pkey PRIMARY KEY (account_id);


--
-- Name: cycle_position_infinite cycle_position_infinite_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.cycle_position_infinite
    ADD CONSTRAINT cycle_position_infinite_pkey PRIMARY KEY (cycle_position_id);


--
-- Name: cycle_position cycle_position_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.cycle_position
    ADD CONSTRAINT cycle_position_pkey PRIMARY KEY (id);


--
-- Name: event_publication event_publication_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: scheduler_locks scheduler_locks_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.scheduler_locks
    ADD CONSTRAINT scheduler_locks_pkey PRIMARY KEY (name);


--
-- Name: strategy_cycle strategy_cycle_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_cycle
    ADD CONSTRAINT strategy_cycle_pkey PRIMARY KEY (id);


--
-- Name: strategy_cycle_vr strategy_cycle_vr_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_cycle_vr
    ADD CONSTRAINT strategy_cycle_vr_pkey PRIMARY KEY (strategy_cycle_id);


--
-- Name: strategy_infinite_version strategy_infinite_version_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_infinite_version
    ADD CONSTRAINT strategy_infinite_version_pkey PRIMARY KEY (strategy_version_id);


--
-- Name: strategy strategy_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy
    ADD CONSTRAINT strategy_pkey PRIMARY KEY (id);


--
-- Name: strategy_version strategy_version_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_version
    ADD CONSTRAINT strategy_version_pkey PRIMARY KEY (id);


--
-- Name: strategy_vr_version strategy_vr_version_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_vr_version
    ADD CONSTRAINT strategy_vr_version_pkey PRIMARY KEY (strategy_version_id);


--
-- Name: user_notify_profile user_notify_profile_pkey; Type: CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.user_notify_profile
    ADD CONSTRAINT user_notify_profile_pkey PRIMARY KEY (user_id);


--
-- Name: privacy_trade_base_orders privacy_trade_base_orders_pkey; Type: CONSTRAINT; Schema: trading_ref; Owner: -
--

ALTER TABLE ONLY trading_ref.privacy_trade_base_orders
    ADD CONSTRAINT privacy_trade_base_orders_pkey PRIMARY KEY (id);


--
-- Name: privacy_trade_bases privacy_trade_bases_pkey; Type: CONSTRAINT; Schema: trading_ref; Owner: -
--

ALTER TABLE ONLY trading_ref.privacy_trade_bases
    ADD CONSTRAINT privacy_trade_bases_pkey PRIMARY KEY (id);


--
-- Name: privacy_trade_bases uq_privacy_trade_bases_release_date_ticker; Type: CONSTRAINT; Schema: trading_ref; Owner: -
--

ALTER TABLE ONLY trading_ref.privacy_trade_bases
    ADD CONSTRAINT uq_privacy_trade_bases_release_date_ticker UNIQUE (release_date, ticker);


--
-- Name: us_market_holidays us_market_holidays_pkey; Type: CONSTRAINT; Schema: trading_ref; Owner: -
--

ALTER TABLE ONLY trading_ref.us_market_holidays
    ADD CONSTRAINT us_market_holidays_pkey PRIMARY KEY (id);


--
-- Name: us_market_holidays us_market_holidays_trade_date_key; Type: CONSTRAINT; Schema: trading_ref; Owner: -
--

ALTER TABLE ONLY trading_ref.us_market_holidays
    ADD CONSTRAINT us_market_holidays_trade_date_key UNIQUE (trade_date);


--
-- Name: event_publication_by_completion_date_idx; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX event_publication_by_completion_date_idx ON trading.event_publication USING btree (completion_date);


--
-- Name: event_publication_serialized_event_hash_idx; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX event_publication_serialized_event_hash_idx ON trading.event_publication USING hash (serialized_event);


--
-- Name: idx_accounts_user_id; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_accounts_user_id ON trading.accounts USING btree (user_id);


--
-- Name: idx_cycle_position_strategy_cycle_id; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_cycle_position_strategy_cycle_id ON trading.cycle_position USING btree (strategy_cycle_id);


--
-- Name: idx_orders_account_date_status_direction; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_orders_account_date_status_direction ON trading.orders USING btree (account_id, trade_date, status, direction);


--
-- Name: idx_orders_account_date_ticker_direction_status; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_orders_account_date_ticker_direction_status ON trading.orders USING btree (account_id, trade_date, ticker, direction, status);


--
-- Name: idx_orders_cycle_date_status; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_orders_cycle_date_status ON trading.orders USING btree (strategy_cycle_id, trade_date, status);


--
-- Name: idx_orders_cycle_date_timing_status; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_orders_cycle_date_timing_status ON trading.orders USING btree (strategy_cycle_id, trade_date, timing, status);


--
-- Name: idx_orders_trade_date; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_orders_trade_date ON trading.orders USING btree (trade_date);


--
-- Name: idx_scheduler_locks_lock_until; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_scheduler_locks_lock_until ON trading.scheduler_locks USING btree (lock_until);


--
-- Name: idx_strategy_account_id; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_strategy_account_id ON trading.strategy USING btree (account_id);


--
-- Name: idx_strategy_cycle_strategy_id; Type: INDEX; Schema: trading; Owner: -
--

CREATE INDEX idx_strategy_cycle_strategy_id ON trading.strategy_cycle USING btree (strategy_id);


--
-- Name: uq_accounts_account_no_hash; Type: INDEX; Schema: trading; Owner: -
--

CREATE UNIQUE INDEX uq_accounts_account_no_hash ON trading.accounts USING btree (account_no_hash) WHERE ((deleted_at IS NULL) AND (account_no_hash IS NOT NULL));


--
-- Name: uq_strategy_version_strategy_version_no; Type: INDEX; Schema: trading; Owner: -
--

CREATE UNIQUE INDEX uq_strategy_version_strategy_version_no ON trading.strategy_version USING btree (strategy_id, version_no);


--
-- Name: broker_tokens broker_tokens_account_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.broker_tokens
    ADD CONSTRAINT broker_tokens_account_id_fkey FOREIGN KEY (account_id) REFERENCES trading.accounts(id) ON DELETE CASCADE;


--
-- Name: cycle_position_infinite cycle_position_infinite_cycle_position_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.cycle_position_infinite
    ADD CONSTRAINT cycle_position_infinite_cycle_position_id_fkey FOREIGN KEY (cycle_position_id) REFERENCES trading.cycle_position(id) ON DELETE CASCADE;


--
-- Name: cycle_position cycle_position_strategy_cycle_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.cycle_position
    ADD CONSTRAINT cycle_position_strategy_cycle_id_fkey FOREIGN KEY (strategy_cycle_id) REFERENCES trading.strategy_cycle(id) ON DELETE CASCADE;


--
-- Name: orders orders_account_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.orders
    ADD CONSTRAINT orders_account_id_fkey FOREIGN KEY (account_id) REFERENCES trading.accounts(id) ON DELETE CASCADE;


--
-- Name: orders orders_strategy_cycle_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.orders
    ADD CONSTRAINT orders_strategy_cycle_id_fkey FOREIGN KEY (strategy_cycle_id) REFERENCES trading.strategy_cycle(id) ON DELETE CASCADE;


--
-- Name: strategy strategy_account_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy
    ADD CONSTRAINT strategy_account_id_fkey FOREIGN KEY (account_id) REFERENCES trading.accounts(id) ON DELETE CASCADE;


--
-- Name: strategy_cycle strategy_cycle_strategy_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_cycle
    ADD CONSTRAINT strategy_cycle_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES trading.strategy(id) ON DELETE CASCADE;


--
-- Name: strategy_cycle strategy_cycle_strategy_version_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_cycle
    ADD CONSTRAINT strategy_cycle_strategy_version_id_fkey FOREIGN KEY (strategy_version_id) REFERENCES trading.strategy_version(id) ON DELETE CASCADE;


--
-- Name: strategy_cycle_vr strategy_cycle_vr_strategy_cycle_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_cycle_vr
    ADD CONSTRAINT strategy_cycle_vr_strategy_cycle_id_fkey FOREIGN KEY (strategy_cycle_id) REFERENCES trading.strategy_cycle(id) ON DELETE CASCADE;


--
-- Name: strategy_infinite_version strategy_infinite_version_strategy_version_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_infinite_version
    ADD CONSTRAINT strategy_infinite_version_strategy_version_id_fkey FOREIGN KEY (strategy_version_id) REFERENCES trading.strategy_version(id) ON DELETE CASCADE;


--
-- Name: strategy_version strategy_version_strategy_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_version
    ADD CONSTRAINT strategy_version_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES trading.strategy(id) ON DELETE CASCADE;


--
-- Name: strategy_vr_version strategy_vr_version_strategy_version_id_fkey; Type: FK CONSTRAINT; Schema: trading; Owner: -
--

ALTER TABLE ONLY trading.strategy_vr_version
    ADD CONSTRAINT strategy_vr_version_strategy_version_id_fkey FOREIGN KEY (strategy_version_id) REFERENCES trading.strategy_version(id) ON DELETE CASCADE;


--
-- Name: privacy_trade_base_orders privacy_trade_base_orders_privacy_trade_id_fkey; Type: FK CONSTRAINT; Schema: trading_ref; Owner: -
--

ALTER TABLE ONLY trading_ref.privacy_trade_base_orders
    ADD CONSTRAINT privacy_trade_base_orders_privacy_trade_id_fkey FOREIGN KEY (privacy_trade_id) REFERENCES trading_ref.privacy_trade_bases(id) ON DELETE CASCADE;
