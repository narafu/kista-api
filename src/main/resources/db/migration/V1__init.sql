-- 스키마 재편(서비스별 소유 분리) 후 baseline. 옛 마이그레이션을 스쿼시해 pg_dump --schema-only로 기계 생성했다
-- (deploy/server/schema-reorg/ 참고). 이 파일은 root 소유 스키마만 만든다: public/finance/kista_ref.
-- 기본 스키마(public)는 Flyway가 생성한다. 운영 DB는 이행 SQL 적용 후 baseline-on-migrate로 이 버전을 건너뛴다.

-- finance_budgets의 기간 겹침 방지 EXCLUDE USING gist(uuid = 비교)에 필요 — pg_dump -n 은 확장을 덤프하지 않아 수동 추가
CREATE EXTENSION btree_gist WITH SCHEMA public;

--
-- Name: finance; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA finance;


--
-- Name: kista_ref; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA kista_ref;


--
-- Name: finance_accounts; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid,
    user_id uuid NOT NULL,
    account_type character varying(20) NOT NULL,
    name character varying(50) NOT NULL,
    account_no character varying(512),
    memo character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    account_no_hash character varying(64)
);


--
-- Name: finance_asset_snapshots; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_asset_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid,
    category_id uuid NOT NULL,
    account_id uuid,
    user_id uuid NOT NULL,
    entry_date date NOT NULL,
    asset_class character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    strategy character varying(50),
    amount bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    memo character varying(255),
    CONSTRAINT finance_asset_snapshots_amount_check CHECK ((amount >= 0))
);


--
-- Name: finance_budgets; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_budgets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid,
    category_id uuid NOT NULL,
    user_id uuid NOT NULL,
    apply_start_date date NOT NULL,
    apply_end_date date,
    amount bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT finance_budgets_amount_check CHECK ((amount > 0)),
    CONSTRAINT finance_budgets_period_check CHECK (((apply_end_date IS NULL) OR (apply_end_date >= apply_start_date)))
);


--
-- Name: finance_categories; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid,
    parent_id uuid,
    user_id uuid,
    type character varying(20) NOT NULL,
    name character varying(50) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT finance_categories_ownership_check CHECK (((user_id IS NOT NULL) OR (group_id IS NULL)))
);


