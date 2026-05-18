-- AES-256 CBC + Base64 인코딩 시 출력 ~260자 → VARCHAR(255) 초과 방지
-- constraints.md: 암호화 저장 컬럼은 반드시 VARCHAR(512) 이상
ALTER TABLE users ALTER COLUMN telegram_bot_token TYPE VARCHAR(512);
