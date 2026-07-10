-- 탈퇴(소프트 삭제) 후 같은 카카오 계정으로 재가입이 막히는 문제 수정.
-- 기존 전역 UNIQUE 제약은 deleted_at을 모르므로 소프트 삭제된 row가 kakao_id 값을 계속 점유했다.
-- accounts.account_no_hash와 동일하게 활성 row(deleted_at IS NULL)에 대해서만 유니크를 강제한다.
ALTER TABLE users DROP CONSTRAINT users_kakao_id_key;

CREATE UNIQUE INDEX uq_users_kakao_id_active
    ON users (kakao_id)
    WHERE deleted_at IS NULL;
