CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE organizations (
 id uuid PRIMARY KEY, name varchar(160) NOT NULL, tax_id varchar(40), active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE branches (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id), code varchar(30) NOT NULL,
 name varchar(160) NOT NULL, address varchar(300) NOT NULL DEFAULT '', timezone varchar(80) NOT NULL DEFAULT 'America/Bogota',
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id), UNIQUE(organization_id,code)
);
CREATE TABLE users (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE CHECK(email=lower(email)), password_hash varchar(120) NOT NULL,
 full_name varchar(160) NOT NULL, active boolean NOT NULL DEFAULT true, failed_attempts int NOT NULL DEFAULT 0,
 locked_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE user_memberships (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), organization_id uuid NOT NULL REFERENCES organizations(id),
 branch_id uuid, role varchar(24) NOT NULL CHECK(role IN('SUPERADMIN','ADMIN','SUPERVISOR','OPERATOR','CUSTOMER')),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id),
 UNIQUE NULLS NOT DISTINCT(user_id,organization_id,branch_id,role)
);
CREATE TABLE customers (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id), user_id uuid REFERENCES users(id),
 name varchar(160) NOT NULL, document_number varchar(40) NOT NULL DEFAULT '', email varchar(254) NOT NULL DEFAULT '',
 phone varchar(40) NOT NULL DEFAULT '', consent boolean NOT NULL DEFAULT false, active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(organization_id,id), UNIQUE(organization_id,user_id)
);
CREATE TABLE vehicles (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id), customer_id uuid,
 plate varchar(12) NOT NULL, country_code char(2) NOT NULL DEFAULT 'CO', type varchar(16) NOT NULL CHECK(type IN('CAR','MOTORCYCLE','ELECTRIC','ACCESSIBLE')),
 brand varchar(80) NOT NULL DEFAULT '', watchlisted boolean NOT NULL DEFAULT false, active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id), UNIQUE(organization_id,plate,country_code), FOREIGN KEY(organization_id,customer_id) REFERENCES customers(organization_id,id)
);
CREATE TABLE parking_zones (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, name varchar(100) NOT NULL, level varchar(30) NOT NULL DEFAULT '1',
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE TABLE parking_spaces (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, zone_id uuid,
 code varchar(30) NOT NULL, type varchar(16) NOT NULL CHECK(type IN('CAR','MOTORCYCLE','ELECTRIC','ACCESSIBLE')),
 status varchar(20) NOT NULL DEFAULT 'AVAILABLE' CHECK(status IN('AVAILABLE','BLOCKED','MAINTENANCE')),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), UNIQUE(branch_id,code),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id),
 FOREIGN KEY(organization_id,branch_id,zone_id) REFERENCES parking_zones(organization_id,branch_id,id)
);
CREATE TABLE tariff_plans (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, name varchar(100) NOT NULL,
 type varchar(16) NOT NULL CHECK(type IN('CAR','MOTORCYCLE','ELECTRIC','ACCESSIBLE')),
 hourly_rate numeric(14,2) NOT NULL CHECK(hourly_rate>=0), grace_minutes int NOT NULL DEFAULT 0 CHECK(grace_minutes>=0),
 block_minutes int NOT NULL DEFAULT 60 CHECK(block_minutes>0 AND block_minutes<=1440),
 daily_cap numeric(14,2) CHECK(daily_cap>0), active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE UNIQUE INDEX active_tariff_type ON tariff_plans(branch_id,type) WHERE active;
CREATE TABLE parking_sessions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, vehicle_id uuid NOT NULL, space_id uuid NOT NULL,
 ticket_number bigint GENERATED ALWAYS AS IDENTITY, entry_at timestamptz NOT NULL DEFAULT now(), exit_at timestamptz,
 status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN('OPEN','CLOSED')),
 entry_operator_id uuid NOT NULL REFERENCES users(id), exit_operator_id uuid REFERENCES users(id),
 hourly_rate numeric(14,2) NOT NULL, grace_minutes int NOT NULL, block_minutes int NOT NULL, daily_cap numeric(14,2),
 final_amount numeric(14,2), idempotency_key uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), UNIQUE(organization_id,idempotency_key),
 FOREIGN KEY(organization_id,vehicle_id) REFERENCES vehicles(organization_id,id),
 FOREIGN KEY(organization_id,branch_id,space_id) REFERENCES parking_spaces(organization_id,branch_id,id),
 CHECK(exit_at IS NULL OR exit_at>=entry_at)
);
CREATE UNIQUE INDEX one_open_vehicle ON parking_sessions(organization_id,vehicle_id) WHERE status='OPEN';
CREATE UNIQUE INDEX one_open_space ON parking_sessions(space_id) WHERE status='OPEN';
CREATE INDEX sessions_scope_date ON parking_sessions(organization_id,branch_id,entry_at);
CREATE TABLE reservations (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, customer_id uuid NOT NULL, vehicle_id uuid NOT NULL, space_id uuid NOT NULL,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, status varchar(20) NOT NULL DEFAULT 'CONFIRMED'
 CHECK(status IN('CONFIRMED','CANCELLED','CHECKED_IN','COMPLETED','NO_SHOW')), session_id uuid,
 idempotency_key uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), UNIQUE(organization_id,idempotency_key), CHECK(ends_at>starts_at),
 FOREIGN KEY(organization_id,customer_id) REFERENCES customers(organization_id,id),
 FOREIGN KEY(organization_id,vehicle_id) REFERENCES vehicles(organization_id,id),
 FOREIGN KEY(organization_id,branch_id,space_id) REFERENCES parking_spaces(organization_id,branch_id,id),
 FOREIGN KEY(organization_id,branch_id,session_id) REFERENCES parking_sessions(organization_id,branch_id,id),
 EXCLUDE USING gist(space_id WITH =,tstzrange(starts_at,ends_at,'[)') WITH &&) WHERE(status IN('CONFIRMED','CHECKED_IN'))
);
CREATE TABLE subscription_plans (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, name varchar(100) NOT NULL,
 price numeric(14,2) NOT NULL CHECK(price>0), duration_days int NOT NULL CHECK(duration_days BETWEEN 1 AND 366),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE TABLE subscriptions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, customer_id uuid NOT NULL, vehicle_id uuid NOT NULL, plan_id uuid NOT NULL,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','ACTIVE','SUSPENDED','EXPIRED')),
 price numeric(14,2) NOT NULL CHECK(price>0), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ends_at>starts_at), UNIQUE(organization_id,branch_id,id),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id),
 FOREIGN KEY(organization_id,customer_id) REFERENCES customers(organization_id,id),
 FOREIGN KEY(organization_id,vehicle_id) REFERENCES vehicles(organization_id,id),
 FOREIGN KEY(organization_id,branch_id,plan_id) REFERENCES subscription_plans(organization_id,branch_id,id)
);
CREATE TABLE cash_sessions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, operator_id uuid NOT NULL REFERENCES users(id),
 opened_at timestamptz NOT NULL DEFAULT now(), closed_at timestamptz, opening_balance numeric(14,2) NOT NULL CHECK(opening_balance>=0),
 expected_balance numeric(14,2), counted_balance numeric(14,2), status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN('OPEN','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE UNIQUE INDEX one_open_cash ON cash_sessions(branch_id,operator_id) WHERE status='OPEN';
CREATE TABLE payment_orders (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, session_id uuid, subscription_id uuid, customer_id uuid,
 amount numeric(14,2) NOT NULL CHECK(amount>=0), currency char(3) NOT NULL DEFAULT 'COP', status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PAID','REFUNDED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), UNIQUE(session_id), UNIQUE(subscription_id),
 CHECK(num_nonnulls(session_id,subscription_id)=1),
 FOREIGN KEY(organization_id,branch_id,session_id) REFERENCES parking_sessions(organization_id,branch_id,id),
 FOREIGN KEY(organization_id,branch_id,subscription_id) REFERENCES subscriptions(organization_id,branch_id,id),
 FOREIGN KEY(organization_id,customer_id) REFERENCES customers(organization_id,id)
);
CREATE TABLE payments (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, order_id uuid NOT NULL, cash_session_id uuid NOT NULL,
 amount numeric(14,2) NOT NULL CHECK(amount>0), method varchar(16) NOT NULL CHECK(method IN('CASH','TRANSFER','CARD')),
 reference varchar(160) NOT NULL DEFAULT '', idempotency_key uuid NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,idempotency_key), UNIQUE(organization_id,branch_id,id),
 FOREIGN KEY(organization_id,branch_id,order_id) REFERENCES payment_orders(organization_id,branch_id,id),
 FOREIGN KEY(organization_id,branch_id,cash_session_id) REFERENCES cash_sessions(organization_id,branch_id,id)
);
CREATE TABLE refunds (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, payment_id uuid NOT NULL, amount numeric(14,2) NOT NULL CHECK(amount>0),
 reason varchar(400) NOT NULL, approved_by uuid NOT NULL REFERENCES users(id), idempotency_key uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(organization_id,idempotency_key),
 FOREIGN KEY(organization_id,branch_id,payment_id) REFERENCES payments(organization_id,branch_id,id)
);
CREATE TABLE cash_movements (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, cash_session_id uuid NOT NULL,
 amount numeric(14,2) NOT NULL CHECK(amount<>0), reason varchar(400) NOT NULL, approved_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id,cash_session_id) REFERENCES cash_sessions(organization_id,branch_id,id)
);
CREATE TABLE operator_shifts (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, operator_id uuid NOT NULL REFERENCES users(id),
 scheduled_start timestamptz NOT NULL, scheduled_end timestamptz NOT NULL, actual_start timestamptz, actual_end timestamptz,
 status varchar(16) NOT NULL DEFAULT 'SCHEDULED' CHECK(status IN('SCHEDULED','ACTIVE','ENDED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), CHECK(scheduled_end>scheduled_start),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id),
 EXCLUDE USING gist(operator_id WITH =,tstzrange(scheduled_start,scheduled_end,'[)') WITH &&)
);
CREATE TABLE devices (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, name varchar(100) NOT NULL,
 type varchar(20) NOT NULL CHECK(type IN('CAMERA','BARRIER','SENSOR','READER')), mode varchar(20) NOT NULL DEFAULT 'SIMULATOR' CHECK(mode='SIMULATOR'),
 status varchar(16) NOT NULL DEFAULT 'OFFLINE' CHECK(status IN('ONLINE','OFFLINE')), last_heartbeat_at timestamptz,
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,branch_id,id), FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE TABLE device_events (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, device_id uuid NOT NULL,
 event_type varchar(40) NOT NULL, actor_id uuid REFERENCES users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id,device_id) REFERENCES devices(organization_id,branch_id,id)
);
CREATE TABLE plate_recognitions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, plate_candidate varchar(12) NOT NULL,
 confidence numeric(4,3) NOT NULL CHECK(confidence BETWEEN 0 AND 1), mode varchar(20) NOT NULL CHECK(mode='SIMULATOR'),
 review_status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(review_status IN('PENDING','CONFIRMED','REJECTED')),
 reviewed_by uuid REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE TABLE notifications (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, recipient_user_id uuid NOT NULL REFERENCES users(id),
 title varchar(160) NOT NULL, body varchar(1000) NOT NULL, read_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE TABLE anomaly_alerts (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, type varchar(60) NOT NULL, detail varchar(500) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN('OPEN','RESOLVED')), resolved_by uuid REFERENCES users(id), resolved_at timestamptz,
 reference_id uuid, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE UNIQUE INDEX one_open_anomaly ON anomaly_alerts(branch_id,type,reference_id) WHERE status='OPEN';
CREATE TABLE audit_logs (
 id uuid PRIMARY KEY, organization_id uuid, branch_id uuid, actor_id uuid NOT NULL REFERENCES users(id),
 action varchar(100) NOT NULL, entity_type varchar(60) NOT NULL, entity_id uuid, occurred_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE FUNCTION immutable_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Audit events are immutable'; END $$;
CREATE TRIGGER no_audit_mutation BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION immutable_audit();
CREATE TABLE branch_settings (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, key varchar(80) NOT NULL, value varchar(2000) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(branch_id,key),
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE TABLE outbox_events (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, event_type varchar(80) NOT NULL, aggregate_id uuid NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','DELIVERED','FAILED')), attempts int NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), delivered_at timestamptz,
 FOREIGN KEY(organization_id,branch_id) REFERENCES branches(organization_id,id)
);
CREATE INDEX outbox_pending ON outbox_events(created_at) WHERE status='PENDING';
CREATE INDEX audit_scope ON audit_logs(organization_id,branch_id,occurred_at);
CREATE INDEX vehicle_search ON vehicles(organization_id,plate);
CREATE INDEX reservation_scope ON reservations(organization_id,branch_id,starts_at);
CREATE INDEX payment_scope ON payments(organization_id,branch_id,received_at);
CREATE INDEX notification_recipient ON notifications(organization_id,recipient_user_id,created_at);
