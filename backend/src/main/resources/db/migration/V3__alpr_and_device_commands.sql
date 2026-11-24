-- M14/M15: provider-labelled readings, automatic vs human decisions, auditable barrier commands.
ALTER TABLE plate_recognitions DROP CONSTRAINT plate_recognitions_mode_check;
ALTER TABLE plate_recognitions ADD CONSTRAINT plate_recognitions_mode_check CHECK (mode IN ('SIMULATOR','SIMULATED_CAMERA','TEMPLATE_OCR'));
ALTER TABLE plate_recognitions DROP CONSTRAINT plate_recognitions_review_status_check;
ALTER TABLE plate_recognitions ADD CONSTRAINT plate_recognitions_review_status_check CHECK (review_status IN ('PENDING','AUTO_ACCEPTED','CONFIRMED','REJECTED'));
ALTER TABLE plate_recognitions ALTER COLUMN plate_candidate DROP NOT NULL;
ALTER TABLE plate_recognitions
  ADD COLUMN device_id uuid,
  ADD COLUMN vehicle_id uuid,
  ADD COLUMN decision_reason varchar(200) NOT NULL DEFAULT '',
  ADD COLUMN warnings varchar(400) NOT NULL DEFAULT '',
  ADD COLUMN reviewed_at timestamptz,
  ADD CONSTRAINT plate_recognitions_scope UNIQUE (organization_id, branch_id, id),
  ADD CONSTRAINT plate_recognitions_device_fk FOREIGN KEY (organization_id, branch_id, device_id) REFERENCES devices(organization_id, branch_id, id),
  ADD CONSTRAINT plate_recognitions_vehicle_fk FOREIGN KEY (organization_id, vehicle_id) REFERENCES vehicles(organization_id, id);
CREATE INDEX plate_recognitions_scope_idx ON plate_recognitions(organization_id, branch_id, review_status, created_at);

CREATE TABLE device_commands (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, branch_id uuid NOT NULL, device_id uuid NOT NULL,
 command varchar(20) NOT NULL CHECK (command IN ('OPEN','CLOSE')),
 status varchar(20) NOT NULL CHECK (status IN ('SIMULATED_ACK','REJECTED','TIMEOUT','FAILED')),
 recognition_id uuid, reason varchar(300) NOT NULL DEFAULT '', requested_by uuid NOT NULL REFERENCES users(id),
 requested_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
 FOREIGN KEY (organization_id, branch_id, device_id) REFERENCES devices(organization_id, branch_id, id),
 FOREIGN KEY (organization_id, branch_id, recognition_id) REFERENCES plate_recognitions(organization_id, branch_id, id)
);
CREATE INDEX device_commands_scope ON device_commands(organization_id, branch_id, requested_at);