--
-- Name: finance_group_invitations; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_group_invitations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid NOT NULL,
    invited_by uuid NOT NULL,
    invitee_user_id uuid,
    code character varying(16) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: finance_group_members; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_group_members (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role character varying(20) DEFAULT 'MEMBER'::character varying NOT NULL,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: finance_groups; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_groups (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    owner_user_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: finance_monthly_closings; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_monthly_closings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid,
    user_id uuid,
    month character varying(7) NOT NULL,
    completed boolean DEFAULT false NOT NULL,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: finance_transactions; Type: TABLE; Schema: finance; Owner: -
--

CREATE TABLE finance.finance_transactions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id uuid,
    category_id uuid NOT NULL,
    user_id uuid NOT NULL,
    transaction_date date NOT NULL,
    amount bigint NOT NULL,
    memo character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT finance_transactions_amount_check CHECK ((amount >= 0))
);


--
-- Name: fear_greed_snapshots; Type: TABLE; Schema: kista_ref; Owner: -
--

CREATE TABLE kista_ref.fear_greed_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source character varying(20) NOT NULL,
    snapshot_date timestamp with time zone NOT NULL,
    value integer NOT NULL,
    rating character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: housing_benchmark_prices; Type: TABLE; Schema: kista_ref; Owner: -
--

CREATE TABLE kista_ref.housing_benchmark_prices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source character varying(20) NOT NULL,
    metric_code character varying(40) NOT NULL,
    region_code character varying(20) NOT NULL,
    region_name character varying(50) NOT NULL,
    base_month date NOT NULL,
    first_quintile_price numeric(18,6) NOT NULL,
    second_quintile_price numeric(18,6) NOT NULL,
    third_quintile_price numeric(18,6) NOT NULL,
    fourth_quintile_price numeric(18,6) NOT NULL,
    fifth_quintile_price numeric(18,6) NOT NULL,
    fifth_quintile_ratio numeric(18,12) NOT NULL,
    source_updated_date date,
    fetched_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: housing_price_indices; Type: TABLE; Schema: kista_ref; Owner: -
--

CREATE TABLE kista_ref.housing_price_indices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source character varying(20) NOT NULL,
    metric_code character varying(40) NOT NULL,
    region_code character varying(20) NOT NULL,
    region_name character varying(50) NOT NULL,
    base_date date NOT NULL,
    index_value numeric(18,12) NOT NULL,
    source_updated_date date,
    fetched_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: market_index_prices; Type: TABLE; Schema: kista_ref; Owner: -
--

CREATE TABLE kista_ref.market_index_prices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    symbol character varying(10) NOT NULL,
    trade_date date NOT NULL,
    close_price numeric(12,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: admin_runtime_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_runtime_settings (
    setting_key character varying(100) NOT NULL,
    setting_value jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: app_error_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_error_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    error_type character varying(255) NOT NULL,
    message text,
    stack_trace text,
    context jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    admin_id uuid,
    action character varying(64) NOT NULL,
    target_type character varying(64),
    target_id uuid,
    payload jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: event_publication; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.event_publication (
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
-- Name: fcm_device_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fcm_device_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token text NOT NULL,
    platform character varying(10) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    user_agent character varying(512),
    expires_at timestamp with time zone NOT NULL,
    rotated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: scheduler_locks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scheduler_locks (
    name character varying(100) NOT NULL,
    lock_until timestamp with time zone NOT NULL,
    locked_at timestamp with time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);


--
-- Name: user_notification_prefs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notification_prefs (
    user_id uuid NOT NULL,
    type character varying(50) NOT NULL,
    enabled boolean DEFAULT true NOT NULL
);


--
-- Name: user_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_settings (
    user_id uuid NOT NULL,
    balance_check_enabled boolean DEFAULT true NOT NULL,
    strategy_suggestions jsonb
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid NOT NULL,
    kakao_id character varying(50) NOT NULL,
    nickname character varying(100),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    telegram_bot_token character varying(512),
    telegram_chat_id character varying(50),
    telegram_bot_username character varying(64),
    reject_reason text,
    last_reapplied_at timestamp with time zone,
    notification_channel character varying(20) DEFAULT 'TELEGRAM'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    email character varying(512)
);


--
-- Name: finance_accounts finance_accounts_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_accounts
    ADD CONSTRAINT finance_accounts_pkey PRIMARY KEY (id);


--
-- Name: finance_asset_snapshots finance_asset_snapshots_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_asset_snapshots
    ADD CONSTRAINT finance_asset_snapshots_pkey PRIMARY KEY (id);


--
-- Name: finance_budgets finance_budgets_group_no_overlap; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_budgets
    ADD CONSTRAINT finance_budgets_group_no_overlap EXCLUDE USING gist (group_id WITH =, category_id WITH =, daterange(apply_start_date, apply_end_date, '[]'::text) WITH &&) WHERE ((group_id IS NOT NULL));


--
-- Name: finance_budgets finance_budgets_personal_no_overlap; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_budgets
    ADD CONSTRAINT finance_budgets_personal_no_overlap EXCLUDE USING gist (user_id WITH =, category_id WITH =, daterange(apply_start_date, apply_end_date, '[]'::text) WITH &&) WHERE ((group_id IS NULL));


--
-- Name: finance_budgets finance_budgets_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_budgets
    ADD CONSTRAINT finance_budgets_pkey PRIMARY KEY (id);


--
-- Name: finance_categories finance_categories_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_categories
    ADD CONSTRAINT finance_categories_pkey PRIMARY KEY (id);


--
-- Name: finance_group_invitations finance_group_invitations_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_invitations
    ADD CONSTRAINT finance_group_invitations_pkey PRIMARY KEY (id);


--
-- Name: finance_group_members finance_group_members_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_members
    ADD CONSTRAINT finance_group_members_pkey PRIMARY KEY (id);


--
-- Name: finance_groups finance_groups_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_groups
    ADD CONSTRAINT finance_groups_pkey PRIMARY KEY (id);


--
-- Name: finance_monthly_closings finance_monthly_closings_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_monthly_closings
    ADD CONSTRAINT finance_monthly_closings_pkey PRIMARY KEY (id);


--
-- Name: finance_transactions finance_transactions_pkey; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_transactions
    ADD CONSTRAINT finance_transactions_pkey PRIMARY KEY (id);


--
-- Name: finance_group_invitations uq_finance_group_invitations_code; Type: CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_invitations
    ADD CONSTRAINT uq_finance_group_invitations_code UNIQUE (code);


--
-- Name: fear_greed_snapshots fear_greed_snapshots_pkey; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.fear_greed_snapshots
    ADD CONSTRAINT fear_greed_snapshots_pkey PRIMARY KEY (id);


--
-- Name: housing_benchmark_prices housing_benchmark_prices_pkey; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.housing_benchmark_prices
    ADD CONSTRAINT housing_benchmark_prices_pkey PRIMARY KEY (id);


--
-- Name: housing_price_indices housing_price_indices_pkey; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.housing_price_indices
    ADD CONSTRAINT housing_price_indices_pkey PRIMARY KEY (id);


--
-- Name: market_index_prices market_index_prices_pkey; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.market_index_prices
    ADD CONSTRAINT market_index_prices_pkey PRIMARY KEY (id);


--
-- Name: fear_greed_snapshots uq_fear_greed_source_date; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.fear_greed_snapshots
    ADD CONSTRAINT uq_fear_greed_source_date UNIQUE (source, snapshot_date);


--
-- Name: housing_benchmark_prices uq_housing_benchmark_prices_source_metric_region_month; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.housing_benchmark_prices
    ADD CONSTRAINT uq_housing_benchmark_prices_source_metric_region_month UNIQUE (source, metric_code, region_code, base_month);


--
-- Name: housing_price_indices uq_housing_price_indices_source_metric_region_date; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.housing_price_indices
    ADD CONSTRAINT uq_housing_price_indices_source_metric_region_date UNIQUE (source, metric_code, region_code, base_date);


--
-- Name: market_index_prices uq_market_index_prices_symbol_date; Type: CONSTRAINT; Schema: kista_ref; Owner: -
--

ALTER TABLE ONLY kista_ref.market_index_prices
    ADD CONSTRAINT uq_market_index_prices_symbol_date UNIQUE (symbol, trade_date);


--
-- Name: admin_runtime_settings admin_runtime_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_runtime_settings
    ADD CONSTRAINT admin_runtime_settings_pkey PRIMARY KEY (setting_key);


--
-- Name: app_error_logs app_error_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_error_logs
    ADD CONSTRAINT app_error_logs_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: event_publication event_publication_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);


--
-- Name: fcm_device_tokens fcm_device_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_device_tokens
    ADD CONSTRAINT fcm_device_tokens_pkey PRIMARY KEY (id);


--
-- Name: fcm_device_tokens fcm_device_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_device_tokens
    ADD CONSTRAINT fcm_device_tokens_token_key UNIQUE (token);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: scheduler_locks scheduler_locks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scheduler_locks
    ADD CONSTRAINT scheduler_locks_pkey PRIMARY KEY (name);


--
-- Name: refresh_tokens uq_refresh_tokens_token_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash);


--
-- Name: user_notification_prefs user_notification_prefs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_prefs
    ADD CONSTRAINT user_notification_prefs_pkey PRIMARY KEY (user_id, type);


--
-- Name: user_settings user_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_pkey PRIMARY KEY (user_id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: idx_finance_asset_snapshots_category_id; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_asset_snapshots_category_id ON finance.finance_asset_snapshots USING btree (category_id);


--
-- Name: idx_finance_asset_snapshots_group_entry_date; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_asset_snapshots_group_entry_date ON finance.finance_asset_snapshots USING btree (group_id, entry_date DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_finance_asset_snapshots_personal_entry_date; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_asset_snapshots_personal_entry_date ON finance.finance_asset_snapshots USING btree (user_id, entry_date DESC) WHERE ((group_id IS NULL) AND (deleted_at IS NULL));


--
-- Name: idx_finance_budgets_group_category_start; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_budgets_group_category_start ON finance.finance_budgets USING btree (group_id, category_id, apply_start_date DESC);


--
-- Name: idx_finance_categories_group_type; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_categories_group_type ON finance.finance_categories USING btree (group_id, type) WHERE (deleted_at IS NULL);


--
-- Name: idx_finance_categories_parent_id; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_categories_parent_id ON finance.finance_categories USING btree (parent_id);


--
-- Name: idx_finance_group_invitations_group_id; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_group_invitations_group_id ON finance.finance_group_invitations USING btree (group_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_finance_group_members_user_id; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_group_members_user_id ON finance.finance_group_members USING btree (user_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_finance_transactions_category_id; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_transactions_category_id ON finance.finance_transactions USING btree (category_id);


--
-- Name: idx_finance_transactions_group_date; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_transactions_group_date ON finance.finance_transactions USING btree (group_id, transaction_date DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_finance_transactions_personal_date; Type: INDEX; Schema: finance; Owner: -
--

CREATE INDEX idx_finance_transactions_personal_date ON finance.finance_transactions USING btree (user_id, transaction_date DESC) WHERE ((group_id IS NULL) AND (deleted_at IS NULL));


--
-- Name: uq_finance_accounts_account_no_hash; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_accounts_account_no_hash ON finance.finance_accounts USING btree (account_no_hash) WHERE ((deleted_at IS NULL) AND (account_no_hash IS NOT NULL));


--
-- Name: uq_finance_categories_group_parent_name; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_categories_group_parent_name ON finance.finance_categories USING btree (group_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), name) WHERE ((group_id IS NOT NULL) AND (deleted_at IS NULL));


--
-- Name: uq_finance_categories_personal_parent_name; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_categories_personal_parent_name ON finance.finance_categories USING btree (user_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), name) WHERE ((group_id IS NULL) AND (user_id IS NOT NULL) AND (deleted_at IS NULL));


--
-- Name: uq_finance_group_members_group_user; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_group_members_group_user ON finance.finance_group_members USING btree (group_id, user_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_finance_group_members_one_active_group; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_group_members_one_active_group ON finance.finance_group_members USING btree (user_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_finance_monthly_closings_group_month; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_monthly_closings_group_month ON finance.finance_monthly_closings USING btree (group_id, month) WHERE (group_id IS NOT NULL);


--
-- Name: uq_finance_monthly_closings_personal_month; Type: INDEX; Schema: finance; Owner: -
--

CREATE UNIQUE INDEX uq_finance_monthly_closings_personal_month ON finance.finance_monthly_closings USING btree (user_id, month) WHERE ((group_id IS NULL) AND (user_id IS NOT NULL));


--
-- Name: idx_housing_benchmark_prices_metric_region_month; Type: INDEX; Schema: kista_ref; Owner: -
--

CREATE INDEX idx_housing_benchmark_prices_metric_region_month ON kista_ref.housing_benchmark_prices USING btree (metric_code, region_code, base_month);


--
-- Name: idx_housing_price_indices_metric_region_date; Type: INDEX; Schema: kista_ref; Owner: -
--

CREATE INDEX idx_housing_price_indices_metric_region_date ON kista_ref.housing_price_indices USING btree (metric_code, region_code, base_date);


--
-- Name: event_publication_by_completion_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX event_publication_by_completion_date_idx ON public.event_publication USING btree (completion_date);


--
-- Name: event_publication_serialized_event_hash_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX event_publication_serialized_event_hash_idx ON public.event_publication USING hash (serialized_event);


--
-- Name: idx_app_error_logs_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_error_logs_created_at ON public.app_error_logs USING btree (created_at DESC);


--
-- Name: idx_refresh_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);


--
-- Name: idx_scheduler_locks_lock_until; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scheduler_locks_lock_until ON public.scheduler_locks USING btree (lock_until);


--
-- Name: uq_users_kakao_id_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_kakao_id_active ON public.users USING btree (kakao_id) WHERE (deleted_at IS NULL);


--
-- Name: finance_accounts finance_accounts_created_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_accounts
    ADD CONSTRAINT finance_accounts_created_by_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_accounts finance_accounts_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_accounts
    ADD CONSTRAINT finance_accounts_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_asset_snapshots finance_asset_snapshots_account_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_asset_snapshots
    ADD CONSTRAINT finance_asset_snapshots_account_id_fkey FOREIGN KEY (account_id) REFERENCES finance.finance_accounts(id);


--
-- Name: finance_asset_snapshots finance_asset_snapshots_category_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_asset_snapshots
    ADD CONSTRAINT finance_asset_snapshots_category_id_fkey FOREIGN KEY (category_id) REFERENCES finance.finance_categories(id) ON DELETE RESTRICT;


--
-- Name: finance_asset_snapshots finance_asset_snapshots_created_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_asset_snapshots
    ADD CONSTRAINT finance_asset_snapshots_created_by_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_asset_snapshots finance_asset_snapshots_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_asset_snapshots
    ADD CONSTRAINT finance_asset_snapshots_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_budgets finance_budgets_category_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_budgets
    ADD CONSTRAINT finance_budgets_category_id_fkey FOREIGN KEY (category_id) REFERENCES finance.finance_categories(id);


--
-- Name: finance_budgets finance_budgets_created_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_budgets
    ADD CONSTRAINT finance_budgets_created_by_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_budgets finance_budgets_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_budgets
    ADD CONSTRAINT finance_budgets_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_categories finance_categories_created_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_categories
    ADD CONSTRAINT finance_categories_created_by_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_categories finance_categories_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_categories
    ADD CONSTRAINT finance_categories_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_categories finance_categories_parent_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_categories
    ADD CONSTRAINT finance_categories_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES finance.finance_categories(id);


--
-- Name: finance_group_invitations finance_group_invitations_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_invitations
    ADD CONSTRAINT finance_group_invitations_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_group_invitations finance_group_invitations_invited_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_invitations
    ADD CONSTRAINT finance_group_invitations_invited_by_fkey FOREIGN KEY (invited_by) REFERENCES public.users(id);


--
-- Name: finance_group_invitations finance_group_invitations_invitee_user_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_invitations
    ADD CONSTRAINT finance_group_invitations_invitee_user_id_fkey FOREIGN KEY (invitee_user_id) REFERENCES public.users(id);


--
-- Name: finance_group_members finance_group_members_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_members
    ADD CONSTRAINT finance_group_members_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_group_members finance_group_members_user_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_group_members
    ADD CONSTRAINT finance_group_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_groups finance_groups_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_groups
    ADD CONSTRAINT finance_groups_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.users(id);


--
-- Name: finance_monthly_closings finance_monthly_closings_closed_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_monthly_closings
    ADD CONSTRAINT finance_monthly_closings_closed_by_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_monthly_closings finance_monthly_closings_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_monthly_closings
    ADD CONSTRAINT finance_monthly_closings_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: finance_transactions finance_transactions_category_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_transactions
    ADD CONSTRAINT finance_transactions_category_id_fkey FOREIGN KEY (category_id) REFERENCES finance.finance_categories(id) ON DELETE RESTRICT;


--
-- Name: finance_transactions finance_transactions_created_by_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_transactions
    ADD CONSTRAINT finance_transactions_created_by_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: finance_transactions finance_transactions_group_id_fkey; Type: FK CONSTRAINT; Schema: finance; Owner: -
--

ALTER TABLE ONLY finance.finance_transactions
    ADD CONSTRAINT finance_transactions_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance.finance_groups(id);


--
-- Name: audit_logs audit_logs_admin_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_admin_id_fkey FOREIGN KEY (admin_id) REFERENCES public.users(id) ON DELETE SET NULL;


--
-- Name: fcm_device_tokens fcm_device_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_device_tokens
    ADD CONSTRAINT fcm_device_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: refresh_tokens refresh_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: user_notification_prefs user_notification_prefs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_prefs
    ADD CONSTRAINT user_notification_prefs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_settings user_settings_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;

-- 시드 데이터 — 옛 마이그레이션이 만들던 값(빈 DB에서만 실행됨; 운영은 baseline으로 건너뜀)
-- finance_categories: 시스템 전역 카테고리(고정 UUID — Java 상수·테스트가 참조). L1을 먼저 넣는다(parent_id 자기참조 FK).
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000101', NULL, NULL, NULL, 'INCOME', '근로소득', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000102', NULL, NULL, NULL, 'INCOME', '금융소득', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000103', NULL, NULL, NULL, 'INCOME', '부동산소득', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000104', NULL, NULL, NULL, 'INCOME', '기타소득', 40, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000201', NULL, NULL, NULL, 'EXPENSE', '주거비', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000202', NULL, NULL, NULL, 'EXPENSE', '생활비', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000203', NULL, NULL, NULL, 'EXPENSE', '용돈', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000204', NULL, NULL, NULL, 'EXPENSE', '대출이자', 40, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000301', NULL, NULL, NULL, 'SAVING', 'DCA', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000302', NULL, NULL, NULL, 'SAVING', '주택청약종합저축', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000303', NULL, NULL, NULL, 'SAVING', '연금저축보험', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000401', NULL, NULL, NULL, 'ASSET', '예적금', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000402', NULL, NULL, NULL, 'ASSET', '부동산', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000403', NULL, NULL, NULL, 'ASSET', '투자', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000404', NULL, NULL, NULL, 'ASSET', '대출', 40, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000111', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '급여', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000112', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '사업', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000113', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '상여', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000114', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '수당', 40, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000115', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '퇴직', 50, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000121', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '이자', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000122', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '배당', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000123', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '주식', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000124', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '크립토', 40, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000125', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '환차익', 50, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000131', NULL, 'f1000000-0000-4000-8000-000000000103', NULL, 'INCOME', '임대', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000132', NULL, 'f1000000-0000-4000-8000-000000000103', NULL, 'INCOME', '양도', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000141', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '일시수익', 10, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000142', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '세금환급', 20, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000143', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '보험금', 30, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000144', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '정부지원금', 40, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000145', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '경조사비', 50, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);
INSERT INTO finance.finance_categories (id, group_id, parent_id, user_id, type, name, sort_order, created_at, updated_at, deleted_at) VALUES ('f1000000-0000-4000-8000-000000000146', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '캐시백', 60, '2026-09-21 06:18:41.929032+00', '2026-09-21 06:18:41.929032+00', NULL);

-- admin_runtime_settings: 런타임 설정 기본값
INSERT INTO public.admin_runtime_settings (setting_key, setting_value, created_at, updated_at) VALUES ('runtime', '{"brokers": {"KIS": {"enabled": true}, "TOSS": {"enabled": true}}, "strategies": {"VR": {"ticker": {"customizable": false, "defaultValue": "TQQQ", "allowedValues": ["TQQQ"]}, "enabled": true, "bandWidth": {"customizable": true, "defaultValue": 15, "allowedValues": [10, 15, 20]}, "divisionCount": null, "intervalWeeks": {"customizable": true, "defaultValue": 2, "allowedValues": [1, 2, 4]}, "recurringMode": {"customizable": true, "defaultValue": "HOLD", "allowedValues": ["DEPOSIT", "HOLD", "WITHDRAW"]}}, "PRIVACY": {"ticker": {"customizable": false, "defaultValue": "SOXL", "allowedValues": ["SOXL"]}, "enabled": true, "bandWidth": null, "divisionCount": null, "intervalWeeks": null, "recurringMode": null}, "INFINITE": {"ticker": {"customizable": true, "defaultValue": "SOXL", "allowedValues": ["MAGX", "USD", "TQQQ", "SOXL"]}, "enabled": true, "bandWidth": null, "divisionCount": {"customizable": true, "defaultValue": 20, "allowedValues": [20, 30, 40]}, "intervalWeeks": null, "recurringMode": null}}, "approvalRequired": true}', '2026-09-21 06:18:38.225668+00', '2026-09-21 06:18:38.225668+00');
