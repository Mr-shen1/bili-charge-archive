# 部署与运维设计

- 状态：M8 生产部署包与隔离恢复演练见本页及 [M8 验收记录](ACCEPTANCE_REPORT_M8.md)；目标 2 GB 主机和公网证书验收仍属于 M9
- 环境：用户现有阿里云 Linux 主机、2 GB RAM、Docker、固定公网 IP、无域名，初始 1～2 个 UP
- 需求与架构：[PRD](PRD.md) · [ARCHITECTURE](ARCHITECTURE.md)
- 后端运行基线：Spring Boot 3.5.x、JDK 21、原生 MyBatis；具体补丁版本在创建工程时固定

## 1. 服务布局

生产包为 [`compose.prod.yaml`](../deploy/compose.prod.yaml)、两套 Nginx 配置及 `deploy/` 运维脚本。Spring 构建与运行镜像均使用 JDK/JRE 21。Nginx 提供 Vue 静态文件并仅反向代理公开 `/api`；Spring、MySQL 与 Python 处于 Compose 私有网络。MySQL 使用命名卷；ACME webroot 与 TLS 文件在 `DEPLOY_STATE_DIR`，Docker 日志由宿主 Docker 引擎持久化并配置轮转。仅映射主机 80/443，3306 和 Spring 无主机端口。`monitor` 独立 profile，普通 `up` 不启动。

Python 容器内的管理进程按启用 UP 启停子进程，不为每个 UP 建一套容器。初始以 1～2 个 UP 测试；增加 UP 前重新做内存和请求量测量。容器设置重启策略、内存上限、每个服务 10 MiB×3 的 Docker 日志轮转与健康检查。Spring `/actuator/health` 覆盖应用与数据库可用性；队列积压需另从管理页 UP 状态、投递记录和日志巡检，容器健康本身不证明扫描或发送正常。`ops-check.sh` 检查容器、磁盘和近期日志。

本地 `deploy/compose.dev.yaml` 的 `monitor` profile 需显式启动。M7 起监控容器同时运行飞书投递线程，会认领数据库中**所有已就绪且未作废**的事件，包括以前累积的待发通知；启动前先检查各 UP 路由、群 Webhook 与待发数量。真实联调应使用隔离库及明确指定的测试群，不直接对有历史积压的开发库启动发送器。

## 2. 配置与凭据

以下名称是实施设计中的环境变量约定，真实部署不得把值写进文档或镜像：

| 变量 | 用途 |
| --- | --- |
| ADMIN_USERNAME、ADMIN_PASSWORD_BCRYPT | 唯一管理员登录；只存密码哈希 |
| BILI_COOKIE | 所有 UP 共用的 B 站账号凭据 |
| MONITOR_API_TOKEN | Python 与 Spring 内部接口鉴权 |
| FEISHU_WEBHOOK_ENC_KEY | 数据库飞书 Webhook 加解密密钥 |
| FEISHU_APP_ID、FEISHU_APP_SECRET | 飞书图片消息上传所需的应用凭据 |
| MYSQL_DATABASE、MYSQL_USER、MYSQL_PASSWORD | 应用数据库 |
| OSS_REGION、OSS_BUCKET、OSS_ACCESS_KEY_ID、OSS_ACCESS_KEY_SECRET | 私有 OSS 上传、读取与签名 |
| TZ | 容器日志时区；数据库时间统一按 UTC 写入 |

在服务器使用仅管理员可读的环境配置文件或容器密钥机制，备份中保护数据库密文与加密密钥。`FEISHU_WEBHOOK_ENC_KEY` 是 Base64 编码的随机 32 字节密钥；已有群配置后必须持续使用同一密钥，丢失密钥将无法解密已保存的 Webhook，轮换时需先制定重新加密方案。飞书图片上传需先验证应用授权与上传接口可用，失败时按通知队列重试。群 Webhook 可由管理员页面输入、在数据库加密，公开 API 仅回显是否已配置。现有脚本中存在硬编码 B 站 Cookie，路由文件中含 Webhook；迁移时不要将原值复制到新文档或仓库，正式上线前移除代码内凭据并更换已暴露的凭据。

