# 部署与运维设计

- 状态：目标生产运行手册；本地开发 Compose 已实现，生产部署包、IP HTTPS 与 2 GB 主机验收仍属于 M8～M9
- 环境：用户现有阿里云 Linux 主机、2 GB RAM、Docker、固定公网 IP、无域名，初始 1～2 个 UP
- 需求与架构：[PRD](PRD.md) · [ARCHITECTURE](ARCHITECTURE.md)
- 后端运行基线：Spring Boot 3.5.x、JDK 21、原生 MyBatis；具体补丁版本在创建工程时固定

## 1. 服务布局

目标 Docker Compose 运行 nginx、spring-app、mysql8、python-monitor 四类服务。Spring 构建与运行镜像均使用 JDK/JRE 21 主版本；具体发行版和镜像补丁标签在实施时选择、固定并复验。Nginx 提供 Vue 静态文件并仅反向代理公开 /api；Spring、MySQL 与 Python 处于私有 Docker 网络。MySQL 数据、证书、Nginx ACME webroot 与应用日志放持久卷。只开放主机 80/443，3306 和 Spring 端口不得对公网映射。

Python 容器内的管理进程按启用 UP 启停子进程，不为每个 UP 建一套容器。初始以 1～2 个 UP 测试；增加 UP 前重新做内存和请求量测量。容器设置重启策略与健康检查；Spring 健康检查应区分进程存活、MySQL 可用和内部任务积压。

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

截至 2026-09，Let's Encrypt 已提供公网 IP 证书；Certbot 5.4+ 支持用 webroot 为 IP 申请 shortlived 证书，证书有效期约六天。Certbot 当前负责签发与续期，IP 证书的 Nginx 安装与重载需自行配置。官方步骤见 [Let's Encrypt 公告](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。

目标流程：

1. 确认固定公网 IP 可由外部访问 80/443，Nginx 将 /.well-known/acme-challenge/ 映射到持久 webroot。
2. 安装并确认 Certbot 版本不低于 5.4；先用 staging 申请并检查 webroot 验证。
3. 去掉 staging 后申请正式 IP 证书，Nginx 指向证书 fullchain.pem 与 privkey.pem；80 端口除 ACME 挑战外重定向 HTTPS。
4. 配置自动续期与 deploy hook，在证书更新后重载 Nginx；每天检查续期任务是否正常，且定期从公网验证证书剩余有效期。

示意命令（路径和 IP 都须替换，不是可直接部署脚本）：

~~~bash
certbot certonly --staging --preferred-profile shortlived \
  --webroot --webroot-path <ACME_WEBROOT> --ip-address <PUBLIC_IP>
# staging 成功后删除 --staging 再执行，并配置续期及 Nginx reload hook
~~~

若签发、自动续期或浏览器信任验证未通过，不开放正式登录页面。不要改用自签证书冒充生产 HTTPS。

## 4. 首次上线与容量门槛

1. 在隔离环境运行 [DATABASE](DATABASE.md) 的 DDL，完成应用配置和私有 OSS 联通检查。
2. 备份旧脚本的 JSON 状态、队列与路由文件供回退参考；**停止旧普通监控与固定动态监控**，确认两者不再发送飞书。
3. 启动 MySQL、Spring、Python 与 Nginx；先验证登录、图片签名、内部令牌、数据库写入及证书。
4. 在管理页建立飞书群、添加第一个 UP 并观察首次基线。旧状态不导入，因此最新 50 条内符合条件的存量动态与评论会重新发飞书；固定目标首次发现亦然。
5. 加入第二个 UP，运行普通轮次、完整校准、图片上传及飞书重试场景，观察 Docker 与主机内存、CPU、磁盘、网络和错误日志。

在目标 2 GB 主机上记录空闲、首次基线及两 UP 同时扫描时的峰值；不得出现 OOM 或持续交换，峰值时仍应留有可观测的系统余量（实施验收建议至少 256 MiB 可用内存）。若不足，先调低 MySQL/JVM 预算或减少同时启用 UP 数，再复测；不能仅凭开发机测试宣布部署合格。

## 5. 日常运维、备份与回退

- 每小时检查各启用 UP 的飞书心跳；同一错误每轮提醒，运维群可能高频收到故障消息。管理页查看最近成功扫描、待发队列与图片失败；全局数据库、Nginx 与证书错误看服务器日志。
- 每天做一次 MySQL 一致性备份，并将备份复制到主机外；保留与恢复周期按实际磁盘空间执行。OSS 内容与数据库 OSS Key 必须配套保留，不能只备份其中一方。定期演练将备份恢复到隔离数据库。
- 完成事件及投递记录保留 90 天；未完成通知及动态、评论正文不自动清理。检查磁盘余量和 OSS 用量。
- 更新 B 站 Cookie 通过服务器环境变量并重启监控容器；更新飞书 Webhook 通过群管理页，未发目标使用新配置。证书续期失败、B 站访问受限、OSS 上传失败均应可从状态或日志发现。
- 回退时先停新监控，再恢复旧脚本及其原状态；两套系统不得同时发送。已经发送的通知无法撤回，回退后可能因状态差异产生重复，须在运维记录中注明切换时间。
