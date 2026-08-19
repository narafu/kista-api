-- 같은 그룹 안에서 같은 이름의 계좌를 여러 개 등록할 수 있도록 허용 (예: "국민은행" 계좌 2개)
DROP INDEX finance.uq_finance_accounts_group_name;
