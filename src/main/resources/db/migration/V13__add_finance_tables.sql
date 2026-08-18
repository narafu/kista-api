-- V13: 재무 관리(그룹/카테고리/계좌/예산/거래) 테이블 추가
-- 소유 축은 group_id 단일 — 모든 사용자는 가입 시 1인 개인 그룹을 갖는다.
-- 입력자는 created_by로 별도 기록 (개인/그룹 탭 = created_by 필터 유무).
-- Column order: pk -> fk -> business columns -> created_at -> updated_at -> deleted_at

-- 예산 기간 중첩 원천 차단용 (uuid 등치 + daterange를 한 EXCLUDE에 묶으려면 필수)
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ─────────────────────────────────────────────────────────────────────────────
-- 그룹 (가계 단위)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_groups (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID         NOT NULL,     -- FK → users.id, 그룹 생성자(개인 그룹은 본인)
    name          VARCHAR(50)  NOT NULL,     -- 표시명, 개인 그룹 기본값 '개인'
    personal      BOOLEAN      NOT NULL DEFAULT true, -- true면 가입 시 자동 생성된 1인 그룹 (삭제·탈퇴 불가)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT finance_groups_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES users(id)
);

-- 사용자당 개인 그룹 정확히 1개 (부트스트랩 재실행·동시 가입 경쟁 차단)
CREATE UNIQUE INDEX uq_finance_groups_personal_owner
    ON finance_groups(owner_user_id) WHERE personal AND deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 그룹 멤버십
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_group_members (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id   UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'MEMBER', -- FinanceGroup.MemberRole (OWNER/MEMBER)
    joined_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,                  -- 그룹 탈퇴 시 소프트 삭제 (원본 DDL is_active 대체)
    CONSTRAINT finance_group_members_group_id_fkey FOREIGN KEY (group_id) REFERENCES finance_groups(id),
    CONSTRAINT finance_group_members_user_id_fkey  FOREIGN KEY (user_id)  REFERENCES users(id)
);

CREATE UNIQUE INDEX uq_finance_group_members_group_user
    ON finance_group_members(group_id, user_id) WHERE deleted_at IS NULL;
-- "내가 속한 그룹 목록" 조회 경로 (모든 재무 API의 소유권 검증 진입점)
CREATE INDEX idx_finance_group_members_user_id
    ON finance_group_members(user_id) WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 그룹 초대
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_group_invitations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id        UUID         NOT NULL,
    invited_by      UUID         NOT NULL,   -- FK → users.id, 초대한 사람
    invitee_user_id UUID,                    -- FK → users.id, 코드 수락 전에는 NULL
    code            VARCHAR(16)  NOT NULL,   -- 공유용 초대 코드 (URL-safe)
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING/ACCEPTED/DECLINED/EXPIRED
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT finance_group_invitations_group_id_fkey        FOREIGN KEY (group_id)        REFERENCES finance_groups(id),
    CONSTRAINT finance_group_invitations_invited_by_fkey      FOREIGN KEY (invited_by)      REFERENCES users(id),
    CONSTRAINT finance_group_invitations_invitee_user_id_fkey FOREIGN KEY (invitee_user_id) REFERENCES users(id),
    CONSTRAINT uq_finance_group_invitations_code UNIQUE (code)
);

