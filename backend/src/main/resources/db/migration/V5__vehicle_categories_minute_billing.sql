-- Vehicle categories, per-minute billing in 12 h blocks capped by a full rate, monthly plans per category.
--
-- Stays still open under the previous hourly model would be re-priced by the new engine, which RN-012 forbids.
-- The upgrade therefore refuses to run while any stay is open; closed stays keep their final amounts untouched.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM parking_sessions WHERE status = 'OPEN') THEN
    RAISE EXCEPTION 'V5: cierre las estancias abiertas antes de actualizar el modelo tarifario';
  END IF;
END $$;

-- 1. Exactly four categories. Electric and accessible were car variants: they become CAR and the space keeps
--    the physical attribute, so no information is lost.
ALTER TABLE parking_spaces ADD COLUMN feature varchar(16) NOT NULL DEFAULT 'STANDARD'
  CHECK (feature IN ('STANDARD', 'EV_CHARGING', 'ACCESSIBLE'));
UPDATE parking_spaces SET feature = CASE type WHEN 'ELECTRIC' THEN 'EV_CHARGING' ELSE 'ACCESSIBLE' END, type = 'CAR'
 WHERE type IN ('ELECTRIC', 'ACCESSIBLE');
ALTER TABLE parking_spaces DROP CONSTRAINT parking_spaces_type_check;
ALTER TABLE parking_spaces ADD CONSTRAINT parking_spaces_type_check CHECK (type IN ('MOTORCYCLE', 'CAR', 'BICYCLE', 'CARGO'));

UPDATE vehicles SET type = 'CAR' WHERE type IN ('ELECTRIC', 'ACCESSIBLE');
ALTER TABLE vehicles DROP CONSTRAINT vehicles_type_check;
ALTER TABLE vehicles ADD CONSTRAINT vehicles_type_check CHECK (type IN ('MOTORCYCLE', 'CAR', 'BICYCLE', 'CARGO'));

-- 2. Rate configuration per branch and category. Initial values live in this template table, never in code.
CREATE TABLE vehicle_rate_templates (
 vehicle_type varchar(16) PRIMARY KEY CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BICYCLE', 'CARGO')),
 minute_rate numeric(14,2) NOT NULL CHECK (minute_rate > 0),
 full_rate numeric(14,2) NOT NULL,
 block_minutes int NOT NULL CHECK (block_minutes BETWEEN 1 AND 10080),
 monthly_fee numeric(14,2) NOT NULL CHECK (monthly_fee > 0),
 grace_minutes int NOT NULL CHECK (grace_minutes BETWEEN 0 AND 1440),
 capacity int NOT NULL CHECK (capacity >= 0),
 sort_order int NOT NULL UNIQUE,
 CHECK (full_rate >= minute_rate)
);
-- Capacity starts at 0: entries are refused until the branch declares how many vehicles of each kind it can hold.
INSERT INTO vehicle_rate_templates (vehicle_type, minute_rate, full_rate, block_minutes, monthly_fee, grace_minutes, capacity, sort_order) VALUES
 ('MOTORCYCLE',  80, 15000, 720,  90000, 0, 0, 1),
 ('CAR',        150, 32000, 720, 180000, 0, 0, 2),
 ('BICYCLE',     30,  5000, 720,  40000, 0, 0, 3),
 ('CARGO',      250, 50000, 720, 350000, 0, 0, 4);

CREATE TABLE vehicle_rates (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, branch_id uuid NOT NULL,
 vehicle_type varchar(16) NOT NULL REFERENCES vehicle_rate_templates(vehicle_type),
 minute_rate numeric(14,2) NOT NULL CHECK (minute_rate > 0),
 full_rate numeric(14,2) NOT NULL,
 block_minutes int NOT NULL CHECK (block_minutes BETWEEN 1 AND 10080),
 monthly_fee numeric(14,2) NOT NULL CHECK (monthly_fee > 0),
 grace_minutes int NOT NULL CHECK (grace_minutes BETWEEN 0 AND 1440),
 capacity int NOT NULL CHECK (capacity BETWEEN 0 AND 100000),
 active boolean NOT NULL DEFAULT true,
 version int NOT NULL DEFAULT 1,
 updated_by uuid REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (full_rate >= minute_rate), CHECK (grace_minutes < block_minutes),
 UNIQUE (branch_id, vehicle_type), UNIQUE (organization_id, branch_id, id),
 FOREIGN KEY (organization_id, branch_id) REFERENCES branches(organization_id, id)
);

