# M7 飞书通知验收记录

- 日期：2026-09-27
- 范围：投递认领与结果确认、动态路由、运维事件、文字/图片发送、故障恢复；未进入 M8 生产部署
- 环境：MySQL 8.4.11 隔离库 `bili_charge_archive_m1_test`、独立 Spring 端口 18082、Python 3.12；真实飞书只使用用户指定的群 ID 7（“临时通知”）

## 关键结果

| 场景 | 证据 | 结果 |
| --- | --- | --- |
| MSG-01 | `DeliveryServiceTest.routesDynamicAndCommentsByRoleAndKeepsSentTargetAfterChange`：动态概览和 UP 评论生成 ALL、UP 两目标，普通评论只生成 ALL | 通过 |
| MSG-02 | 同一测试在未发目标认领前插入专属路由，随后目标落到新群 | 通过 |
| MSG-03 | 全部目标完成后删除路由，再认领返回空；完成事件不重开 | 通过 |
| MSG-04 | ALL 已成功后改变路由，ALL 的原群保持不变，UP 未发目标跟随新群；群名快照保留 | 通过 |
| MSG-05 | `DeliveryServiceTest.opsErrorsArePerRoundHeartbeatIsHourlyAndCleanupPreservesPending`：同小时两次 STARTED 只产生一条心跳，两次相同 ERROR 产生两条运维事件；`ManagerTest` 验证子进程异常入队 | 通过 |
| MSG-06 | `retryLadderAndSharedGroupOrderingAcrossUps` 验证 5/10/15/30/60/60 秒及跨 UP 同群队头阻塞；独立 HTTP 链路中事件 402 首次报告 HTTP_500，第二次认领并确认成功，尝试次数为 2 | 通过 |
| MSG-07 | M3 的 `BatchHttpTest` 在真实 MySQL 临时 CHECK 故障下验证内容、进度、事件一同回滚，移除故障后原批次重试只保留一份；本阶段回归继续执行该测试 | 通过 |
| MSG-08 | 独立 Python 子进程对模拟 Webhook 收到成功响应后立即 `os._exit(23)`，未调用结果接口；事件 454 留在 SENDING、租约与未完成事件仍在。人为推进租约到期后，另一 worker 重新认领并确认，最终 SENT、尝试次数 2 | 通过；极端故障可能重复 |
| MSG-09 | `DeliveryServiceTest` 将完成事件时间推进 91 天后清理，完成事件及级联投递行被删，两个未完成运维事件保留；管理员投递记录支持状态筛选与前后分页 | 通过 |
| MSG-10 | `test_delivery.py` 验证图片上传失败只报告失败、不调用 Webhook；真实测试群对文字动态事件 403、图片动态事件 404、图片评论事件 405 均收到飞书 API 成功响应，数据库各目标均为 SENT、尝试次数 1 | 通过 |

## 真实联调边界

真实发送的三个事件只存在隔离库，群 Webhook 密文从开发库群 ID 7 复制到隔离库，不打印或持久化明文。图片取已公开可访问的 B 站 CDN 测试图片；飞书应用凭据只从被 Git 忽略的 `deploy/.env` 读取。飞书文本、图片上传和 Webhook 接口均返回成功，投递结果由内部接口写为 SENT。这里记录的是接口和数据库证据，群内最终显示可由群成员在飞书核对。

开发库原有 350 条专属动态待发事件未被本轮认领，监控容器保持关闭。开发库已应用 V5，内部锁表有 1 行；独立联调结束后，隔离库中的测试 UP、动态、事件与两条测试群配置已按精确 ID 清理，开发库群 ID 7 配置未改动。正式启动监控容器会发送现有的全部就绪事件，须先核对路由及积压量。

## 回归与运行状态

- Python 3.12：`PYTHONPATH=monitor; py -3.12 -m unittest discover -s monitor/tests -v`，21 项通过。
- Spring：JDK 21、真实 MySQL 8.4.11 隔离库执行 `mvn -q test`，共 36 项，35 项通过、失败/错误 0、跳过 1 项未给测试进程提供 OSS 配置的真实 Bucket 测试；`DeliveryServiceTest` 覆盖路由、租约、重试、跨 UP 顺序、运维心跳、清理和分页。
- 对活跃租约期间删除原路由的边界修复后，单独重跑 `DeliveryServiceTest` 7 项全部通过；其中新增用例确认发送中目标保留原群直到结果回报，已作废的历史事件永不认领。
- 前端：`npm run build` 类型检查与生产构建通过。
- `deploy-monitor` 镜像构建和 `bili_monitor.delivery` 包导入通过，但没有启动该容器；真实发送由只连接测试库的独立后端及受控 Python 调用完成，避免开发库积压通知外发。
- 本地开发页面 `/admin` 返回 200；登录后 `GET /api/admin/deliveries?upUid=550494308` 返回空投递目标列表，同时 UP 状态仍显示 350 条尚未认领的待发事件，符合两种计数的边界。匿名读取投递记录返回 401，公开 Nginx 访问 `/internal/deliveries/claim` 返回 404。
- 用当前代码重建本地 `spring-app`、`nginx` 镜像并启动后，MySQL、Spring、Nginx 三容器均 healthy，Spring 健康接口返回 UP，`/admin` 返回 200；开发库迁移版本为 V5，待发数量仍为 350，monitor 服务未运行。
