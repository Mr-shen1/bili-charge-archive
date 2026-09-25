ALTER TABLE dynamic_scan_state
  ADD COLUMN baseline_completed_at DATETIME(3) NULL AFTER state_json;
