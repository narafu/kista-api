-- contract-ok: 7150d7fa8 배포 이미지부터 이 컬럼을 매핑하지 않음
-- 소유자는 finance_group_members.role='OWNER'가 정한다. FK는 컬럼과 함께 사라지지만 의도를 드러내려 먼저 명시해 지운다.
ALTER TABLE finance.finance_groups DROP CONSTRAINT finance_groups_owner_user_id_fkey;
ALTER TABLE finance.finance_groups DROP COLUMN owner_user_id;
