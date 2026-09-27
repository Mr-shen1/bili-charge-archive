# M8 生产部署包与隔离恢复验收

- 日期：2026-09-27
- 环境：Windows + Docker Desktop 29.8.0 / Compose v5.5.1；`m8_source` 与 `m8_restore` 为独立项目和 MySQL 卷。演练文件在被 Git 忽略的 `deploy/state/`，环境文件在被 Git 忽略的 `deploy/.env.m8-*`。
- 范围：仅 M8；未连接正式公网 IP、正式 OSS、正式群或正式 B 站账号，未启动 monitor，未切换旧监控。

## 部署包与隔离启动

| 检查 | 证据与结果 |
| --- | --- |
| Compose 配置 | `docker compose --env-file deploy/.env.m8-source -f deploy/compose.prod.yaml config --quiet` 退出码 0；恢复项目同样通过。 |
| 服务与端口 | 两项目的 MySQL、Spring、Nginx 启动并为 healthy；恢复项目仅主机 `127.0.0.1:80/443` 映射，MySQL 仅显示容器内 `3306/tcp`，Spring 仅显示容器内 `8080/tcp`，无 monitor 容器。 |
| 数据库迁移 | 新库 Flyway 成功版本 V6，共 12 张表（含 Flyway 元数据与队列锁表）。 |
| HTTP 安全 | 引导模式只允许 ACME 与健康检查，业务页及 API 返回 503、`/internal/ups` 404；模拟 HTTPS 下匿名 API 401、管理员登录 200、群接口未返回合成密文。 |
| 资源与日志 | 恢复项目空载一次采样：MySQL 195.6 MiB、Spring 200.9 MiB、Nginx 12.31 MiB；容器上限分别 512/640/64 MiB。Nginx `json-file` 轮转实查为 10 MiB×3。此为开发机基线，不能代替 2 GB 主机 OPS-02。 |

## OPS-04 可预演部分

1. 在禁用 UP 的合成数据中写入 1 条动态、1 条评论，以及动态/评论各 1 条 READY 图片 Key；均无真实 OSS 对象或真实 Webhook。`backup.sh` 生成 `database.sql.gz`、2 条 Key 的 `oss-keys.txt` 和 `SHA256SUMS`，`gzip -t` 与 SHA-256 检查通过。
2. 停止源项目 Nginx 释放回环端口后，`restore-isolated.sh` 在全新 `m8_restore_mysql_data` 卷恢复 SQL；恢复库动态/评论各 1 条，两类 READY 图片各 1 条，Flyway 仍为 V6，三个服务重新 healthy。源项目 `m8_source_mysql_data` 仍有原合成动态。重复恢复被“目标库非空”保护拒绝，退出码 2。
3. `reconcile-oss-keys.sh` 对完整合成清单报告缺失 0，删去一条后的清单报告缺失 1 且退出码 1。恢复脚本还比对数据库中的 Key 与备份清单。此处只演练 Key 对账；真实 OSS 对象备份和恢复待 M9 验证。
4. `monitor` profile 始终未启动，因此无新系统投递；旧监控也未被修改。真实新旧监控切换、首次基线目标群和任何时刻仅一套发送的验证保留给 M9，执行顺序见 [上线检查表](M9_CUTOVER_CHECKLIST.md)。

## IP HTTPS 可预演部分

- 按 [Let's Encrypt IP 与 Certbot 公告](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)复核：Certbot 5.4+ 的 webroot + `--ip-address` + shortlived 可申请有效期 160 小时的 IP 证书；[Certbot 文档](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)要求成功续期后通过 deploy hook 重载应用。
- 引导配置的 `/.well-known/acme-challenge/m8-probe` 返回预期文本。使用只供本机演练的自签 IP 证书切换 TLS 配置后，HTTP 首页 301、HTTPS 首页 200，ACME 路径仍可访问。
- `install-renewed-cert.sh` 用第二份模拟证书执行 `nginx -t` 和 `nginx -s reload`；实际 TLS 证书 SHA-256 前缀由 `4b3fe92439246eac` 变为 `8ee1b3f76f9ae1c4`，重载后 HTTPS 仍为 200。此证书不受浏览器信任，公网 CA 签发、自动定时续期和浏览器信任尚未验证，属于 M9。
- 用隔离目录中的模拟 `certbot` 执行 `renew-cert.sh`，核对证书名参数和 deploy hook；hook 再次安装证书并重载，服务端指纹变为 `9c5bd3f3a05f62e7`。这验证脚本调用链，不等于 CA 真实续期成功。

## OPS-05 可预演部分

- `bash -n deploy/*.sh` 与 ShellCheck 全部通过；`scan-secrets.py` 扫描仓库及额外证据文件（新增代码配置、隔离日志、首页/匿名 API/群 API 响应），并与被 Git 忽略的本地环境凭据比对，潜在暴露数 0。测试代码中的三个明确假 Webhook 后缀已逐一核对并列为扫描器允许的测试常量。
- `git check-ignore` 确认隔离环境文件和 SQL 备份不入库。原始需求、图片、旧脚本及 `AGENTS.md` 本阶段均未修改。
- 演练后对两个隔离项目执行 `docker compose down`；无 `m8_*` 运行容器，独立 MySQL 卷和被忽略的备份仍保留供复核，开发 Compose 未停止。

## M9 仍需真实验证

- 目标阿里云 2 GB 主机的峰值内存、交换、磁盘、容器故障恢复和 1～2 个 UP 的真实扫描负载。
- 固定公网 IP 的 staging/正式签发、浏览器信任、定时续期与剩余有效期监测；确认公网 80/443 安全组。
- 私有 OSS 真实对象清单与异地备份、真实测试群消息及正式切换授权。按检查表核对旧监控全停后才可启动新 monitor，M8 未执行此切换。
