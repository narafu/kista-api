-- 알림 수단 복제 — 매매 알림 텔레그램 발송을 사용자의 알림 채널(NONE/TELEGRAM/FCM/ALL)에 맞춘다
-- nullable: 기존 행은 NULL = 채널 판정 없이 봇 연결 여부만 본다(종전 동작). root V4가 봇 연결 사용자의 채널을
-- 텔레그램 포함으로 보정하므로 NULL 행의 종전 동작과 결과가 같고, 다음 프로필 변경 때 실제 값으로 채워진다
ALTER TABLE trading.user_notify_profile ADD COLUMN notification_channel VARCHAR(20);
