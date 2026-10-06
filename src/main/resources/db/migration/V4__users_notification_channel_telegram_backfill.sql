-- 텔레그램 봇을 연결했지만 알림 채널에 텔레그램이 없는 사용자 보정 — 매매 알림이 채널을 따르게 되면서(trading V5)
-- 채널을 고르지 않고 봇만 연결해 텔레그램 매매 리포트를 받아 오던 사용자의 수신이 끊기지 않도록 NONE→TELEGRAM, FCM→ALL
-- 데이터 보정만(스키마 변경 없음)이라 구 이미지와 호환
UPDATE public.users
SET notification_channel = CASE notification_channel WHEN 'NONE' THEN 'TELEGRAM' WHEN 'FCM' THEN 'ALL' END
WHERE telegram_bot_token IS NOT NULL
  AND telegram_chat_id IS NOT NULL
  AND notification_channel IN ('NONE', 'FCM');
