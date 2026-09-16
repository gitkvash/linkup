CREATE TABLE device_tokens (
    user_id UUID NOT NULL REFERENCES users(user_id),
    fcm_token VARCHAR(255) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, fcm_token)
);

CREATE INDEX idx_device_tokens_user ON device_tokens(user_id);
