ALTER TABLE notification_event ADD COLUMN canceled_at DATETIME(3) NULL;

UPDATE notification_event e
JOIN (SELECT DISTINCT up_uid FROM dynamic_route) active ON active.up_uid=e.up_uid
LEFT JOIN dynamic_route selected ON selected.up_uid=e.up_uid AND selected.dynamic_id=e.dynamic_id
SET e.canceled_at=UTC_TIMESTAMP(3)
WHERE e.dynamic_id IS NOT NULL AND e.completed_at IS NULL
  AND selected.dynamic_id IS NULL;
