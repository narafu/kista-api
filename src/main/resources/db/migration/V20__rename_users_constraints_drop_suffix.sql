-- V20: V19에서 users 테이블 재생성 시 public 스키마 내 이름 충돌로 자동 부여된 _1 접미사 제거
--   users_pkey1 -> users_pkey, users_kakao_id_key1 -> users_kakao_id_key
--   (auth.users는 별도 스키마이므로 이름 충돌 없음)

ALTER TABLE users RENAME CONSTRAINT users_pkey1 TO users_pkey;
ALTER TABLE users RENAME CONSTRAINT users_kakao_id_key1 TO users_kakao_id_key;
