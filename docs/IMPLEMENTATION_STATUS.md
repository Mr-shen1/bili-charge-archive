# 实施状态

- 更新日期：2026-09-26
- 当前目标：[开发实施计划](IMPLEMENTATION_PLAN.md) M2；**已完成本地开发与阶段验收**，后续 M3 尚未开始；见 [M0～M2 回归记录](ACCEPTANCE_REPORT_M0_M2.md)
- 原始需求、参考图片和旧脚本均未改动；项目根目录的 `AGENTS.md` 已按用户后续要求提交

## 里程碑状态

| 里程碑 | 状态 | 验证证据 / 阻塞 |
| --- | --- | --- |
| M0 工程准备 | 已完成 | 后端、前端、Python 构建通过；Compose 镜像构建、三服务健康检查、MySQL 查询及 HTTP 验证通过 |
| M1 数据库与安全基础 | 已完成 | MySQL 8.4.11 空库迁移及重复启动、5 项真实 MySQL/HTTP 测试与 2 项内网鉴权单元测试、开发容器和接口冒烟检查通过；Flyway 兼容性提示见下文 |
| M2 管理配置 | 已完成 | 群、UP、专属路由管理 API 与手机管理页已实现；本轮 15 项后端测试通过，真实 B 站 UP 与动态预览成功；`CFG-07` 的扫描及投递结果待 M4、M7 验证 |
| M3 批次入库 | 未开始 | 不在 M0 范围内 |
| M4 Python 扫描器 | 未开始 | 不在 M0 范围内 |
| M5 查询页面 | 未开始 | 不在 M0 范围内 |
| M6 私有 OSS 图片 | 未开始 | 不在 M0 范围内 |
| M7 飞书通知 | 未开始 | 不在 M0 范围内 |
| M8 部署包与恢复演练 | 未开始 | 不在 M0 范围内 |
| M9 服务器上线验收 | 未开始 | 不在 M0 范围内 |

## M0 技术版本与文件

已确认的技术系列是 Spring Boot 3.5.x、JDK 21 和原生 MyBatis。Maven、Flyway、Vue 3 / TypeScript / Vite 是本计划采用的实施默认值，尚未记录为用户单独确认的决定。具体版本如下，后续里程碑引入新依赖时继续固定版本并检查兼容性。

| 组件 | 本阶段固定版本 / 状态 |
| --- | --- |
| Spring Boot | 3.5.16，已用于构建与启动 |
| JDK | 21；本机验证使用 Temurin 21.0.12.1+1 的校验过的便携归档，Docker 运行镜像固定在 JRE 21.0.12_8 |
| Maven | Wrapper 3.3.4，分发版本 3.9.16 |
| MyBatis Starter | 3.0.5，在 `backend/pom.xml` 的依赖管理中固定；M1 才启用数据库访问 |
| Flyway | Spring Boot 3.5.16 BOM 管理 11.7.2；M1 才加入实际依赖和迁移 |
| Node / Vue / Vite / TypeScript | Node 24.16.0；Vue 3.5.43、Vite 8.3.1、TypeScript 5.9.3，`package-lock.json` 锁定传递依赖 |
| Python | 3.12.10，`monitor/.python-version` 与 `pyproject.toml` 固定；构建工具 setuptools 80.9.0 |
| 本地开发镜像 | MySQL 8.4.11、Nginx 1.28.3；Compose 与 Dockerfile 的基础镜像均带固定 digest |

新增 `backend/`、`frontend/`、`monitor/`、`deploy/compose.dev.yaml`、`.env.example`、`.gitignore`；README 增加本地运行命令。后端和前端的 `.dockerignore` 排除了本地构建产物及依赖目录，缩小后续镜像构建上下文。M0 后端只有健康检查，前端只有占位页，Python 包只有运行时测试；没有开始 M1 的表结构、登录或业务 API。

