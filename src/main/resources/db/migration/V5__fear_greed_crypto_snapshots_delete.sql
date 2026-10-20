-- 크립토 공포탐욕지수는 조회 시 alternative.me 이력 API를 직접 호출하도록 바뀌어 DB 스냅샷을 더 읽지 않는다 — CNN 행만 남긴다
-- 데이터 삭제만(스키마 변경 없음)이라 구 이미지와 호환: 2-role 스큐 중 구 kista-scheduler가 CRYPTO 행을 다시 쓰더라도 신코드는 읽지 않아 무해
DELETE FROM kista_ref.fear_greed_snapshots WHERE source = 'CRYPTO';
