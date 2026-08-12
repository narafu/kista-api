-- V12: 개인 자산 관리(자산 기록 + 월별 기록 점검) 테이블 추가
-- Column order: pk -> fk -> business columns -> created_at -> updated_at -> deleted_at

CREATE TABLE assets (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL,
    entry_date   DATE         NOT NULL,
    category     VARCHAR(20)  NOT NULL,       -- AssetCategory enum name (INVESTMENT/SAVINGS/LOAN/REAL_ESTATE)
    subcategory  VARCHAR(100) NOT NULL,       -- 자유 입력
    institution  VARCHAR(100),                -- 자유 입력, 선택
    asset_class  VARCHAR(50)  NOT NULL,       -- 자유 입력
    strategy     VARCHAR(50),                 -- 자유 입력, 선택 — 실제 자동매매 전략과 무관
    amount       BIGINT       NOT NULL,       -- 원화 정수, 0 이상
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT assets_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_assets_user_id_entry_date ON assets(user_id, entry_date DESC);

CREATE TABLE asset_monthly_checks (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL,
    month        VARCHAR(7)   NOT NULL,       -- 'YYYY-MM'
    completed    BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT asset_monthly_checks_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_asset_monthly_checks_user_month UNIQUE (user_id, month)
);
