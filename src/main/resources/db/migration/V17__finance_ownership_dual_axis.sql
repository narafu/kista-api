-- V17: finance 소유축을 group_id 단일 → (user_id 소유 + group_id 공유) 이중축으로 전환
-- 배경: 모든 finance 데이터 테이블이 group_id NOT NULL이라 저장하려면 반드시 그룹이 필요했고,
-- 이 제약이 "가입 시 1인 personal 그룹 자동 생성" 우회 구조를 낳았다(FinanceGroupSelfHealer,
-- resolveGroupId racing-safe, unmarkPersonal, leaveGroup의 reassignGroup 이관).
-- 새 모델: user_id(옛 created_by/closed_by 개명)=소유자, group_id(nullable)=공유 스코프.
--   group_id IS NULL = 개인(본인만 조회), group_id 채움 = 그룹 공유(그룹 전원 조회).
-- 되돌릴 수 없는 지점: STEP 4에서 personal 데이터의 group_id를 NULL로 미는 순간, 그 행이 어느
-- personal 그룹 출신이었는지 정보가 사라진다(소유자는 user_id로 계속 식별 가능하므로 복구 불필요).
-- 반드시 이 마이그레이션 적용 전 DB 스냅샷을 확보할 것 — Flyway는 자동 롤백하지 않는다.

SET search_path = finance, public;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 0. 사전 가드 — personal 제외 활성 멤버십이 2개 이상인 사용자가 있으면 배포 중단.
--   1인 1그룹이 새 모델의 불변식이다. 조용히 진행하면 다중 그룹 사용자가 남아
--   findCurrentGroupId(단일 그룹 가정)가 비결정적이 된다.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE bad TEXT;
BEGIN
    SELECT string_agg(DISTINCT m.user_id::text, ', ') INTO bad
    FROM finance_group_members m
    JOIN finance_groups g ON g.id = m.group_id
    WHERE m.deleted_at IS NULL AND g.deleted_at IS NULL AND g.personal = false
    GROUP BY m.user_id HAVING count(*) > 1;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '1인 1그룹 위반(공유 그룹 2개 이상 소속): % — 수동 정리 후 재배포하십시오', bad;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 1. 컬럼 개명 — created_by/closed_by를 소유자 축 user_id로 통일
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE finance_transactions    RENAME COLUMN created_by TO user_id;
ALTER TABLE finance_budgets         RENAME COLUMN created_by TO user_id;
ALTER TABLE finance_accounts        RENAME COLUMN created_by TO user_id;
ALTER TABLE finance_categories      RENAME COLUMN created_by TO user_id;
ALTER TABLE finance_asset_snapshots RENAME COLUMN created_by TO user_id;
ALTER TABLE finance_monthly_closings RENAME COLUMN closed_by TO user_id;

-- closed_by는 "마감 해제 시 NULL로 되돌리는" 액션 트래커였다(MonthlyClosingService.setCompleted).
-- user_id로 승격된 뒤로는 절대 null로 되돌리지 않는 소유자 축이라 기존 미완료(completed=false) 행의
-- NULL을 그룹 소유자로 백필한다 — 이후 서비스 레이어도 더는 null로 되돌리지 않도록 변경된다.
UPDATE finance_monthly_closings c SET user_id = g.owner_user_id
    FROM finance_groups g WHERE c.group_id = g.id AND c.user_id IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 2. group_id NOT NULL 완화 (categories는 이미 nullable) — 개인 소유 표현을 위해 필수
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE finance_transactions    ALTER COLUMN group_id DROP NOT NULL;
ALTER TABLE finance_budgets         ALTER COLUMN group_id DROP NOT NULL;
ALTER TABLE finance_accounts        ALTER COLUMN group_id DROP NOT NULL;
ALTER TABLE finance_asset_snapshots ALTER COLUMN group_id DROP NOT NULL;
ALTER TABLE finance_monthly_closings ALTER COLUMN group_id DROP NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 3. category CHECK 재정의 + 개인 카테고리 유니크 신설 (STEP 4의 개인화보다 먼저 필요)
--   구 CHECK: (group NULL AND user_id NULL) OR (둘 다 NOT NULL) — 개인(NULL,NOT NULL)을 막는다.
--   새 CHECK: user_id 없는 그룹 카테고리만 금지. 허용 3-state:
--     system(user_id NULL, group NULL) / personal(user_id 있음, group NULL) / group(둘 다 있음)
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE finance_categories DROP CONSTRAINT finance_categories_ownership_check;
ALTER TABLE finance_categories ADD CONSTRAINT finance_categories_ownership_check
    CHECK (user_id IS NOT NULL OR group_id IS NULL);

-- 개인 카테고리 유니크: (user_id, parent, name) — 기존 그룹 유니크(uq_finance_categories_group_parent_name,
-- WHERE group_id IS NOT NULL)는 그대로 두고 술어로 완전 분리.
CREATE UNIQUE INDEX uq_finance_categories_personal_parent_name
    ON finance_categories(user_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), name)
    WHERE group_id IS NULL AND user_id IS NOT NULL AND deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 4. personal 그룹에 매달린 데이터를 개인 소유로 전환 (group_id → NULL)
