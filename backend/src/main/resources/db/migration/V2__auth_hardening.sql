-- M01: MFA (TOTP), one-time password reset, access log and session revocation.
ALTER TABLE users
  ADD COLUMN mfa_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN mfa_secret_encrypted varchar(255),
  ADD COLUMN mfa_last_step bigint,
  ADD COLUMN credentials_changed_at timestamptz NOT NULL DEFAULT now();

CREATE TABLE password_reset_tokens (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),
 token_hash char(64) NOT NULL UNIQUE, expires_at timestamptz NOT NULL, used_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_user ON password_reset_tokens(user_id, created_at);

CREATE TABLE mfa_recovery_codes (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),
 code_hash char(64) NOT NULL, used_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id, code_hash)
);

-- Access log: no passwords, tokens or raw e-mails for unknown accounts (only a keyed fingerprint).
CREATE TABLE access_events (
 id uuid PRIMARY KEY, user_id uuid REFERENCES users(id), email_fingerprint char(64),
 outcome varchar(30) NOT NULL CHECK(outcome IN('LOGIN_SUCCESS','LOGIN_FAILURE','LOCKED','MFA_REQUIRED','MFA_SUCCESS','MFA_FAILURE','LOGOUT','PASSWORD_RESET_REQUESTED','PASSWORD_RESET','MFA_ENABLED')),
 ip varchar(64), user_agent varchar(200), occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX access_events_user ON access_events(user_id, occurred_at);
CREATE TRIGGER no_access_event_mutation BEFORE UPDATE OR DELETE ON access_events FOR EACH ROW EXECUTE FUNCTION immutable_audit();