CREATE INDEX idx_finance_group_invitations_group_id ON finance_group_invitations(group_id) WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 카테고리 — group_id NULL = 시스템 제공 전역 카테고리(읽기 전용, 전 사용자 공유)
-- 대분류(수입/소비/저축/자산)는 별도 행이 아니라 type 컬럼이 SSOT — 최대 2계층(L1 → L2)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id   UUID,                      -- FK → finance_groups.id, NULL이면 시스템 전역 카테고리
    parent_id  UUID,                      -- FK → 자기참조, L1이면 NULL
    created_by UUID,                      -- FK → users.id, 시스템 카테고리는 NULL
    type       VARCHAR(20)  NOT NULL,     -- FinanceCategory.Type (INCOME/EXPENSE/SAVING/ASSET), 생성 후 불변
    name       VARCHAR(50)  NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0, -- 표시 순서 (한글 사전순이 업무 순서와 무관해 필요)
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,               -- null이면 활성 (원본 DDL is_active 대체)
    CONSTRAINT finance_categories_group_id_fkey   FOREIGN KEY (group_id)   REFERENCES finance_groups(id),
    -- 복합 FK (parent_id, group_id)는 쓰지 않는다: 그룹 카테고리가 시스템 부모(group_id IS NULL)를
    -- 참조하는 정상 케이스를 구조적으로 금지하기 때문. 부모 규칙은 FinanceCategoryService.resolveParent가 강제한다.
    CONSTRAINT finance_categories_parent_id_fkey  FOREIGN KEY (parent_id)  REFERENCES finance_categories(id),
    CONSTRAINT finance_categories_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    -- 시스템 카테고리는 그룹·작성자가 둘 다 없고, 그룹 카테고리는 둘 다 있다
    CONSTRAINT finance_categories_ownership_check
        CHECK ((group_id IS NULL AND created_by IS NULL) OR (group_id IS NOT NULL AND created_by IS NOT NULL))
);

CREATE INDEX idx_finance_categories_group_type ON finance_categories(group_id, type) WHERE deleted_at IS NULL;
CREATE INDEX idx_finance_categories_parent_id  ON finance_categories(parent_id);
-- 같은 부모 아래 같은 이름 금지 (그룹 카테고리만 — 시스템은 시드가 보장).
-- parent_id를 COALESCE로 감싼다: PostgreSQL 유니크 인덱스는 NULL을 서로 다른 값으로 취급하므로
-- COALESCE 없이 (group_id, parent_id, name)만 쓰면 parent_id가 전부 NULL인 L1 카테고리끼리는
-- 이름이 같아도 인덱스가 막지 못한다(L2는 parent_id가 non-null이라 정상 동작). 고정 sentinel UUID로
-- NULL을 대체해 L1 행끼리도 동일 값으로 비교되게 한다.
CREATE UNIQUE INDEX uq_finance_categories_group_parent_name
    ON finance_categories(group_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), name)
    WHERE group_id IS NOT NULL AND deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 시스템 전역 카테고리 시드 (고정 UUID — V14 데이터 변환·Java 상수·테스트가 모두 이 값을 참조한다)