--   공유(personal=false) 그룹 데이터는 손대지 않는다.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE finance_categories      x SET group_id = NULL FROM finance_groups g WHERE x.group_id = g.id AND g.personal;
UPDATE finance_transactions    x SET group_id = NULL FROM finance_groups g WHERE x.group_id = g.id AND g.personal;
UPDATE finance_accounts        x SET group_id = NULL FROM finance_groups g WHERE x.group_id = g.id AND g.personal;
UPDATE finance_asset_snapshots x SET group_id = NULL FROM finance_groups g WHERE x.group_id = g.id AND g.personal;
UPDATE finance_budgets         x SET group_id = NULL FROM finance_groups g WHERE x.group_id = g.id AND g.personal;
UPDATE finance_monthly_closings x SET group_id = NULL FROM finance_groups g WHERE x.group_id = g.id AND g.personal;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 5. budget 겹침 방지 재설계 (STEP 4 이후 실행 — 개인화된 행이 새 제약으로 검증됨)
--   구 EXCLUDE는 group_id 등치 기준이라 group_id가 둘 다 NULL인 개인 예산끼리는 GiST에서
--   NULL이 "같지 않음"으로 취급돼 겹침을 못 막는다. 개인=user_id 축, 그룹=group_id 축으로 분리.
--   COALESCE 센티널은 부적합(공유 센티널이 서로 다른 개인 사용자를 충돌시킴) — 부분 EXCLUDE 2개가 정답.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE finance_budgets DROP CONSTRAINT finance_budgets_no_overlap;
ALTER TABLE finance_budgets ADD CONSTRAINT finance_budgets_group_no_overlap
    EXCLUDE USING gist (group_id WITH =, category_id WITH =,
                         daterange(apply_start_date, apply_end_date, '[]') WITH &&)
    WHERE (group_id IS NOT NULL);
ALTER TABLE finance_budgets ADD CONSTRAINT finance_budgets_personal_no_overlap
    EXCLUDE USING gist (user_id WITH =, category_id WITH =,
                         daterange(apply_start_date, apply_end_date, '[]') WITH &&)
    WHERE (group_id IS NULL);

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 6. monthly_closings 유니크 재정의 — 그룹/개인 분리
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE finance_monthly_closings DROP CONSTRAINT uq_finance_monthly_closings_group_month;
CREATE UNIQUE INDEX uq_finance_monthly_closings_group_month
    ON finance_monthly_closings(group_id, month) WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_finance_monthly_closings_personal_month
    ON finance_monthly_closings(user_id, month) WHERE group_id IS NULL AND user_id IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 7. personal 그룹/멤버십 정리 — 소프트 삭제(하드 삭제 아님, FK 안전·롤백 내성).
--   members 먼저(그룹 FK 참조 유지한 채), groups 나중.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE finance_group_members m SET deleted_at = now()
    FROM finance_groups g WHERE m.group_id = g.id AND g.personal AND m.deleted_at IS NULL;
UPDATE finance_groups SET deleted_at = now() WHERE personal AND deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 7.5. 1인 1그룹 불변식을 DB 레벨로 강제 — 이 시점엔 STEP 7이 personal 멤버십을 모두 정리해
--   사용자당 활성 멤버십이 최대 1개(공유 그룹)만 남아 있어 안전하게 걸 수 있다.
--   respondToInvitation의 앱 레벨 findCurrentGroupId 사전검증은 TOCTOU 레이스(동시 두 초대 수락)를
--   못 막는다 — 이 유니크 인덱스가 그 레이스의 최종 방어선이다. addMember의 ON CONFLICT DO NOTHING은
--   (group_id, user_id) 인덱스만 대상으로 하므로 이 인덱스 위반은 그대로 예외로 올라와
--   FinanceGroupPersistenceAdapter가 이를 감지해 도메인 예외로 변환한다.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_finance_group_members_one_active_group
    ON finance_group_members(user_id) WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 8. personal 개념 폐기 — 모든 의존 연산이 끝난 뒤 마지막에.
-- ─────────────────────────────────────────────────────────────────────────────
DROP INDEX uq_finance_groups_personal_owner;
ALTER TABLE finance_groups DROP COLUMN personal;

-- name도 함께 폐기 — rename API가 없어 모든 행이 "가계부" 고정값이라 정보량이 없다.
-- 컨트롤러 응답(FinanceGroupResponse)은 이 고정 문자열을 상수로 채워 하위 호환을 유지한다.
ALTER TABLE finance_groups DROP COLUMN name;

-- ─────────────────────────────────────────────────────────────────────────────
-- STEP 9. 고volume 개인 브랜치 인덱스 — 대시보드 "개인 ∪ 그룹" 조회에서 개인 가지는
--   기존 (group_id, ...) 인덱스를 못 탄다.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_finance_transactions_personal_date
    ON finance_transactions(user_id, transaction_date DESC) WHERE group_id IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_finance_asset_snapshots_personal_entry_date
    ON finance_asset_snapshots(user_id, entry_date DESC) WHERE group_id IS NULL AND deleted_at IS NULL;
