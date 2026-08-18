-- V14: 배포 완료된 assets / asset_monthly_checks 를 finance_* 스키마로 전환
-- 이 파일은 이번 변경에서 운영 실데이터를 건드리는 유일한 마이그레이션이다.
-- 구 테이블은 DROP하지 않고 *_v12_backup으로 남긴다 — 안정화 후 별도 마이그레이션에서 DROP할 것.

-- ─────────────────────────────────────────────────────────────────────────────
-- 0) 사전 가드: 매핑표에 없는 asset_class가 있으면 조용히 잘못 분류하지 말고 배포를 중단시킨다
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE unmapped TEXT;
BEGIN
    SELECT string_agg(DISTINCT asset_class, ', ') INTO unmapped
    FROM assets
    WHERE asset_class NOT IN ('미국주식', '기타주식', '금/은', '크립토', '원화', '달러');
    IF unmapped IS NOT NULL THEN
        RAISE EXCEPTION '매핑되지 않은 asset_class: % — V14 매핑표를 갱신한 뒤 재배포하십시오', unmapped;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 0-1) 사전 가드: subcategory/institution이 각각 finance_categories.name/finance_accounts.name의
--      VARCHAR(50) 폭을 넘으면 3)/4)의 INSERT가 "value too long for type character varying(50)"으로
--      실패한다 — 어느 값이 문제인지 알 수 없는 채로 배포가 중단되지 않도록 미리 밝혀둔다.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE too_long TEXT;
BEGIN
    SELECT string_agg(DISTINCT subcategory, ', ') INTO too_long FROM assets WHERE length(subcategory) > 50;
    IF too_long IS NOT NULL THEN
        RAISE EXCEPTION 'finance_categories.name(50자) 초과 subcategory: % — 값을 줄이거나 컬럼 폭을 넓힌 뒤 재배포하십시오', too_long;
    END IF;
    SELECT string_agg(DISTINCT institution, ', ') INTO too_long FROM assets WHERE length(institution) > 50;
    IF too_long IS NOT NULL THEN
        RAISE EXCEPTION 'finance_accounts.name(50자) 초과 institution: % — 값을 줄이거나 컬럼 폭을 넓힌 뒤 재배포하십시오', too_long;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 자산 스냅샷 테이블
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_asset_snapshots (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID         NOT NULL,
    category_id UUID         NOT NULL,  -- type='ASSET' 카테고리 (L1 또는 L2)
    account_id  UUID,                   -- 계좌 없는 자산(전세임차보증금 등)은 NULL
    created_by  UUID         NOT NULL,
    entry_date  DATE         NOT NULL,  -- 기준 날짜
    asset_class VARCHAR(20)  NOT NULL,  -- AssetClass (CASH/EQUITY/FIXED_INCOME/COMMODITY/CRYPTO/REAL_ESTATE)
    market      VARCHAR(20)  NOT NULL,  -- Market (DOMESTIC/GLOBAL)
    strategy    VARCHAR(50),            -- 자유 입력, 선택 — 실제 자동매매 전략과 무관한 개인 메모
    amount      BIGINT       NOT NULL,  -- 원화 정수, 0 이상
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT finance_asset_snapshots_group_id_fkey    FOREIGN KEY (group_id)    REFERENCES finance_groups(id),
    CONSTRAINT finance_asset_snapshots_category_id_fkey FOREIGN KEY (category_id) REFERENCES finance_categories(id) ON DELETE RESTRICT,
    CONSTRAINT finance_asset_snapshots_account_id_fkey  FOREIGN KEY (account_id)  REFERENCES finance_accounts(id),
    CONSTRAINT finance_asset_snapshots_created_by_fkey  FOREIGN KEY (created_by)  REFERENCES users(id),
    CONSTRAINT finance_asset_snapshots_amount_check     CHECK (amount >= 0)
);

CREATE INDEX idx_finance_asset_snapshots_group_entry_date ON finance_asset_snapshots(group_id, entry_date DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_finance_asset_snapshots_category_id      ON finance_asset_snapshots(category_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) 월별 마감 — asset_monthly_checks를 대체하며 자산뿐 아니라 재무 전 영역(수입/소비/저축/자산)을 덮는다
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE finance_monthly_closings (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id   UUID         NOT NULL,
    closed_by  UUID,                     -- FK → users.id, 마감 해제 상태면 NULL
    month      VARCHAR(7)   NOT NULL,    -- 'YYYY-MM'
    completed  BOOLEAN      NOT NULL DEFAULT false,
    closed_at  TIMESTAMPTZ,              -- completed=true로 전환된 시각
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT finance_monthly_closings_group_id_fkey  FOREIGN KEY (group_id)  REFERENCES finance_groups(id),
    CONSTRAINT finance_monthly_closings_closed_by_fkey FOREIGN KEY (closed_by) REFERENCES users(id),
    CONSTRAINT uq_finance_monthly_closings_group_month UNIQUE (group_id, month)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) 구 (category, subcategory) 조합 → 그룹 소유 L2 카테고리 생성
--    구 SAVINGS(주택청약종합저축/연금저축보험)는 '저축(SAVING)' 월 흐름이 아니라 '자산/예적금' 누적 잔액이므로
--    ASSET/예적금 아래로 보낸다 — 사용자 스펙에서 이름이 겹치는 부분의 해소(FLAG-2)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO finance_categories (group_id, parent_id, created_by, type, name, sort_order)
SELECT DISTINCT
       g.id,
       CASE a.category
           WHEN 'INVESTMENT'  THEN 'f1000000-0000-4000-8000-000000000403'::uuid  -- 투자
           WHEN 'SAVINGS'     THEN 'f1000000-0000-4000-8000-000000000401'::uuid  -- 예적금
           WHEN 'LOAN'        THEN 'f1000000-0000-4000-8000-000000000404'::uuid  -- 대출
           WHEN 'REAL_ESTATE' THEN 'f1000000-0000-4000-8000-000000000402'::uuid  -- 부동산
       END,
       a.user_id, 'ASSET', a.subcategory, 0
FROM assets a
JOIN finance_groups g ON g.owner_user_id = a.user_id AND g.personal;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) institution 값 → finance_accounts 행 생성 (계좌타입은 이름 패턴으로 추정, 나머지는 SECURITIES)
--    Phase 0 운영 데이터 실측: '토스뱅크'는 '은행'을 포함하지 않아 '%은행%' 단독 패턴으로는 BANK로
--    분류되지 않고 SECURITIES로 오분류된다 — '%뱅크%' 패턴을 추가해 잡는다.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO finance_accounts (group_id, created_by, account_type, name)
SELECT DISTINCT g.id, a.user_id,
       CASE
           WHEN a.institution LIKE '%은행%' OR a.institution LIKE '%뱅크%' THEN 'BANK'
           WHEN a.institution IN ('업비트', '빗썸', 'edgeX')               THEN 'EXCHANGE'
           WHEN a.institution IN ('교보생명', '라이프플래닛')               THEN 'INSURANCE'
           ELSE 'SECURITIES'
       END,
       a.institution
