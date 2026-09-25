# 实施状态

- 更新日期：2026-09-25
- 当前目标：[开发实施计划](IMPLEMENTATION_PLAN.md) M0；**已完成**，退出条件均有实际验证证据
- 原始需求、参考图片和旧脚本未改动；项目根目录的 `AGENTS.md` 在 M0 开始时已经存在，本阶段没有创建或修改它

## 里程碑状态

| 里程碑 | 状态 | 验证证据 / 阻塞 |
| --- | --- | --- |
| M0 工程准备 | 已完成 | 后端、前端、Python 构建通过；Compose 镜像构建、三服务健康检查、MySQL 查询及 HTTP 验证通过 |
| M1 数据库与安全基础 | 未开始 | 不在 M0 范围内 |
| M2 管理配置 | 未开始 | 不在 M0 范围内 |
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