-- 규칙: L1 전부 + 수입의 L2 전부 = 시스템(읽기 전용). 그 아래는 전부 그룹 소유.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO finance_categories (id, group_id, parent_id, created_by, type, name, sort_order) VALUES
-- 수입 L1
('f1000000-0000-4000-8000-000000000101', NULL, NULL, NULL, 'INCOME',  '근로소득',   10),
('f1000000-0000-4000-8000-000000000102', NULL, NULL, NULL, 'INCOME',  '금융소득',   20),
('f1000000-0000-4000-8000-000000000103', NULL, NULL, NULL, 'INCOME',  '부동산소득', 30),
('f1000000-0000-4000-8000-000000000104', NULL, NULL, NULL, 'INCOME',  '기타소득',   40),
-- 수입 L2 (업무 정의가 확정적이라 시스템 제공)
('f1000000-0000-4000-8000-000000000111', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '급여',       10),
('f1000000-0000-4000-8000-000000000112', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '사업',       20),
('f1000000-0000-4000-8000-000000000113', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '상여',       30),
('f1000000-0000-4000-8000-000000000114', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '수당',       40),
('f1000000-0000-4000-8000-000000000115', NULL, 'f1000000-0000-4000-8000-000000000101', NULL, 'INCOME', '퇴직',       50),
('f1000000-0000-4000-8000-000000000121', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '이자',       10),
('f1000000-0000-4000-8000-000000000122', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '배당',       20),
('f1000000-0000-4000-8000-000000000123', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '주식',       30),
('f1000000-0000-4000-8000-000000000124', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '크립토',     40),
('f1000000-0000-4000-8000-000000000125', NULL, 'f1000000-0000-4000-8000-000000000102', NULL, 'INCOME', '환차익',     50),
('f1000000-0000-4000-8000-000000000131', NULL, 'f1000000-0000-4000-8000-000000000103', NULL, 'INCOME', '임대',       10),
('f1000000-0000-4000-8000-000000000132', NULL, 'f1000000-0000-4000-8000-000000000103', NULL, 'INCOME', '양도',       20),
('f1000000-0000-4000-8000-000000000141', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '일시수익',   10),
('f1000000-0000-4000-8000-000000000142', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '세금환급',   20),
('f1000000-0000-4000-8000-000000000143', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '보험금',     30),
('f1000000-0000-4000-8000-000000000144', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '정부지원금', 40),
('f1000000-0000-4000-8000-000000000145', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '경조사비',   50),
('f1000000-0000-4000-8000-000000000146', NULL, 'f1000000-0000-4000-8000-000000000104', NULL, 'INCOME', '캐시백',     60),
-- 소비 L1 (L2는 사용자가 직접 생성)
('f1000000-0000-4000-8000-000000000201', NULL, NULL, NULL, 'EXPENSE', '주거비',   10),
('f1000000-0000-4000-8000-000000000202', NULL, NULL, NULL, 'EXPENSE', '생활비',   20),
('f1000000-0000-4000-8000-000000000203', NULL, NULL, NULL, 'EXPENSE', '용돈',     30),
('f1000000-0000-4000-8000-000000000204', NULL, NULL, NULL, 'EXPENSE', '대출이자', 40),
-- 저축 L1
('f1000000-0000-4000-8000-000000000301', NULL, NULL, NULL, 'SAVING',  'DCA',              10),
('f1000000-0000-4000-8000-000000000302', NULL, NULL, NULL, 'SAVING',  '주택청약종합저축', 20),
('f1000000-0000-4000-8000-000000000303', NULL, NULL, NULL, 'SAVING',  '연금저축보험',     30),
-- 자산 L1 — 대출(...0404)이 부채 판별자. 구 AssetCategory.LOAN의 유일한 후계값이다.
('f1000000-0000-4000-8000-000000000401', NULL, NULL, NULL, 'ASSET',   '예적금',   10),
('f1000000-0000-4000-8000-000000000402', NULL, NULL, NULL, 'ASSET',   '부동산',   20),
('f1000000-0000-4000-8000-000000000403', NULL, NULL, NULL, 'ASSET',   '투자',     30),
('f1000000-0000-4000-8000-000000000404', NULL, NULL, NULL, 'ASSET',   '대출',     40);

