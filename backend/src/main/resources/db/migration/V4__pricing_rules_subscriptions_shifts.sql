-- M09: time-band rules per tariff plan, branch holidays, immutable tariff snapshot per session.
CREATE TABLE tariff_rules (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, plan_id uuid NOT NULL,
 rule_type varchar(16) NOT NULL CHECK (rule_type IN ('NIGHT','WEEKEND','HOLIDAY')),
 hourly_rate numeric(14,2) NOT NULL CHECK (hourly_rate >= 0),
 starts_local time, ends_local time,
 priority int NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100),
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (rule_type <> 'NIGHT' OR (starts_local IS NOT NULL AND ends_local IS NOT NULL AND starts_local <> ends_local)),
 FOREIGN KEY (organization_id, branch_id, plan_id) REFERENCES tariff_plans(organization_id, branch_id, id)
);
CREATE UNIQUE INDEX one_active_rule_type ON tariff_rules(plan_id, rule_type) WHERE active;

CREATE TABLE branch_holidays (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL,
 holiday_date date NOT NULL, name varchar(120) NOT NULL, active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (branch_id, holiday_date),
 FOREIGN KEY (organization_id, branch_id) REFERENCES branches(organization_id, id)
);

ALTER TABLE parking_sessions ADD COLUMN tariff_snapshot jsonb NOT NULL DEFAULT '[]'::jsonb;

-- M10: renewals chain, no overlapping live contracts per vehicle, one expiry notice per contract.
ALTER TABLE subscriptions
  ADD COLUMN renewal_of uuid,
  ADD COLUMN expiry_notified_at timestamptz,
  ADD CONSTRAINT subscriptions_renewal_fk FOREIGN KEY (organization_id, branch_id, renewal_of) REFERENCES subscriptions(organization_id, branch_id, id),
  ADD CONSTRAINT subscriptions_no_overlap EXCLUDE USING gist (vehicle_id WITH =, branch_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
    WHERE (status IN ('PENDING','ACTIVE','SUSPENDED'));
CREATE UNIQUE INDEX one_renewal_per_subscription ON subscriptions(renewal_of) WHERE renewal_of IS NOT NULL;
CREATE INDEX subscriptions_expiring ON subscriptions(status, ends_at) WHERE expiry_notified_at IS NULL;

-- M13: cancelled shifts free the operator's time slot; incidents ("novedades") per shift.
ALTER TABLE operator_shifts DROP CONSTRAINT operator_shifts_status_check;
ALTER TABLE operator_shifts ADD CONSTRAINT operator_shifts_status_check CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','CANCELLED'));
ALTER TABLE operator_shifts DROP CONSTRAINT operator_shifts_operator_id_tstzrange_excl;
ALTER TABLE operator_shifts ADD CONSTRAINT operator_shifts_no_overlap
  EXCLUDE USING gist (operator_id WITH =, tstzrange(scheduled_start, scheduled_end, '[)') WITH &&) WHERE (status <> 'CANCELLED');
ALTER TABLE operator_shifts ADD CONSTRAINT operator_shifts_scope UNIQUE (organization_id, branch_id, id);

CREATE TABLE shift_incidents (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, shift_id uuid NOT NULL,
 kind varchar(20) NOT NULL CHECK (kind IN ('LATE','ABSENCE','HANDOVER','INCIDENT')),
 detail varchar(500) NOT NULL, reported_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (organization_id, branch_id, shift_id) REFERENCES operator_shifts(organization_id, branch_id, id)
);

-- M16: one notification per (event, recipient) even across retries; per-user preferences; failure tracking.
ALTER TABLE notifications
  ADD COLUMN outbox_event_id uuid REFERENCES outbox_events(id),
  ADD COLUMN template_code varchar(60) NOT NULL DEFAULT 'GENERIC',
  ADD COLUMN channel varchar(16) NOT NULL DEFAULT 'IN_APP' CHECK (channel IN ('IN_APP','EMAIL','SMS','WHATSAPP')),
  ADD COLUMN delivery_status varchar(20) NOT NULL DEFAULT 'DELIVERED' CHECK (delivery_status IN ('DELIVERED','PENDING_PROVIDER','FAILED'));
CREATE UNIQUE INDEX one_notification_per_event_recipient ON notifications(outbox_event_id, recipient_user_id, channel) WHERE outbox_event_id IS NOT NULL;
ALTER TABLE outbox_events ADD COLUMN last_error varchar(300);

CREATE TABLE notification_preferences (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),
 event_type varchar(80) NOT NULL, channel varchar(16) NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS','WHATSAPP')),
 enabled boolean NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (user_id, event_type, channel)
);
