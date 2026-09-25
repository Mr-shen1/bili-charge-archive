-- Flyway uses the configured database; database creation belongs to deployment.
CREATE TABLE feishu_group (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  webhook_ciphertext TEXT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_feishu_group_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE up_account (
  uid VARCHAR(32) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  avatar_url VARCHAR(2048) NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  ops_group_id BIGINT UNSIGNED NOT NULL,
  default_all_group_id BIGINT UNSIGNED NULL,
  default_up_group_id BIGINT UNSIGNED NULL,
  worker_heartbeat_at DATETIME(3) NULL,
  last_scan_started_at DATETIME(3) NULL,
  last_scan_succeeded_at DATETIME(3) NULL,
  last_scan_error_at DATETIME(3) NULL,
  last_scan_error VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (uid),
  KEY idx_up_enabled (enabled, uid),
  KEY idx_up_ops_group (ops_group_id),
  KEY idx_up_default_all_group (default_all_group_id),
  KEY idx_up_default_up_group (default_up_group_id),
  CONSTRAINT chk_up_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT chk_up_default_nonempty
    CHECK (default_all_group_id IS NOT NULL OR default_up_group_id IS NOT NULL),
  CONSTRAINT chk_up_default_distinct
    CHECK (default_all_group_id IS NULL OR default_up_group_id IS NULL
           OR default_all_group_id <> default_up_group_id),
  CONSTRAINT fk_up_ops_group FOREIGN KEY (ops_group_id) REFERENCES feishu_group (id),
  CONSTRAINT fk_up_default_all_group
    FOREIGN KEY (default_all_group_id) REFERENCES feishu_group (id),
  CONSTRAINT fk_up_default_up_group
    FOREIGN KEY (default_up_group_id) REFERENCES feishu_group (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dynamic_route (
  dynamic_id VARCHAR(32) NOT NULL,
  up_uid VARCHAR(32) NOT NULL,
  all_group_id BIGINT UNSIGNED NULL,
  up_group_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dynamic_id),
  KEY idx_route_up (up_uid, dynamic_id),
  KEY idx_route_all_group (all_group_id),
  KEY idx_route_up_group (up_group_id),
  CONSTRAINT chk_route_nonempty
    CHECK (all_group_id IS NOT NULL OR up_group_id IS NOT NULL),
  CONSTRAINT chk_route_distinct
    CHECK (all_group_id IS NULL OR up_group_id IS NULL
           OR all_group_id <> up_group_id),
  CONSTRAINT fk_route_up FOREIGN KEY (up_uid) REFERENCES up_account (uid),
  CONSTRAINT fk_route_all_group
    FOREIGN KEY (all_group_id) REFERENCES feishu_group (id),
  CONSTRAINT fk_route_up_group
    FOREIGN KEY (up_group_id) REFERENCES feishu_group (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dynamic (
  dynamic_id VARCHAR(32) NOT NULL,
  up_uid VARCHAR(32) NOT NULL,
  title VARCHAR(255) NULL,
  content_text MEDIUMTEXT NOT NULL,
  published_at DATETIME(3) NOT NULL,
  comment_oid VARCHAR(32) NOT NULL,
  comment_type SMALLINT UNSIGNED NOT NULL,
  source_unavailable_at DATETIME(3) NULL,
  first_seen_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dynamic_id),
  KEY idx_dynamic_latest (published_at DESC, dynamic_id DESC),
  KEY idx_dynamic_up_latest (up_uid, published_at DESC, dynamic_id DESC),
  CONSTRAINT fk_dynamic_up FOREIGN KEY (up_uid) REFERENCES up_account (uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dynamic_image (
  dynamic_id VARCHAR(32) NOT NULL,
  position SMALLINT UNSIGNED NOT NULL,
  source_url VARCHAR(2048) NOT NULL,
  oss_key VARCHAR(512) NULL,
  upload_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_at DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dynamic_id, position),
  KEY idx_dynamic_image_retry (upload_status, retry_at),
  CONSTRAINT chk_dynamic_image_status
    CHECK (upload_status IN ('PENDING', 'RETRY', 'READY', 'UNAVAILABLE')),
  CONSTRAINT fk_dynamic_image_dynamic
    FOREIGN KEY (dynamic_id) REFERENCES dynamic (dynamic_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE comment (
  dynamic_id VARCHAR(32) NOT NULL,
  rpid VARCHAR(32) NOT NULL,
  root_rpid VARCHAR(32) NULL,
  parent_rpid VARCHAR(32) NULL,
  author_mid VARCHAR(32) NOT NULL,
  author_name VARCHAR(100) NOT NULL,
  author_avatar_url VARCHAR(2048) NULL,
  author_level SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  content_text MEDIUMTEXT NOT NULL,
  published_at DATETIME(3) NOT NULL,
  like_count INT UNSIGNED NOT NULL DEFAULT 0,
  reply_count INT UNSIGNED NOT NULL DEFAULT 0,
  is_up TINYINT(1) NOT NULL DEFAULT 0,
  source_unavailable_at DATETIME(3) NULL,
  first_seen_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dynamic_id, rpid),
  KEY idx_comment_thread (dynamic_id, root_rpid, published_at, rpid),
  CONSTRAINT chk_comment_is_up CHECK (is_up IN (0, 1)),
  CONSTRAINT chk_comment_parent_pair
    CHECK ((root_rpid IS NULL AND parent_rpid IS NULL)
        OR (root_rpid IS NOT NULL AND parent_rpid IS NOT NULL)),
  CONSTRAINT fk_comment_dynamic
    FOREIGN KEY (dynamic_id) REFERENCES dynamic (dynamic_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE comment_image (
  dynamic_id VARCHAR(32) NOT NULL,
  rpid VARCHAR(32) NOT NULL,
  position SMALLINT UNSIGNED NOT NULL,
  source_url VARCHAR(2048) NOT NULL,
  oss_key VARCHAR(512) NULL,
  upload_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_at DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dynamic_id, rpid, position),
  KEY idx_comment_image_retry (upload_status, retry_at),
  CONSTRAINT chk_comment_image_status
    CHECK (upload_status IN ('PENDING', 'RETRY', 'READY', 'UNAVAILABLE')),
  CONSTRAINT fk_comment_image_comment
    FOREIGN KEY (dynamic_id, rpid) REFERENCES comment (dynamic_id, rpid)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dynamic_scan_state (
  dynamic_id VARCHAR(32) NOT NULL,
  state_json JSON NOT NULL,
  last_complete_scan_at DATETIME(3) NULL,
  last_full_scan_at DATETIME(3) NULL,
  full_scan_retry_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dynamic_id),
  CONSTRAINT fk_scan_dynamic
    FOREIGN KEY (dynamic_id) REFERENCES dynamic (dynamic_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dedupe_key VARCHAR(160) NOT NULL,
  up_uid VARCHAR(32) NOT NULL,
  dynamic_id VARCHAR(32) NULL,
  comment_rpid VARCHAR(32) NULL,
  event_type VARCHAR(24) NOT NULL,
  is_up_comment TINYINT(1) NOT NULL DEFAULT 0,
  message_text MEDIUMTEXT NOT NULL,
  image_source_urls JSON NULL,
  ready_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_dedupe (dedupe_key),
  KEY idx_event_up_queue (up_uid, completed_at, id),
  KEY idx_event_completed (completed_at),
  KEY idx_event_dynamic (dynamic_id, id),
  CONSTRAINT chk_event_type
    CHECK (event_type IN ('DYNAMIC', 'COMMENT', 'OPS_HEARTBEAT', 'OPS_ERROR')),
  CONSTRAINT chk_event_is_up CHECK (is_up_comment IN (0, 1)),
  CONSTRAINT fk_event_up FOREIGN KEY (up_uid) REFERENCES up_account (uid),
  CONSTRAINT fk_event_dynamic FOREIGN KEY (dynamic_id) REFERENCES dynamic (dynamic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_delivery (
  event_id BIGINT UNSIGNED NOT NULL,
  role VARCHAR(8) NOT NULL,
  group_id BIGINT UNSIGNED NULL,
  group_name_snapshot VARCHAR(100) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  lease_owner VARCHAR(64) NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (event_id, role),
  KEY idx_delivery_due (status, next_retry_at, lease_until),
  KEY idx_delivery_group (group_id, status, event_id),
  CONSTRAINT chk_delivery_role CHECK (role IN ('ALL', 'UP', 'OPS')),
  CONSTRAINT chk_delivery_status
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'SKIPPED')),
  CONSTRAINT fk_delivery_event
    FOREIGN KEY (event_id) REFERENCES notification_event (id) ON DELETE CASCADE,
  CONSTRAINT fk_delivery_group
    FOREIGN KEY (group_id) REFERENCES feishu_group (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
