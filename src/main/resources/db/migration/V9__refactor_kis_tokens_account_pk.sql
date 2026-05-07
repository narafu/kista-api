-- V1 단일 행(id=1) 제거 후 account_id UUID PK로 전환
DELETE FROM kis_tokens;
ALTER TABLE kis_tokens DROP CONSTRAINT kis_tokens_pkey;
ALTER TABLE kis_tokens DROP COLUMN id;
ALTER TABLE kis_tokens ALTER COLUMN account_id SET NOT NULL;
ALTER TABLE kis_tokens ADD PRIMARY KEY (account_id);