FROM assets a
JOIN finance_groups g ON g.owner_user_id = a.user_id AND g.personal
WHERE a.institution IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5) assets → finance_asset_snapshots (소프트 삭제 행 포함 — deleted_at 그대로 이월)
--    Phase 0 운영 데이터 실측: 같은 subcategory 문자열('마이너스통장')이 한 사용자 안에서 서로 다른
--    category(LOAN·SAVINGS) 아래 동시에 존재한다. 3)이 이 조합마다 별도 카테고리 행을 만들므로,
--    이름만으로 조인하면 fan-out되어 7)의 행 수 가드가 dst > src로 배포를 중단시킨다.
--    parent_id를 조인 조건에 포함해 3)과 동일한 category→parent 매핑으로 정확히 1행만 매칭시킨다.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO finance_asset_snapshots
    (id, group_id, category_id, account_id, created_by, entry_date, asset_class, market, strategy, amount,
     created_at, updated_at, deleted_at)
SELECT a.id, g.id, c.id, acc.id, a.user_id, a.entry_date,
       CASE
           WHEN a.subcategory = '전세임차보증금' THEN 'REAL_ESTATE'
           WHEN a.asset_class IN ('미국주식', '기타주식') THEN 'EQUITY'
           WHEN a.asset_class = '금/은'                 THEN 'COMMODITY'
           WHEN a.asset_class = '크립토'                THEN 'CRYPTO'
           ELSE 'CASH'                                                 -- 원화/달러
       END,
       CASE
           WHEN a.subcategory = '전세임차보증금' THEN 'DOMESTIC'
           WHEN a.asset_class IN ('기타주식', '원화')    THEN 'DOMESTIC'
           ELSE 'GLOBAL'                                               -- 미국주식/금·은/크립토/달러
       END,
       a.strategy, a.amount, a.created_at, a.updated_at, a.deleted_at
FROM assets a
JOIN finance_groups g         ON g.owner_user_id = a.user_id AND g.personal
JOIN finance_categories c     ON c.group_id = g.id AND c.type = 'ASSET' AND c.name = a.subcategory
                             AND c.parent_id = CASE a.category
                                 WHEN 'INVESTMENT'  THEN 'f1000000-0000-4000-8000-000000000403'::uuid
                                 WHEN 'SAVINGS'     THEN 'f1000000-0000-4000-8000-000000000401'::uuid
                                 WHEN 'LOAN'        THEN 'f1000000-0000-4000-8000-000000000404'::uuid
                                 WHEN 'REAL_ESTATE' THEN 'f1000000-0000-4000-8000-000000000402'::uuid
                             END
LEFT JOIN finance_accounts acc ON acc.group_id = g.id AND acc.name = a.institution;

-- ─────────────────────────────────────────────────────────────────────────────
-- 6) asset_monthly_checks → finance_monthly_closings
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO finance_monthly_closings (id, group_id, closed_by, month, completed, closed_at, created_at, updated_at)
SELECT m.id, g.id,
       CASE WHEN m.completed THEN m.user_id END,
       m.month, m.completed,
       CASE WHEN m.completed THEN m.updated_at END,
       m.created_at, m.updated_at
FROM asset_monthly_checks m
JOIN finance_groups g ON g.owner_user_id = m.user_id AND g.personal;

-- ─────────────────────────────────────────────────────────────────────────────
-- 7) 사후 가드: 변환 누락 0건 확인 (JOIN 실패로 조용히 사라진 행 검출)
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE src BIGINT; dst BIGINT;
BEGIN
    SELECT count(*) INTO src FROM assets;
    SELECT count(*) INTO dst FROM finance_asset_snapshots;
    IF src <> dst THEN
        RAISE EXCEPTION 'assets 변환 누락: 원본 %건, 변환 %건', src, dst;
    END IF;
    SELECT count(*) INTO src FROM asset_monthly_checks;
    SELECT count(*) INTO dst FROM finance_monthly_closings;
    IF src <> dst THEN
        RAISE EXCEPTION 'asset_monthly_checks 변환 누락: 원본 %건, 변환 %건', src, dst;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 8) 구 테이블 보존 리네임 — 엔티티가 사라지므로 ddl-auto:validate 대상에서 빠진다.
--    운영 안정화(1~2개월) 후 별도 마이그레이션에서 DROP할 것.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE assets               RENAME TO assets_v12_backup;
ALTER TABLE asset_monthly_checks RENAME TO asset_monthly_checks_v12_backup;
