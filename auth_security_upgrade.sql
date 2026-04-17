BEGIN;

CREATE TABLE IF NOT EXISTS user_security_state (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    auth_version BIGINT NOT NULL DEFAULT 1,
    credential_version BIGINT NOT NULL DEFAULT 1,
    force_logout_after TIMESTAMP NULL,
    last_password_change_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user_security_state (user_id, auth_version, credential_version, created_at, updated_at)
SELECT id, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users
WHERE NOT EXISTS (
    SELECT 1
    FROM user_security_state s
    WHERE s.user_id = users.id
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_scope VARCHAR(16) NOT NULL DEFAULT 'tab',
    client_instance_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    idle_expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    revoke_reason VARCHAR(64) NULL,
    last_ip VARCHAR(64),
    user_agent VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_status
    ON auth_sessions(user_id, status);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_client
    ON auth_sessions(client_instance_id);

CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    parent_id VARCHAR(36) NULL REFERENCES auth_refresh_tokens(id),
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    reuse_detected BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_session
    ON auth_refresh_tokens(session_id);

CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_active
    ON auth_refresh_tokens(session_id, revoked_at, expires_at);

COMMIT;
