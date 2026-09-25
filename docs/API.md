# 接口契约

- 状态：M1 已实现登录、会话、CSRF、统一错误与请求 ID；其余业务接口仍为目标契约
- 需求依据：[PRD](PRD.md)；事务与数据关系见[架构](ARCHITECTURE.md)、[数据库](DATABASE.md)
- Base path：公开 /api；仅 Docker 内网 /internal
- 所有 B 站 ID 为 JSON 字符串；时间为 UTC ISO 8601（例如 2026-09-25T08:00:00Z）；请求与响应为 UTF-8 JSON

## 1. 通用规则

除登录外，/api 均要求管理员会话。登录成功由服务器设置 Secure、HttpOnly、SameSite Cookie，并返回 CSRF token；POST、PUT、PATCH、DELETE 需在 X-CSRF-Token 中回传。/internal 不接受浏览器会话，要求私有网络及 X-Monitor-Token；Nginx 不转发 /internal。

正常响应以 data 包装；列表另带 page。错误响应固定为：

~~~json
{"code":"INVALID_ROUTE","message":"至少配置一个评论群","requestId":"..."}
~~~

常用 HTTP 状态：401 未登录、403 CSRF/内部令牌失败、404 资源不存在、409 状态冲突或群仍被引用、422 输入与来源校验失败、502 上游 B 站/飞书响应异常、503 本地依赖暂不可用。错误响应不得包含 Cookie、Webhook、AccessKey 或完整外部响应。

列表采用不透明 cursor 的稳定键集分页，固定 pageSize=20；不提供任意大小查询。列表返回 nextCursor、prevCursor、hasNext、hasPrev，前端显示“上一页/下一页”。动态按 (publishedAt, dynamicId) 倒序；根评论同样倒序；楼中楼正序。

## 2. 登录与内容

| 方法与路径 | 输入 | 输出与规则 |
| --- | --- | --- |
| POST /api/auth/login | username、password | 建立会话，返回 CSRF token；账号与密码哈希来自服务器环境变量 |
| GET /api/auth/me | 无 | 当前管理员标识、CSRF token |
| POST /api/auth/logout | 无 | 销毁会话 |
| GET /api/ups | 无 | 所有已配置 UP 的 UID、名称、头像、启停状态，供头像筛选 |
| GET /api/dynamics | 可选 upUid、cursor | 20 条动态卡片、已存评论数、图片状态与分页 cursor |
| GET /api/dynamics/{dynamicId} | 路径 ID | 动态详情及图片元数据；不返回来源私有图片直链 |
| GET /api/dynamics/{dynamicId}/comments | 可选 cursor | 20 条根评论，按最新在前 |
| GET /api/dynamics/{dynamicId}/comments/{rpid}/replies | 可选 cursor | 该根评论下 20 条楼中楼，按最早在前 |
| GET /api/media/dynamics/{dynamicId}/{position} | 0 起始图片顺序 | 登录后对 READY 图片 302 到 10 分钟 OSS 签名 GET URL |
| GET /api/media/comments/{dynamicId}/{rpid}/{position} | 同上 | 同上 |

内容 API 的图片元数据包含 position、status（PENDING/RETRY/READY/UNAVAILABLE）和本站媒体路径。READY 前前端显示占位提示；签名端点在未就绪时返回 409、确定不可用时返回 410。302 设置 Cache-Control: no-store；页面重新请求同一路径即可获取新签名。来源删除标记 sourceUnavailable 为真时仍返回已存内容。

动态列表响应示意：

~~~json
{
  "data":[
    {
      "dynamicId":"1234567890123456789",
      "upUid":"987654321",
      "upName":"示例UP",
      "title":"可选标题",
      "text":"示例文字",
      "publishedAt":"2026-09-25T08:00:00Z",
      "storedCommentCount":42,
      "sourceUnavailable":false,
      "images":[{"position":0,"status":"READY","url":"/api/media/dynamics/1234567890123456789/0"}]
    }
  ],
  "page":{"pageSize":20,"nextCursor":"opaque","prevCursor":null,"hasNext":true,"hasPrev":false}
}
~~~

## 3. 管理接口