-- Append-only history: every insert or update of a rate leaves a numbered version.
CREATE TABLE vehicle_rate_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, branch_id uuid NOT NULL, rate_id uuid NOT NULL,
 version int NOT NULL, vehicle_type varchar(16) NOT NULL,
 minute_rate numeric(14,2) NOT NULL, full_rate numeric(14,2) NOT NULL, block_minutes int NOT NULL, monthly_fee numeric(14,2) NOT NULL,
 grace_minutes int NOT NULL, capacity int NOT NULL, active boolean NOT NULL,
 changed_by uuid REFERENCES users(id), changed_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (rate_id, version),
 FOREIGN KEY (organization_id, branch_id, rate_id) REFERENCES vehicle_rates(organization_id, branch_id, id)
);

CREATE FUNCTION vehicle_rate_before_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.vehicle_type <> OLD.vehicle_type OR NEW.branch_id <> OLD.branch_id OR NEW.organization_id <> OLD.organization_id THEN
    RAISE EXCEPTION 'La categoría y la sede de una tarifa no se modifican';
  END IF;
  NEW.version := OLD.version + 1;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_rate_version BEFORE UPDATE ON vehicle_rates FOR EACH ROW EXECUTE FUNCTION vehicle_rate_before_update();

CREATE FUNCTION vehicle_rate_record_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO vehicle_rate_versions (organization_id, branch_id, rate_id, version, vehicle_type, minute_rate, full_rate, block_minutes,
                                     monthly_fee, grace_minutes, capacity, active, changed_by)
  VALUES (NEW.organization_id, NEW.branch_id, NEW.id, NEW.version, NEW.vehicle_type, NEW.minute_rate, NEW.full_rate, NEW.block_minutes,
          NEW.monthly_fee, NEW.grace_minutes, NEW.capacity, NEW.active, NEW.updated_by);
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_rate_history AFTER INSERT OR UPDATE ON vehicle_rates FOR EACH ROW EXECUTE FUNCTION vehicle_rate_record_version();

CREATE FUNCTION immutable_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'History rows are immutable'; END $$;
CREATE TRIGGER no_rate_version_mutation BEFORE UPDATE OR DELETE ON vehicle_rate_versions FOR EACH ROW EXECUTE FUNCTION immutable_history();

-- Every branch, existing or future, gets one configuration per category from the template.
CREATE FUNCTION provision_vehicle_rates() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO vehicle_rates (organization_id, branch_id, vehicle_type, minute_rate, full_rate, block_minutes, monthly_fee, grace_minutes, capacity)
  SELECT NEW.organization_id, NEW.id, t.vehicle_type, t.minute_rate, t.full_rate, t.block_minutes, t.monthly_fee, t.grace_minutes, t.capacity
    FROM vehicle_rate_templates t;
  RETURN NEW;
END $$;
CREATE TRIGGER branch_vehicle_rates AFTER INSERT ON branches FOR EACH ROW EXECUTE FUNCTION provision_vehicle_rates();
INSERT INTO vehicle_rates (organization_id, branch_id, vehicle_type, minute_rate, full_rate, block_minutes, monthly_fee, grace_minutes, capacity)
SELECT b.organization_id, b.id, t.vehicle_type, t.minute_rate, t.full_rate, t.block_minutes, t.monthly_fee, t.grace_minutes, t.capacity
  FROM branches b CROSS JOIN vehicle_rate_templates t;

-- 3. Stays: frozen rate snapshot, category, optional space (capacity is per category), six-digit ticket code.
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_org_id UNIQUE (organization_id, id);
ALTER TABLE parking_sessions
  ALTER COLUMN space_id DROP NOT NULL,
  ALTER COLUMN hourly_rate DROP NOT NULL,
  ADD COLUMN pricing_model varchar(20) NOT NULL DEFAULT 'MINUTE_BLOCKS' CHECK (pricing_model IN ('HOURLY_LEGACY', 'MINUTE_BLOCKS')),
  ADD COLUMN vehicle_type varchar(16),
  ADD COLUMN minute_rate numeric(14,2),
  ADD COLUMN full_rate numeric(14,2),
  ADD COLUMN rate_id uuid,
  ADD COLUMN rate_version int,
  ADD COLUMN ticket_code varchar(6),
  ADD COLUMN subscription_id uuid;
