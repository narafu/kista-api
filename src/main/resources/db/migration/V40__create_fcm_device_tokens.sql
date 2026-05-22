-- fcm_device_tokens: 사용자당 FCM 디바이스 토큰 (다중 디바이스 지원)
CREATE TABLE fcm_device_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    platform    VARCHAR(10) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_fcm_device_tokens_user_id ON fcm_device_tokens(user_id);