OSS Bucket 设置为 Private，使用限于目标目录的 RAM 权限；Spring 执行上传与生成短效 GET 签名 URL，前端不持有 OSS AccessKey。匿名直接访问 Bucket 对象应被拒绝。阿里云官方说明私有对象通过限时签名链接访问，签名链接在有效期内持有者可使用：[私有资源访问](https://www.alibabacloud.com/help/en/oss/how-to-apply-the-private-permission-to-the-actual-business)、[签名下载](https://www.alibabacloud.com/help/en/oss/developer-reference/python-download-using-a-presigned-url)。

M6 实现采用 OSS Java SDK 3.18.4、V4 签名。RAM 身份需允许测试 Bucket 的 `oss:GetBucketAcl`，以及 `bili-charge/` 前缀的 `oss:PutObject`、`oss:PutObjectAcl`、`oss:GetObject`；服务在上传或签名前检查 Bucket ACL 为 Private，上传后将对象 ACL 设为 Private。四项 `OSS_*` 参数须全部配置在不入库的 `deploy/.env`，`OSS_REGION` 填地域 ID（如深圳为 `cn-shenzhen`），不能填控制台展示的中文地域名；未配置时图片任务不运行，已存文字照常可查。真实联调还需以匿名原始对象 URL 请求确认拒绝，再以登录后的本站媒体路径确认十分钟签名链接可用；签名 URL 不写日志。权限与地域依据：[Bucket ACL](https://help.aliyun.com/en/oss/developer-reference/manage-the-acl-of-a-bucket)、[Java SDK 签名下载](https://help.aliyun.com/en/oss/developer-reference/download-using-a-presigned-url)、[地域与 Endpoint](https://help.aliyun.com/zh/oss/user-guide/regions-and-endpoints)。

## 3. 公网 IP 的 HTTPS

截至 2026-09，Let's Encrypt 已提供公网 IP 证书；Certbot 5.4+ 支持用 webroot 为 IP 申请 shortlived 证书，有效期 160 小时。Certbot 当前负责签发与续期，IP 证书的 Nginx 安装与重载需自行配置。M8 实施前已复查 [Let's Encrypt 公告](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)和 [Certbot 续期文档](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)。

目标流程：

1. 在 Linux 主机复制 `deploy/.env.example` 为被 Git 忽略的 `deploy/.env.prod`，填入真实随机密钥，将文件权限设为 600；指定固定公网 IP、`DEPLOY_STATE_DIR` 的绝对路径和 `NGINX_CONFIG=./nginx.bootstrap.conf`。运行 `docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml config --quiet`，创建 state 目录，再 `up -d --build --wait mysql8 spring-app nginx`。此时 HTTP 只服务 ACME 验证和健康检查，登录页及业务 API 返回 503，**尚不接入正式账号、UP 或群**。
2. 确认公网 80/443 防火墙与安全组放通，`http://<IP>/.well-known/acme-challenge/` 从外网可访问。宿主安装 Certbot 5.4+，先用下列 staging 命令验证；staging 证书不受浏览器信任。
3. 使用独立的正式证书名、去掉 `--staging` 签发正式证书，执行 `bash deploy/install-renewed-cert.sh deploy/.env.prod /etc/letsencrypt/live/<IP>`。该脚本把证书复制到私有 state 的 `tls/`，仅在 Nginx 已运行时测试配置并重载。将 `.env.prod` 的 `NGINX_CONFIG` 改为 `./nginx.tls.conf`，重建 Nginx 容器；检查 80→443、浏览器信任和 `/internal` 返回 404。
4. 宿主以 root 的定时任务每天两次执行 `bash /绝对路径/deploy/renew-cert.sh /绝对路径/deploy/.env.prod`，例如 crontab 的 `13 2,14 * * * /bin/bash /绝对路径/deploy/renew-cert.sh /绝对路径/deploy/.env.prod`。此脚本使用 Certbot `renew` 和成功后 deploy hook，复制新证书、执行 `nginx -t` 并重载。安装后用 `certbot renew --dry-run --cert-name <IP>` 验证挑战，再手工演练 hook/重载；每天检查任务退出码与证书到期时间。Certbot 的 deploy hook 只在真正续期成功时运行，`--dry-run` 不能单独证明重载路径。

示意命令（路径和 IP 都须替换，不是可直接部署脚本）：

~~~bash
certbot certonly --staging --cert-name <PUBLIC_IP>-staging --preferred-profile shortlived \
  --webroot --webroot-path <DEPLOY_STATE_DIR>/acme-webroot --ip-address <PUBLIC_IP>
certbot certonly --cert-name <PUBLIC_IP> --preferred-profile shortlived \
  --webroot --webroot-path <DEPLOY_STATE_DIR>/acme-webroot --ip-address <PUBLIC_IP>
~~~

若签发、自动续期或浏览器信任验证未通过，不开放正式登录页面。不要改用自签证书冒充生产 HTTPS。

## 4. 生产包启动与资源

`.env.example` 仅列变量和占位值，不能直接当生产凭据。`compose.prod.yaml` 使用 MySQL 512 MiB、Spring 640 MiB（JVM `-Xmx384m`）、Nginx 64 MiB、monitor 384 MiB 上限；这是 2 GB 主机的起点，不是 OPS-02 的实测通过。先不启动 monitor。M9 上机时按首次基线和双 UP 峰值测量 `docker stats`、`free -m` 与交换量，至少留 256 MiB 可用内存；超限须调整并重验。

~~~bash
chmod 600 deploy/.env.prod
mkdir -p /实际/state目录/{acme-webroot,tls,backups}
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml config --quiet
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml up -d --build --wait mysql8 spring-app nginx
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml ps
bash deploy/ops-check.sh deploy/.env.prod
~~~

启动 monitor 需要另行核对旧监控已停、所有已启用 UP、待发总数和群路由；M8 不做这一步。生产群 Webhook 仅在管理页配置，不写进 Compose。`PUBLIC_BIND_IP=127.0.0.1`、独立 `COMPOSE_PROJECT_NAME=m8_*` 与独立 state 路径用于隔离演练，不能用于正式公网入口。

## 5. 首次上线与容量门槛（M9）

1. 在隔离环境运行 [DATABASE](DATABASE.md) 的 DDL，完成应用配置和私有 OSS 联通检查。
2. 备份旧脚本的 JSON 状态、队列与路由文件供回退参考；**停止旧普通监控与固定动态监控**，确认两者不再发送飞书。
3. 启动 MySQL、Spring、Python 与 Nginx；先验证登录、图片签名、内部令牌、数据库写入及证书。
4. 在管理页建立飞书群、添加第一个 UP 并观察首次基线。旧状态不导入，因此最新 50 条内符合条件的存量动态与评论会重新发飞书；固定目标首次发现亦然。
5. 加入第二个 UP，运行普通轮次、完整校准、图片上传及飞书重试场景，观察 Docker 与主机内存、CPU、磁盘、网络和错误日志。

在目标 2 GB 主机上记录空闲、首次基线及两 UP 同时扫描时的峰值；不得出现 OOM 或持续交换，峰值时仍应留有可观测的系统余量（实施验收建议至少 256 MiB 可用内存）。若不足，先调低 MySQL/JVM 预算或减少同时启用 UP 数，再复测；不能仅凭开发机测试宣布部署合格。

## 6. 日常运维、备份与回退

- 每小时检查各启用 UP 的飞书心跳；同一错误每轮提醒，运维群可能高频收到故障消息。管理页查看最近成功扫描、待发队列与图片失败；全局数据库、Nginx 与证书错误看服务器日志。
- 每天做一次 MySQL 一致性备份，并将备份复制到主机外；保留与恢复周期按实际磁盘空间执行。OSS 内容与数据库 OSS Key 必须配套保留，不能只备份其中一方。定期演练将备份恢复到隔离数据库。
- 完成事件及投递记录保留 90 天；未完成通知及动态、评论正文不自动清理。检查磁盘余量和 OSS 用量。
- 更新 B 站 Cookie 通过服务器环境变量并重启监控容器；更新飞书 Webhook 通过群管理页，未发目标使用新配置。证书续期失败、B 站访问受限、OSS 上传失败均应可从状态或日志发现。
- 回退时先停新监控，再恢复旧脚本及其原状态；两套系统不得同时发送。已经发送的通知无法撤回，回退后可能因状态差异产生重复，须在运维记录中注明切换时间。

回退的第一条操作是 `docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml --profile monitor stop monitor`，随后以 `ps --all` 核实新 monitor 已停，再根据旧脚本的实际 systemd/cron/容器配置恢复旧进程及其原状态。旧监控的具体启动命令由 M9 在目标主机盘点后写入运行记录；不要在不清楚旧任务调度器状态时直接启动两套。若只是 Web/TLS 版本故障且 monitor 未切换，先保持 monitor 关闭，回退已保存的 Compose/镜像配置，运行 `nginx -t` 与健康检查，再决定是否开放公网入口。

每天使用 `bash deploy/backup.sh deploy/.env.prod /安全备份目录/日期时间` 生成一致性 SQL 压缩包、数据库 READY 图片的 OSS Key 清单和 SHA-256 校验文件；脚本在 SQL 快照前后各取一次 Key 清单，若有变化则失败，须丢弃本次目录并在写入较少时重试。备份目录权限仅管理员可读，完成后复制到主机外。MySQL 卷、数据库备份及 `FEISHU_WEBHOOK_ENC_KEY` 都须安全保管。恢复前核对同一时间点的 OSS 对象仍在；用 OSS 清单工具列出实际对象 Key 到私有文件，以 `bash deploy/reconcile-oss-keys.sh <备份>/oss-keys.txt <实际清单>` 检查缺失数，缺失时先恢复对象再开放图片访问。不要把对象签名 URL 当作备份。

隔离恢复用全新的 `m8_*` 项目、独立 MySQL 卷及 127.0.0.1 绑定的环境文件执行 `bash deploy/restore-isolated.sh <隔离env> <备份目录>`。脚本验证 SHA-256、拒绝非空目标库，恢复后比对 OSS Key 清单并启动 Web，不启动 monitor。恢复过程不会对原库执行写入。正式灾难恢复时先阻断新旧发送器、确认备份及 OSS 对象、在隔离环境复核，再制定对生产卷的切换方案；不能把隔离脚本直接指向生产项目。

密钥初始化建议使用 `openssl rand -hex 32` 产生 MySQL 密码和内部令牌，`openssl rand -base64 32` 产生 Webhook 加密密钥；管理员密码先选强密码，可用 Apache `htpasswd -nBC 12 admin` 交互生成 BCrypt 哈希，只把冒号后的哈希写入环境文件。若环境文件经 Compose 解析，哈希中的每个 `$` 写为 `$$`，再以隔离登录验证。轮换 MySQL 密码需先在库内 `ALTER USER`，再更新环境文件并重建相关容器；轮换内部令牌需同时更新 Spring 和 monitor，先停止 monitor。Webhook 加密密钥不能直接替换：先备份并安排旧密钥解密、重新加密全部群配置或在管理页重录 Webhook。OSS/飞书/B 站凭据按各服务撤销旧值、填写新值、重建容器并在测试环境验证。任何备份恢复都不要把明文凭据打印到日志。
