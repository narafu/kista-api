-- kista.user_notify_profile에 telegram 발송에 필요한 두 컬럼 추가.
-- root(users.telegram_bot_token/telegram_chat_id, AES-256)와 동일 규격 — VARCHAR(512).
ALTER TABLE kista.user_notify_profile
    ADD COLUMN telegram_bot_token VARCHAR(512),
    ADD COLUMN chat_id VARCHAR(64);

-- 기존 행 백필 — 비어 있으면 trading-core가 매매 텔레그램 알림을 못 보낸다(무증상 아님, 발송 시도 시
-- TelegramHttpClient.sendMessage가 botToken null → 조용히 스킵하므로 알림이 그냥 안 나감).
UPDATE kista.user_notify_profile p
SET telegram_bot_token = u.telegram_bot_token,
    chat_id = u.telegram_chat_id
FROM public.users u
WHERE u.id = p.user_id;
