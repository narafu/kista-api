-- V13: 가계부(카테고리/예산/거래내역) 테이블 추가 — assets 테이블과 병존, 상호 참조 없음
-- Column order: pk -> fk -> business columns -> created_at -> updated_at -> deleted_at

CREATE TABLE ledger_categories (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,     -- FK → users.id, 전역 카테고리 없음(전부 사용자별 생성)
    parent_id   UUID,                      -- FK → ledger_categories.id 자기참조, 최상위면 NULL
    type        VARCHAR(20)  NOT NULL,     -- Category.Type enum name (INCOME/EXPENSE/SAVING), 생성 후 불변
    name        VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,               -- null이면 활성 (원본 DDL is_active 대체)
    CONSTRAINT ledger_categories_user_id_fkey   FOREIGN KEY (user_id)   REFERENCES users(id)             ON DELETE CASCADE,
    -- parent_id는 (id, user_id) 복합 FK로 걸어 부모가 반드시 같은 user_id 소유여야 하도록 DB가 강제한다
    -- (parent_id NULL인 최상위 행은 MATCH SIMPLE 기본값에 따라 이 제약 자체가 검사되지 않는다)
    CONSTRAINT ledger_categories_parent_id_fkey FOREIGN KEY (parent_id, user_id) REFERENCES ledger_categories(id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_ledger_categories_id_user_id UNIQUE (id, user_id) -- 위 복합 FK의 참조 대상
);

CREATE INDEX idx_ledger_categories_user_id_type ON ledger_categories(user_id, type) WHERE deleted_at IS NULL;
CREATE INDEX idx_ledger_categories_parent_id ON ledger_categories(parent_id);

CREATE TABLE ledger_budgets (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL,
    category_id      UUID        NOT NULL,
    apply_start_date DATE        NOT NULL,  -- 이 날짜부터 다음 행 시작일 전까지 유효 (기간 중첩 구조적 불가)
    amount           BIGINT      NOT NULL,  -- 월 할당 예산, 원화 정수. 0이면 예산 종료를 의미
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_budgets_user_id_fkey     FOREIGN KEY (user_id)     REFERENCES users(id)             ON DELETE CASCADE,
    CONSTRAINT ledger_budgets_category_id_fkey FOREIGN KEY (category_id) REFERENCES ledger_categories(id) ON DELETE CASCADE,
    CONSTRAINT ledger_budgets_amount_check     CHECK (amount >= 0),
    CONSTRAINT uq_ledger_budgets_user_category_start UNIQUE (user_id, category_id, apply_start_date)
);
-- UNIQUE 제약이 (user_id, category_id, apply_start_date) 인덱스를 만들고 PG가 역방향 스캔 가능하므로
-- "가장 최근 적용 예산" 조회용 별도 인덱스는 불필요

CREATE TABLE ledger_transaction_records (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL,
    category_id      UUID        NOT NULL,
    amount           BIGINT      NOT NULL,  -- 원화 정수 절대값. 부호는 Category.Type.sign이 SSOT
    transaction_date DATE        NOT NULL,
    memo             VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_transaction_records_user_id_fkey     FOREIGN KEY (user_id)     REFERENCES users(id) ON DELETE CASCADE,
    -- RESTRICT 의도적: 카테고리 하드 삭제가 금융 기록을 조용히 지우지 않게 한다 (budgets는 파생 설정이라 CASCADE)
    CONSTRAINT ledger_transaction_records_category_id_fkey FOREIGN KEY (category_id) REFERENCES ledger_categories(id) ON DELETE RESTRICT,
    CONSTRAINT ledger_transaction_records_amount_check     CHECK (amount >= 0)
);

CREATE INDEX idx_ledger_transaction_records_user_date ON ledger_transaction_records(user_id, transaction_date DESC);
CREATE INDEX idx_ledger_transaction_records_category_id ON ledger_transaction_records(category_id);
