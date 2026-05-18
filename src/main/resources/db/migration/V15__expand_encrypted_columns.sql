-- AES-256 암호화 후 Base64 인코딩된 값이 VARCHAR(255) 초과 → 512로 확장
ALTER TABLE accounts ALTER COLUMN account_no TYPE VARCHAR(512);
ALTER TABLE accounts ALTER COLUMN kis_app_key TYPE VARCHAR(512);
ALTER TABLE accounts ALTER COLUMN kis_secret_key TYPE VARCHAR(512);
ALTER TABLE accounts ALTER COLUMN telegram_bot_token TYPE VARCHAR(512);