UPDATE parking_sessions p SET pricing_model = 'HOURLY_LEGACY', vehicle_type = v.type FROM vehicles v WHERE v.id = p.vehicle_id;
ALTER TABLE parking_sessions
  ALTER COLUMN vehicle_type SET NOT NULL,
  ADD CONSTRAINT parking_sessions_vehicle_type_check CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BICYCLE', 'CARGO')),
  ADD CONSTRAINT parking_sessions_rate_snapshot CHECK (pricing_model <> 'MINUTE_BLOCKS' OR (
    minute_rate > 0 AND full_rate >= minute_rate AND block_minutes > 0 AND rate_id IS NOT NULL AND rate_version IS NOT NULL
    AND ticket_code ~ '^[0-9]{6}$')),
  ADD CONSTRAINT parking_sessions_rate_fk FOREIGN KEY (organization_id, branch_id, rate_id) REFERENCES vehicle_rates(organization_id, branch_id, id),
  ADD CONSTRAINT parking_sessions_subscription_fk FOREIGN KEY (organization_id, subscription_id) REFERENCES subscriptions(organization_id, id);
CREATE UNIQUE INDEX one_open_ticket_code ON parking_sessions(branch_id, ticket_code) WHERE status = 'OPEN';
CREATE INDEX open_sessions_by_type ON parking_sessions(branch_id, vehicle_type) WHERE status = 'OPEN';

-- The instant a payment order was priced for, so a closed stay's liquidation can be reproduced exactly.
ALTER TABLE payment_orders ADD COLUMN priced_at timestamptz;

-- 4. Discounts need supervision; they are amounts with a reason, never an edit of the computed value.
CREATE TABLE session_discounts (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, session_id uuid NOT NULL,
 amount numeric(14,2) NOT NULL CHECK (amount > 0), reason varchar(400) NOT NULL CHECK (length(btrim(reason)) > 0),
 approved_by uuid NOT NULL REFERENCES users(id), idempotency_key uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (organization_id, idempotency_key),
 FOREIGN KEY (organization_id, branch_id, session_id) REFERENCES parking_sessions(organization_id, branch_id, id)
);

-- 5. Monthly plans per category: price (empty = the category's monthly fee), days and hours allowed, grace after
--    expiry and additional authorized branches of the same organization.
ALTER TABLE subscription_plans
  ADD COLUMN vehicle_type varchar(16),
  ALTER COLUMN price DROP NOT NULL,
  ADD COLUMN allowed_days varchar(7) NOT NULL DEFAULT '1234567' CHECK (allowed_days ~ '^[1-7]{1,7}$'),
  ADD COLUMN allowed_start time,
  ADD COLUMN allowed_end time,
  ADD COLUMN grace_days int NOT NULL DEFAULT 0 CHECK (grace_days BETWEEN 0 AND 31),
  ADD CONSTRAINT subscription_plans_window CHECK ((allowed_start IS NULL) = (allowed_end IS NULL) AND (allowed_start IS NULL OR allowed_start <> allowed_end));
-- Existing plans had no category: use the single category of their contracts, otherwise CAR.
UPDATE subscription_plans p SET vehicle_type = coalesce(
  (SELECT min(v.type) FROM subscriptions s JOIN vehicles v ON v.id = s.vehicle_id WHERE s.plan_id = p.id HAVING count(DISTINCT v.type) = 1), 'CAR');
ALTER TABLE subscription_plans
  ALTER COLUMN vehicle_type SET NOT NULL,
  ADD CONSTRAINT subscription_plans_vehicle_type_check CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BICYCLE', 'CARGO'));

CREATE TABLE subscription_plan_branches (
 organization_id uuid NOT NULL, plan_branch_id uuid NOT NULL, plan_id uuid NOT NULL, branch_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (plan_id, branch_id), CHECK (branch_id <> plan_branch_id),
 FOREIGN KEY (organization_id, plan_branch_id, plan_id) REFERENCES subscription_plans(organization_id, branch_id, id),
 FOREIGN KEY (organization_id, branch_id) REFERENCES branches(organization_id, id)
);
CREATE INDEX subscriptions_vehicle_window ON subscriptions(organization_id, vehicle_id, starts_at, ends_at);
