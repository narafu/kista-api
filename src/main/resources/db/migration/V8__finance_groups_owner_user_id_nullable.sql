-- 소유자는 finance_group_members.role='OWNER'만 읽고 finance_groups.owner_user_id는 읽는 곳이 없다.
-- expand 단계: NOT NULL을 풀어 코드가 이 컬럼에 쓰기를 멈추게 한다. 컬럼 drop은 다음 배포(contract)에서 한다.
ALTER TABLE finance.finance_groups ALTER COLUMN owner_user_id DROP NOT NULL;
