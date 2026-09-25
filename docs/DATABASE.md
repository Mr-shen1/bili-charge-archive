# 数据库设计与建表 SQL

- 状态：**实施设计，尚未部署**；需求约束见 [PRD](PRD.md)
- 目标：MySQL 8.0.16+ / InnoDB / utf8mb4
- 表数：10；旧 JSON 状态及队列不导入
- 数据访问：已确认使用原生 MyBatis；DDL 与索引独立于 Java 映射实现

## 1. 约定与关系

所有 B 站 UID、动态 ID、评论 ID 和评论 oid 均存为 VARCHAR(32)，HTTP JSON 中也一律用字符串，避免 JavaScript 数字精度问题。时间在应用层按 UTC 写入 DATETIME(3)，接口返回带 Z 的 ISO 8601 字符串，前端按用户本地时间显示。正文只存用于展示的文字，不保存完整 B 站响应。作者名称、头像链接和等级是抓取时的展示快照。

- feishu_group 被 up_account 的运维/默认群及 dynamic_route 的专属群引用。默认群与专属群至少有一个非空；同一路由两个评论群不可相同。被配置引用的群无法删除。
- dynamic_route 只外键关联 UP，不外键关联 dynamic：管理员可能先配置一个固定动态，首次抓取后才产生内容行。保存路由时服务层必须向 B 站核实作者、类型和动态 ID。
- dynamic 与 comment 只做软来源状态标记，不因来源删除而删行。根评论的 root_rpid、parent_rpid 为 NULL；子回复保存根评论 ID 与直接父评论 ID。二者不建自关联外键，避免来源删除导致孤儿回复无法保留。
- 图片表按所属内容和展示顺序定主键，记录原地址、OSS Key 与上传状态。站点只返回鉴权图片接口，不直接回退到原地址。
- dynamic_scan_state.state_json 保存现有审计算法所需的已见集合和分页锚点，是运行状态，不是来源原始 JSON。
- notification_event 的 dedupe_key 防止重复入队；notification_delivery 按事件与角色记录投递、租约和重试。已发记录保留 90 天；未完成事件保留。群名快照使历史记录在群删除后仍可读。

## 2. 可执行 DDL

以下 DDL 用于**新建空库**，不包含旧状态迁移。执行前确认目标库名及备份策略；正式工程宜用版本化迁移工具管理。

~~~sql
CREATE DATABASE IF NOT EXISTS bili_charge_archive
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE bili_charge_archive;

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
~~~

## 3. 关键读写规则

- 动态列表使用 idx_dynamic_latest 或 idx_dynamic_up_latest 做发布时间、ID 的稳定倒序分页。根评论筛选 root_rpid IS NULL 后倒序；楼中楼按同一索引正序。每张卡片的已存评论数对 comment.dynamic_id 做 COUNT，不信任来源展示的评论总数。
- 批次接口由 Spring 服务层开启事务，并通过同一数据源的 MyBatis Mapper 按主键 upsert dynamic/comment/image，更新 dynamic_scan_state，按 dedupe_key 插入 notification_event。首次基线结束前事件 ready_at 为 NULL；完成后放行。内容编辑只更新内容行，不插入新事件。
- 出站认领在短事务内重算未完成目标、设置 notification_delivery 租约；HTTP 发送在事务外；确认结果再用短事务写状态。路由变更可改尚未成功的 group_id，已 SENT 的 group_name_snapshot 与 sent_at 不变。同一群的队头未完成时后续事件不越过。
- 群删除前检查 up_account 与 dynamic_route 的引用；历史 notification_delivery.group_id 可在删除后置 NULL，group_name_snapshot 保留。清理 completed_at 早于 90 天的完成事件时由外键级联清理投递行；未完成事件不清理。
- 来源不可用标记只在明确证据后设置；不因跌出最新 50 条设置。没有用户主动删除内容或 UP 的接口。

## 4. 待实施时验证的工程点

SQL 是完整的新库设计，不表示现有数据库已建立。本文 DDL 已在隔离的 MySQL 8.0.26 临时实例中执行，并验证 10 张表、空路由/重复群约束、被引用群删除限制及历史群名快照；实施时仍须在目标 MySQL 8 版本复验 90 天清理，并对真实评论规模测量索引查询耗时。若部署版本低于 8.0.16，不能把 CHECK 当成已生效的保护；应升级或改用其他约束方案。

MySQL CHECK、外键与 JSON 语法依据：[MySQL CHECK](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)、[MySQL 外键](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)、[MySQL JSON](https://dev.mysql.com/doc/refman/8.0/en/json.html)。