| 方法与路径 | 行为与校验 |
| --- | --- |
| GET /api/admin/groups | 返回群 ID、名称和是否被引用；Webhook 只返回已配置标记，不返回明文 |
| POST /api/admin/groups | 新建群：name、webhook；名称唯一，校验飞书 URL 格式 |
| PATCH /api/admin/groups/{id} | 修改名称或 Webhook；仅提供新 Webhook 时替换密文 |
| DELETE /api/admin/groups/{id} | 若 UP 默认/运维或专属路由仍引用，返回 409 GROUP_IN_USE |
| POST /api/admin/ups/preview | 输入 UID 或空间 URL，从 B 站验证并返回 UID、名称、头像，不保存 |
| GET /api/admin/ups | UP 列表与状态摘要 |
| POST /api/admin/ups | 保存 UID、opsGroupId、defaultAllGroupId、defaultUpGroupId；运维群必填，默认两群至少一项，双群不得相同；新增默认启用 |
| PATCH /api/admin/ups/{uid} | 更新启停、运维群和默认路由，仍须满足非空与互异约束 |
| GET /api/admin/ups/{uid}/routes | 该 UP 的专属动态路由 |
| POST /api/admin/ups/{uid}/routes/preview | 输入动态 ID 或链接，校验作者、充电属性、文字/图片类型和评论目标，返回可保存资料 |
| PUT /api/admin/ups/{uid}/routes/{dynamicId} | 创建/修改专属路由：allGroupId、upGroupId 至少一项且不同 |
| DELETE /api/admin/ups/{uid}/routes/{dynamicId} | 删除专属路由；后续使用 UP 默认路由，已存内容与已发通知不删除 |
| GET /api/admin/ups/{uid}/status | 最近成功扫描、进程心跳、最近错误、待发/失败数量 |
| GET /api/admin/deliveries | 可选 upUid、status、cursor；展示待发/失败及近 90 天成功记录 |

群 ID 用数字型数据库 ID，B 站 ID 用字符串。Webhook 更新或路由变更后，尚未成功的目标在下次认领时使用最新配置；已成功目标不再发送。无手动重扫和重发接口。

M2 实现中，两个预览接口的请求体均为 `{ "input": "数字 ID 或链接" }`。群响应包含 `id/name/webhookConfigured/referenced/references`，其中 `references` 是被引用的位置说明，不含 Webhook。UP 列表含启停、默认群 ID 和状态摘要；状态接口单独返回扫描心跳、最近成功和错误时间、待发及失败重试数量。专属路由响应含动态 ID、UP UID 与两个可为空的群 ID。无效群配置与不合格动态返回 422，已有配置冲突或引用中的群删除返回 409；B 站预览暂不可用或无权访问时返回 502，未配置预览 Cookie 时返回 503。错误体仍使用本文件第 1 节的统一格式。

## 4. Python 内部接口

| 方法与路径 | 作用 |
| --- | --- |
| GET /internal/ups?enabled=true | 管理进程获取启用 UID；只返回必要的启停信息 |
| GET /internal/ups/{uid}/config | 子进程获取 UP 资料、最新默认路由、专属动态 ID 和扫描参数；不必返回 Webhook |
| POST /internal/ups/{uid}/batches | 提交一批动态、评论、图片元数据、扫描状态和候选通知；整批在一个 MySQL 事务内完成 |
| POST /internal/deliveries/claim | 按 upUid、workerId 认领一个可发送目标；服务端实时解析路由、设置租约，并返回仅供该子进程使用的 Webhook 与消息 |
| POST /internal/deliveries/{eventId}/{role}/result | 按 workerId/租约确认成功或失败；失败计算下次重试时间 |
| POST /internal/ups/{uid}/ops-events | 提交每小时心跳、每轮错误或进程故障事件 |
| POST /internal/ups/{uid}/worker-status | 更新进程心跳、扫描开始/成功/错误状态 |

批次请求的概念结构如下；所有评论合计建议不超过 100 条：

~~~json
{
  "items":[
    {
      "dynamic":{"dynamicId":"123","upUid":"456","title":null,"text":"...","publishedAt":"2026-09-25T08:00:00Z","commentOid":"789","commentType":11,"images":[]},
      "comments":[{"rpid":"101","rootRpid":null,"parentRpid":null,"authorMid":"202","text":"...","publishedAt":"2026-09-25T08:01:00Z","images":[]}],
      "scanState":{"state":{},"lastCompleteScanAt":null,"lastFullScanAt":null},
      "eventCandidates":[{"dedupeKey":"dynamic:123","type":"DYNAMIC","text":"...","imageSourceUrls":[]}]
    }
  ],
  "baselineCompletedDynamicIds":[]
}
~~~

Spring 按主键幂等 upsert 内容，仅对首次发现的动态/评论插入唯一事件键；更新扫描状态与入队在同一事务。首次基线分批时事件 ready_at 留空；最后一批将该动态的基线事件放行。请求失败可原样重试，不能先推进扫描状态。事件正文是生成时的快照，未发目标按**当前**路由选群。

认领只在短数据库事务内完成；飞书 HTTP 调用不在事务中。认领结果包含 leaseUntil；结果接口只接受当前租约持有者，过期或重复确认返回 409。发送成功但确认前崩溃仍可能重复，符合 PRD 的优先不漏发规则。

## 5. 来源与文档边界

B 站动态及评论接口见原始需求文档中的社区接口参考；正式实现时需用实际账号验证字段和鉴权。固定动态必须核实详情返回 ID、作者 UID 及评论 oid/type，不允许仅凭链接字符串入库。公开接口的具体路径和 JSON 是本实施设计提出的契约，项目目前还没有相应代码。