版本核对依据：[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis Starter 兼容范围](https://github.com/mybatis/spring-boot-starter#requirements)、[Maven 版本](https://maven.apache.org/docs/history.html)、[Node LTS 状态](https://nodejs.org/en/about/previous-releases)、[Vite 8 的 Node 要求](https://vite.dev/blog/announcing-vite8)。

## M0 已运行的验证

| 检查 | 实际结果 |
| --- | --- |
| `backend/mvnw.cmd -q test package`（JDK 21） | 退出码 0；JUnit `HealthSmokeTest` 1 项通过；生成 `bili-charge-archive-0.1.0-SNAPSHOT.jar`（23,765,581 字节） |
| 启动上述 JAR，GET `http://127.0.0.1:18080/actuator/health` | 返回 `{"status":"UP"}`；测试后已停止进程 |
| `frontend/npm ci --registry=https://registry.npmjs.org`、`npm run build` | 退出码 0；Vue 类型检查与 Vite 8.3.1 生产构建通过，生成 `frontend/dist/` |
| `npm run preview -- --port 14173`，GET `http://127.0.0.1:14173/` | HTTP 200，包含页面标题和应用挂载节点；测试后已停止进程 |
| `py -3.12 -m unittest discover -s tests -v` | 1 项测试通过 |
| `py -3.12 -m pip wheel . --wheel-dir dist --no-deps` | 成功构建 `bili_charge_monitor-0.1.0-py3-none-any.whl` |
| `docker compose -f deploy/compose.dev.yaml config --quiet` | 退出码 0；识别 `mysql8`、`spring-app`、`nginx` 三个服务；通过便携 Docker CLI + Compose 插件执行，不依赖本地 Engine |
| `docker compose -f deploy/compose.dev.yaml up --build -d`（Docker Desktop 29.8.0 / Compose 5.5.1） | 退出码 0；MySQL 8.4.11 镜像拉取成功，前后端镜像构建成功，三个服务均启动 |
| `docker compose -f deploy/compose.dev.yaml ps` | `mysql8`、`spring-app`、`nginx` 均为 `Up (healthy)`；端口仅绑定 `127.0.0.1` |
| 容器联通 | MySQL 容器以应用用户对开发库执行 `SELECT 1` 返回 `1`；后端 `http://127.0.0.1:18080/actuator/health` 返回 `UP`；Nginx `http://127.0.0.1:18081/` 返回 HTTP 200，包含应用挂载节点 |
| `docker compose -f deploy/compose.dev.yaml down`，随后 `ps` | 退出码 0；三个容器与开发网络已移除，`ps` 无运行服务；开发数据卷保留 |
| 对新增源文件与配置做凭据模式扫描 | 未发现实际 Cookie、Webhook、AccessKey 等模式；`.env.example` 仅有虚构占位值 |

首次 Maven Wrapper 调用因 PowerShell 对未引号包围的 `-Dmaven=3.9.16` 参数解析失败，改为带引号参数后成功生成 3.9.16 Wrapper。首次 npm 安装因本机预设的镜像站返回 HTTP 400 失败，重新生成使用 npm 官方仓库的锁文件后安装与构建成功；后续应按 README 指定官方仓库。上述失败已解决，不是当前阻塞。

## M0 结论与后续边界

先前缺少 Docker Engine / WSL 的阻塞已经解除。本机安装并启动 Docker Desktop 后，真实镜像构建、三服务健康、数据库与 HTTP 连通、停止命令均已通过；**M0 退出条件成立**。首次 Compose 拉取因当前终端未刷新 Docker Desktop 路径而找不到 `docker-credential-desktop`；把 Docker Desktop 的 `resources/bin` 加入该命令进程的 `PATH` 后重试成功。新终端通常会自动获取安装程序添加的路径。

本次验证使用固定摘要的 Docker Hub 镜像。已核查可选的 DaoCloud 镜像代理与 Maven 镜像摘要可解析；未切换代理，也未声称已完成代理环境的完整构建。README 记录可选配置，后续若拉取缓慢可按需使用。本阶段没有进入 M1。

B 站接口字段、鉴权、充电类型与评论 oid/type 尚未做真实联调，这是 M2/M4 的风险；M0 不需要实际 Cookie，也没有读取或复制旧脚本的凭据。

M0 完成后按用户要求初始化本地 Git 仓库。旧脚本目录、环境变量实值和构建产物继续由 `.gitignore` 排除；本次 Git 初始化不属于 M1。

## M1 实现与验证

- `backend/src/main/resources/db/migration/V1__baseline.sql` 使用 Flyway 管理 10 张业务表，表结构与 `DATABASE.md` 的 DDL 相同。迁移省略 `CREATE DATABASE` 和 `USE`，因为部署先建库，Flyway 在配置的数据源内执行；这个差异已记入数据库文档。
- Spring Boot 接入 MySQL、Flyway、原生 MyBatis Mapper；数据库连接按 UTC 会话配置，B 站 ID 的列保持 `VARCHAR(32)`。`PageEnvelope` 提供固定 20 条的列表响应基础，具体键集 cursor 查询由 M5 实现。
- `/api/auth/login` 用环境变量管理员名与 BCrypt 哈希登录，建立服务器会话并轮换 session ID；Cookie 为 `Secure`、`HttpOnly`、`SameSite=Strict`。除登录外的 `/api` 和图片路径均需要会话，写操作需要 `X-CSRF-Token`。错误响应统一为 `code/message/requestId`，响应头带 `X-Request-Id`。
- `/internal` 要求私有网络来源及 `X-Monitor-Token`，Nginx 对该路径直接返回 404。内部业务处理器属于 M3/M4/M7，M1 只交付保护层。缺少管理员哈希或长度不足 32 字符的内部令牌时后端拒绝启动。
- 自动化测试连接隔离的 `bili_charge_archive_m1_test` MySQL 8.4.11 库，没有使用 H2。`backend/mvnw.cmd -q test package` 退出码 0；`AuthHttpTest` 2 项、`HealthSmokeTest` 1 项、`MySqlConstraintsTest` 2 项、`InternalAccessTest` 2 项，合计 7 项通过。检查了首次空库迁移、再次执行迁移 0 项、10 张表、MyBatis 查询、空默认路由、双群相同、无效外键、被引用群删除、匿名接口、正确/错误登录、CSRF 拒绝，以及公网来源或无令牌访问内部接口被拒绝。
- 本机 Compose 的 `mysql8`、`spring-app`、`nginx` 均为 `healthy`；开发库有 10 张业务表和 1 条成功迁移记录。通过 Nginx 请求：首页 200、后端健康 `UP`、匿名动态与图片接口 401、`/internal/ups` 404、登录 200、会话查询 200、缺 CSRF 的写请求 403。测试登录使用被 Git 忽略的 `deploy/.env` 中的本地专用凭据，没有写入仓库。

**M1 结束时的限制**：Spring Boot 3.5.16 管理的 Flyway 11.7.2 在 MySQL 8.4.11 上提示“已测试支持至 8.1”；迁移和约束实测通过，但目标环境升级前仍需复核兼容性。M1 当时尚无前端登录、动态/评论查询或管理业务接口；其中登录与管理已由 M2 实现。M1 当时也未进行真实 B 站、OSS、飞书联调。

## M2 实现与验证

- 后端新增飞书群、UP、专属动态路由的管理 API。群 Webhook 用 AES-256-GCM 加密后入库，接口仅返回是否已配置和引用位置；群仍被运维、默认路由或专属路由引用时返回 `409 GROUP_IN_USE`。UP 运维群必填，默认评论群至少一个，双群互异；专属路由两群也至少一个且互异。UP 与路由保存时重新核对 B 站来源，防止使用伪造或过期的预览结果。
- 前端新增手机管理页：登录、群管理、UP 预览与配置、启停、扫描状态、专属动态预览与路由配置；错误在页面展示，不提供手动重扫或重发。当前“启用”只保存配置；扫描器在 M4 接入后才实际开始抓取。
- 固定响应单元测试覆盖有效充电文字动态、他人动态、非充电、视频类型和缺少评论目标。真实 MySQL 服务测试覆盖密文入库、复用与引用限制、群编辑、UP 启停、默认及专属路由约束、路由更新和状态；HTTP 测试覆盖登录会话、CSRF、公开响应不含 Webhook、群/UP/路由 API。`backend/mvnw.cmd -q test package` 共 14 项通过，`frontend/npm run build` 通过。
- 用被 Git 忽略的本地测试 Cookie 对 UID `550494308` 和动态 `1251460007328743432` 做了真实预览：B 站返回 `code=0`，作者 UID 与输入相同、充电专属标记、`DYNAMIC_TYPE_DRAW` / `MAJOR_TYPE_DRAW`、评论目标 `410108956` / 类型 `11`。经本地 Nginx 的管理 API 再次完成 UP 预览、UP 保存、动态预览、专属路由保存及状态读取，结果一致；创建的假 Webhook 测试配置已从开发库清理。没有发送飞书消息。
- 浏览器以 390×844 视口验证登录、群新增与列表、UP 预览与新增、详情状态、动态真实预览、空专属路由的错误提示和保存成功后的列表。删除操作显示了浏览器原生确认框，用户手动确认；页面最终提示未由自动化抓取，路由删除的 HTTP 场景由测试通过。保存时使用的测试数据已从开发库清理。
- 本地 Compose 重新构建后 `mysql8`、`spring-app` 和 `nginx` 正常启动；群密文查询未包含测试 Webhook 明文，群列表响应也未包含明文。真实预览已测；B 站接口字段和权限仍可能变化，后续 M4 扫描联调需持续核对。

**M2 边界**：尚无扫描进程、内容入库、图片上传、飞书发送和动态/评论查询页。`GET /api/admin/deliveries` 属于 M7，当前未实现。M3 尚未开始。
