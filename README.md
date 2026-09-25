# 类 B 站动态评论页

本项目计划将指定 UP 的充电专属文字、图片动态及评论持久化，提供手机优先的只读查询页面，并继续向飞书群发送文字与图片通知。

> 当前状态：项目尚未实现网站或数据库。根目录的原始需求文档、参考图片和 Python 监控脚本是现有资料；docs/ 中的文件是后续开发的需求与设计依据，不代表功能已上线。

## M0 本地工程骨架

已建立 `backend/`、`frontend/`、`monitor/` 与 `deploy/` 的最小骨架。当前后端只有健康检查，前端是开发占位页，监控程序尚未采集；数据库表、登录、业务页面和通知在后续里程碑实现。实施进度及本机验证结果见[实施状态](docs/IMPLEMENTATION_STATUS.md)。

本地工具基线：JDK 21、Node 24.16.0、Python 3.12.10。Windows PowerShell 中分别运行：

~~~powershell
Set-Location backend
.\mvnw.cmd test package
Set-Location ..\frontend
npm ci --registry=https://registry.npmjs.org
npm run build
Set-Location ..\monitor
py -3.12 -m unittest discover -s tests -v
Set-Location ..
~~~

有 Docker Engine 与 Compose 时，在项目根目录检查并启动仅绑定到本机回环地址的开发服务：

~~~powershell
docker compose -f deploy/compose.dev.yaml config
docker compose -f deploy/compose.dev.yaml up --build -d
docker compose -f deploy/compose.dev.yaml ps
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
Invoke-WebRequest http://127.0.0.1:18081/
docker compose -f deploy/compose.dev.yaml down
~~~

MySQL 使用本地开发专用默认值；正式部署不得沿用。`.env.example` 只有占位值，不要把实际 Cookie、Webhook 或密钥写入仓库。M0 的 Compose 配置不代表生产配置。

若 Docker Hub 在本地拉取很慢，可在 Docker Desktop 的 **Settings → Docker Engine** 中向现有 JSON 加入 `"registry-mirrors": ["https://docker.m.daocloud.io"]`，然后点 **Apply & restart**。这是可选的本机镜像代理配置，项目仍使用固定版本和摘要；代理有白名单与限流，当前 M0 的完整容器验收使用的是 Docker Hub，尚未验证代理下的完整构建。不要把本机的 Docker Engine 设置直接复制到生产服务器。参见 [Docker 镜像代理配置](https://docs.docker.com/docker-hub/image-library/mirror/)及 [DaoCloud 服务说明](https://github.com/DaoCloud/public-image-mirror/blob/main/README.md)。

## 已确认的后端技术基线

后端采用 **Spring Boot 3.5.x + JDK 21 + 原生 MyBatis**；MyBatis Spring Boot Starter 使用兼容的 3.0.x 系列。版本的具体补丁号在创建工程时固定并验证。其余技术组件与尚待确定的版本见[架构设计](docs/ARCHITECTURE.md#技术选型与状态)。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [PRD](docs/PRD.md) | 用户流程、功能规则、范围与验收口径 |
| [架构设计](docs/ARCHITECTURE.md) | 组件职责、扫描、入库、图片和通知链路 |
| [数据库设计](docs/DATABASE.md) | 10 张表、约束、索引及 MySQL 8 建表 SQL |
| [接口契约](docs/API.md) | 页面接口、管理接口和内部监控接口 |
| [部署运维](docs/DEPLOYMENT.md) | 阿里云 2 GB 主机、Docker、HTTPS、备份和凭据 |
| [验收清单](docs/ACCEPTANCE.md) | 可执行的功能、故障和部署验收场景 |
| [开发实施计划](docs/IMPLEMENTATION_PLAN.md) | 分里程碑交付物、退出条件与可直接交给 Goal 的任务文字 |

## 原始资料

- [最初的需求记录](类B站动态评论页.md)；其中空白章节不代表已实现。
- [动态列表示意图一](图片/202609251639510.png)、[动态列表示意图二](图片/202609251642066.png)、[动态详情示意图](图片/202609251648376.png)。
- [现有普通监控脚本](现有爬虫脚本/bili_charge_monitor.py)和[固定动态监控脚本](现有爬虫脚本/bili_charge_target_monitor.py)。脚本行为是迁移参考，最终需求以 PRD 为准。

旧脚本及路由文件含实际凭据，只保留在当前电脑，已由 `.gitignore` 排除；从 Git 检出项目时不会包含这些文件。

## 文档使用方式

PRD 中标为“已确认”的行为来自本次需求讨论。架构、SQL、接口和部署文档中的具体字段、路径、超时与资源配置是为了让方案可实施而补齐的工程设计；开发前应一起评审，发现与 PRD 冲突时以 PRD 为准。参考 B 站接口可能变化，实施时需要用实际可访问的数据再次验证解析逻辑。

现有脚本和路由文件含敏感凭据。阅读、测试或迁移时不要复制 Cookie、Webhook、AccessKey 到文档、日志或代码仓库。按先停旧监控、再启新监控的顺序切换；旧 JSON 状态与飞书队列不导入新系统。