-- ─────────────────────────────────────────────────────────────────────────────
-- 계좌 (설정 메뉴) — account_no는 어댑터 경계에서 AES-256 암호화되어 저장된다
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_accounts (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id     UUID         NOT NULL,
    created_by   UUID         NOT NULL,
    account_type VARCHAR(20)  NOT NULL,   -- FinanceAccount.Type (SECURITIES/BANK/INSURANCE/EXCHANGE)
    name         VARCHAR(50)  NOT NULL,   -- 계좌명 (예: 토스증권 일반계좌)
    -- 계좌번호, 선택. AES-256 CBC + Base64 인코딩 시 평문 길이의 ~1.4배로 늘어난다(레포 관례상 암호화 저장
    -- 컬럼은 VARCHAR(512) 이상 — docs/agents/constraints.md) — accounts.account_no와 동일한 폭으로 맞춘다.
    account_no   VARCHAR(512),
    memo         VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT finance_accounts_group_id_fkey   FOREIGN KEY (group_id)   REFERENCES finance_groups(id),
    CONSTRAINT finance_accounts_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE UNIQUE INDEX uq_finance_accounts_group_name ON finance_accounts(group_id, name) WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 예산 — apply_end_date NULL이면 무기한. 기간 중첩은 EXCLUDE 제약이 원천 차단한다.
-- (구 초안의 amount=0 종료 센티널은 폐기 — 종료는 apply_end_date로 표현)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_budgets (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id         UUID        NOT NULL,
    category_id      UUID        NOT NULL,
    created_by       UUID        NOT NULL,
    apply_start_date DATE        NOT NULL,
    apply_end_date   DATE,                 -- NULL = 무기한
    amount           BIGINT      NOT NULL, -- 월 할당 예산, 원화 정수
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT finance_budgets_group_id_fkey    FOREIGN KEY (group_id)    REFERENCES finance_groups(id),
    CONSTRAINT finance_budgets_category_id_fkey FOREIGN KEY (category_id) REFERENCES finance_categories(id),
    CONSTRAINT finance_budgets_created_by_fkey  FOREIGN KEY (created_by)  REFERENCES users(id),
    -- 0을 막아 "amount=0이 종료를 의미하던" 구 규약이 되살아날 여지를 제거
    CONSTRAINT finance_budgets_amount_check     CHECK (amount > 0),
    CONSTRAINT finance_budgets_period_check     CHECK (apply_end_date IS NULL OR apply_end_date >= apply_start_date),
    -- 같은 그룹·같은 카테고리의 적용 기간이 하루라도 겹치면 INSERT/UPDATE가 거부된다.
    -- daterange의 '[]'는 종료일 포함, 상한 NULL은 무한대로 해석되므로 무기한 예산도 동일 규칙으로 검사된다.
    CONSTRAINT finance_budgets_no_overlap EXCLUDE USING gist (
        group_id WITH =, category_id WITH =,
        daterange(apply_start_date, apply_end_date, '[]') WITH &&
    )
);

-- EXCLUDE 제약이 (group_id, category_id, range) GiST 인덱스를 만들지만 "특정 날짜에 유효한 예산" 조회는
-- 등치 선행 컬럼이 있는 btree가 더 낫다
CREATE INDEX idx_finance_budgets_group_category_start ON finance_budgets(group_id, category_id, apply_start_date DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 거래내역
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_transactions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id         UUID        NOT NULL,
    category_id      UUID        NOT NULL,
    created_by       UUID        NOT NULL,  -- 입력자 (개인/그룹 탭 필터 기준)
    transaction_date DATE        NOT NULL,
    amount           BIGINT      NOT NULL,  -- 원화 정수 절대값. 부호는 FinanceCategory.Type.sign이 SSOT
    memo             VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT finance_transactions_group_id_fkey   FOREIGN KEY (group_id)   REFERENCES finance_groups(id),
    CONSTRAINT finance_transactions_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    -- RESTRICT 의도적: 카테고리 하드 삭제 경로 자체를 만들지 않는다는 계약을 DB가 문서화한다 (§4)
    CONSTRAINT finance_transactions_category_id_fkey FOREIGN KEY (category_id) REFERENCES finance_categories(id) ON DELETE RESTRICT,
    CONSTRAINT finance_transactions_amount_check     CHECK (amount >= 0)
);

CREATE INDEX idx_finance_transactions_group_date  ON finance_transactions(group_id, transaction_date DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_finance_transactions_category_id ON finance_transactions(category_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 기존 사용자 개인 그룹 부트스트랩
-- users는 소프트 삭제되므로 deleted_at 필터를 걸지 않는다 — 탈퇴 사용자도 assets 행을 갖고 있어
-- 필터링하면 V14의 INSERT-SELECT에서 그 행들이 group_id NOT NULL에 걸려 전부 유실된다.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO finance_groups (owner_user_id, name, personal)
SELECT id, '개인', true FROM users;

INSERT INTO finance_group_members (group_id, user_id, role)
SELECT g.id, g.owner_user_id, 'OWNER' FROM finance_groups g WHERE g.personal;
