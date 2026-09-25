<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

type Group = {
  id: number;
  name: string;
  referenced: boolean;
  references: string[];
};
type Up = {
  uid: string;
  displayName: string;
  avatarUrl: string;
  enabled: boolean;
  opsGroupId: number;
  defaultAllGroupId: number | null;
  defaultUpGroupId: number | null;
  lastScanSucceededAt: string | null;
  lastScanError: string | null;
  pendingCount: number;
};
type Status = {
  workerHeartbeatAt: string | null;
  lastScanSucceededAt: string | null;
  lastScanError: string | null;
  pendingCount: number;
  failedCount: number;
};
type Route = {
  dynamicId: string;
  allGroupId: number | null;
  upGroupId: number | null;
};
type UpPreview = { uid: string; displayName: string; avatarUrl: string };
type RoutePreview = {
  dynamicId: string;
  upName: string;
  title: string;
  type: string;
  commentOid: string;
};

const loggedIn = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const username = ref(""),
  password = ref(""),
  csrf = ref("");
const tab = ref<"ups" | "groups">("ups");
const groups = ref<Group[]>([]),
  ups = ref<Up[]>([]),
  routes = ref<Route[]>([]);
const selectedUid = ref(""),
  status = ref<Status | null>(null);
const groupId = ref<number | null>(null),
  groupName = ref(""),
  groupWebhook = ref("");
const upInput = ref(""),
  upPreview = ref<UpPreview | null>(null);
const opsGroupId = ref<number | null>(null),
  defaultAllGroupId = ref<number | null>(null),
  defaultUpGroupId = ref<number | null>(null);
const routeInput = ref(""),
  routePreview = ref<RoutePreview | null>(null);
const allGroupId = ref<number | null>(null),
  upGroupId = ref<number | null>(null);
const selectedUp = computed(() =>
  ups.value.find((up) => up.uid === selectedUid.value),
);
const groupNameOf = (id: number | null) =>
  id == null
    ? "未设置"
    : (groups.value.find((group) => group.id === id)?.name ?? `群 ID ${id}`);
const dateOf = (time?: string | null) =>
  time ? new Date(time).toLocaleString("zh-CN") : "暂无";

async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(method === "GET" || !csrf.value
        ? {}
        : { "X-CSRF-Token": csrf.value }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = (await response.json().catch(() => ({}))) as {
    data?: T;
    message?: string;
  };
  if (!response.ok) {
    if (response.status === 401) loggedIn.value = false;
    throw new Error(result.message || `请求失败 (${response.status})`);
  }
  return result.data as T;
}
async function run(action: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await action();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "操作失败";
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  const [g, u] = await Promise.all([
    api<Group[]>("/api/admin/groups"),
    api<Up[]>("/api/admin/ups"),
  ]);
  groups.value = g;
  ups.value = u;
  if (selectedUid.value) await loadSelected();
}
async function loadSelected() {
  if (!selectedUid.value) return;
  const root = `/api/admin/ups/${selectedUid.value}`;
  const [s, r] = await Promise.all([
    api<Status>(`${root}/status`),
    api<Route[]>(`${root}/routes`),
  ]);
  status.value = s;
  routes.value = r;
  if (selectedUp.value) {
    opsGroupId.value = selectedUp.value.opsGroupId;
    defaultAllGroupId.value = selectedUp.value.defaultAllGroupId;
    defaultUpGroupId.value = selectedUp.value.defaultUpGroupId;
  }
}
async function login() {
  await run(async () => {
    const me = await api<{ username: string; csrfToken: string }>(
      "/api/auth/login",
      "POST",
      { username: username.value, password: password.value },
    );
    username.value = me.username;
    csrf.value = me.csrfToken;
    password.value = "";
    loggedIn.value = true;
    await refresh();
  });
}
async function logout() {
  await run(async () => {
    await api("/api/auth/logout", "POST");
    loggedIn.value = false;
    csrf.value = "";
    groups.value = [];
    ups.value = [];
    selectedUid.value = "";
  });
}
function editGroup(group?: Group) {
  groupId.value = group?.id ?? null;
  groupName.value = group?.name ?? "";
  groupWebhook.value = "";
  tab.value = "groups";
  window.scrollTo({ top: 0, behavior: "smooth" });
}
async function saveGroup() {
  await run(async () => {
    if (groupId.value == null && !groupWebhook.value.trim())
      throw new Error("新建群需要 Webhook");
    await api(
      groupId.value == null
        ? "/api/admin/groups"
        : `/api/admin/groups/${groupId.value}`,
      groupId.value == null ? "POST" : "PATCH",
      {
        name: groupName.value,
        ...(groupWebhook.value.trim()
          ? { webhook: groupWebhook.value.trim() }
          : {}),
      },
    );
    editGroup();
    await refresh();
    notice.value = "群配置已保存";
  });
}
async function deleteGroup(group: Group) {
  if (!confirm(`删除群「${group.name}」？`)) return;
  await run(async () => {
    await api(`/api/admin/groups/${group.id}`, "DELETE");
    await refresh();
    notice.value = "群已删除";
  });
}
async function previewUp() {
  await run(async () => {
    upPreview.value = await api<UpPreview>("/api/admin/ups/preview", "POST", {
      input: upInput.value,
    });
  });
}
async function createUp() {
  await run(async () => {
    if (!upPreview.value) throw new Error("请先预览 UP");
    const up = await api<Up>("/api/admin/ups", "POST", {
      uid: upPreview.value.uid,
      opsGroupId: opsGroupId.value,
      defaultAllGroupId: defaultAllGroupId.value,
      defaultUpGroupId: defaultUpGroupId.value,
    });
    upPreview.value = null;
    upInput.value = "";
    await refresh();
    selectedUid.value = up.uid;
    await loadSelected();
    notice.value = "UP 已保存并启用；监控服务运行时会自动扫描";
  });
}
async function updateUp(change: Record<string, unknown>) {
  await run(async () => {
    await api(`/api/admin/ups/${selectedUid.value}`, "PATCH", change);
    await refresh();
    notice.value = "UP 配置已保存";
  });
}
async function previewRoute() {
  await run(async () => {
    routePreview.value = await api<RoutePreview>(
      `/api/admin/ups/${selectedUid.value}/routes/preview`,
      "POST",
      { input: routeInput.value },
    );
  });
}
async function saveRoute() {
  await run(async () => {
    if (!routePreview.value) throw new Error("请先预览动态");
    await api(
      `/api/admin/ups/${selectedUid.value}/routes/${routePreview.value.dynamicId}`,
      "PUT",
      { allGroupId: allGroupId.value, upGroupId: upGroupId.value },
    );
    routePreview.value = null;
    routeInput.value = "";
    await loadSelected();
    notice.value = "专属路由已保存";
  });
}
async function deleteRoute(route: Route) {
  if (!confirm(`删除动态 ${route.dynamicId} 的专属路由？`)) return;
  await run(async () => {
    await api(
      `/api/admin/ups/${selectedUid.value}/routes/${route.dynamicId}`,
      "DELETE",
    );
    await loadSelected();
    notice.value = "专属路由已删除；后续使用默认路由";
  });
}
onMounted(async () => {
  try {
    const me = await api<{ username: string; csrfToken: string }>(
      "/api/auth/me",
    );
    username.value = me.username;
    csrf.value = me.csrfToken;
    loggedIn.value = true;
    await refresh();
  } catch {
    loggedIn.value = false;
  }
});
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">充</span>
        <div><strong>充电动态</strong><small>管理台</small></div>
      </div>
      <button v-if="loggedIn" class="text-button" @click="logout">
        退出登录
      </button>
    </header>
    <main v-if="!loggedIn" class="card login-panel">
      <span class="eyebrow">管理员登录</span>
      <h1>欢迎回来</h1>
      <p class="muted">登录后管理 UP、飞书群与动态专属路由。</p>
      <form class="form-stack" @submit.prevent="login">
        <label
          >用户名<input
            v-model.trim="username"
            autocomplete="username"
            required /></label
        ><label
          >密码<input
            v-model="password"
            type="password"
            autocomplete="current-password"
            required /></label
        ><button class="primary" :disabled="busy">登录</button>
      </form>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    </main>
    <main v-else class="content">
      <div class="page-heading">
        <div>
          <span class="eyebrow">配置中心</span>
          <h1>{{ tab === "ups" ? "UP 管理" : "飞书群管理" }}</h1>
        </div>
        <span class="user-chip">{{ username }}</span>
      </div>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
      <template v-if="tab === 'groups'">
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>{{ groupId == null ? "新建飞书群" : "编辑飞书群" }}</h2>
              <p>Webhook 保存后只显示已配置状态，不回显明文。</p>
            </div>
            <button
              v-if="groupId != null"
              class="text-button"
              @click="editGroup()"
            >
              取消
            </button>
          </div>
          <form class="form-stack" @submit.prevent="saveGroup">
            <label
              >群名称<input
                v-model.trim="groupName"
                maxlength="100"
                required /></label
            ><label
              >飞书 Webhook <small v-if="groupId != null">留空保持原值</small
              ><input
                v-model.trim="groupWebhook"
                type="password"
                autocomplete="off"
                placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/…"
                :required="groupId == null" /></label
            ><button class="primary" :disabled="busy">保存群配置</button>
          </form>
        </section>
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>已配置的群</h2>
              <p>{{ groups.length }} 个群，可被多个 UP 复用。</p>
            </div>
            <button class="text-button" @click="run(refresh)">刷新</button>
          </div>
          <div v-if="!groups.length" class="empty">还没有群。请先添加群。</div>
          <div v-for="group in groups" :key="group.id" class="list-row">
            <div class="row-main">
              <strong>{{ group.name }}</strong
              ><small>群 ID {{ group.id }} · Webhook 已加密保存</small
              ><small v-if="group.references.length"
                >引用：{{ group.references.join("、") }}</small
              >
            </div>
            <div class="row-actions">
              <button @click="editGroup(group)">编辑</button
              ><button
                class="danger"
                :disabled="busy"
                @click="deleteGroup(group)"
              >
                删除
              </button>
            </div>
          </div>
        </section>
      </template>
      <template v-else-if="!selectedUp">
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>添加 UP</h2>
              <p>输入 UID 或空间链接，预览后配置通知群。</p>
            </div>
          </div>
          <div class="inline-input">
            <input
              v-model.trim="upInput"
              placeholder="UID 或 https://space.bilibili.com/…"
              @input="upPreview = null"
            /><button :disabled="busy || !upInput" @click="previewUp">
              预览
            </button>
          </div>
          <div v-if="upPreview" class="preview">
            <img
              v-if="upPreview.avatarUrl"
              :src="upPreview.avatarUrl"
              referrerpolicy="no-referrer"
              alt=""
            />
            <div>
              <strong>{{ upPreview.displayName }}</strong
              ><small>UID {{ upPreview.uid }}</small>
            </div>
          </div>
          <form v-if="upPreview" class="form-stack" @submit.prevent="createUp">
            <label
              >运维通知群<select v-model="opsGroupId" required>
                <option :value="null">请选择</option>
                <option v-for="g in groups" :key="g.id" :value="g.id">
                  {{ g.name }}
                </option>
              </select></label
            >
            <div class="two-fields">
              <label
                >默认全员评论群<select v-model="defaultAllGroupId">
                  <option :value="null">不配置</option>
                  <option v-for="g in groups" :key="g.id" :value="g.id">
                    {{ g.name }}
                  </option>
                </select></label
              ><label
                >默认 UP 评论群<select v-model="defaultUpGroupId">
                  <option :value="null">不配置</option>
                  <option v-for="g in groups" :key="g.id" :value="g.id">
                    {{ g.name }}
                  </option>
                </select></label
              >
            </div>
            <p class="hint">两个默认群至少选一个；都选时必须不同。</p>
            <button class="primary" :disabled="busy">添加并启用</button>
          </form>
        </section>
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>已管理的 UP</h2>
              <p>查看状态和专属动态。</p>
            </div>
            <button class="text-button" @click="run(refresh)">刷新</button>
          </div>
          <div v-if="!ups.length" class="empty">
            还没有 UP。请先配置飞书群。
          </div>
          <button
            v-for="up in ups"
            :key="up.uid"
            class="up-row"
            @click="
              selectedUid = up.uid;
              run(loadSelected);
            "
          >
            <img
              v-if="up.avatarUrl"
              :src="up.avatarUrl"
              referrerpolicy="no-referrer"
              alt=""
            /><span
              class="row-main"
              ><strong>{{ up.displayName }}</strong
              ><small
                >UID {{ up.uid }} · 待发送 {{ up.pendingCount }}</small
              ><small>最近扫描：{{ dateOf(up.lastScanSucceededAt) }}</small
              ><small>最近错误：{{ up.lastScanError || "暂无" }}</small
              ></span
            ><span :class="['badge', up.enabled ? 'on' : 'off']">{{
              up.enabled ? "已启用" : "已停用"
            }}</span
            ><span class="chevron">›</span>
          </button>
        </section>
      </template>
      <template v-else>
        <button class="back-link" @click="selectedUid = ''">
          ‹ 返回 UP 列表
        </button>
        <section class="card">
          <div class="up-title">
            <img
              v-if="selectedUp.avatarUrl"
              :src="selectedUp.avatarUrl"
              referrerpolicy="no-referrer"
              alt=""
            />
            <div>
              <span class="eyebrow">UID {{ selectedUp.uid }}</span>
              <h2>{{ selectedUp.displayName }}</h2>
            </div>
            <span :class="['badge', selectedUp.enabled ? 'on' : 'off']">{{
              selectedUp.enabled ? "已启用" : "已停用"
            }}</span>
          </div>
          <div class="stat-grid">
            <div>
              <small>最近成功扫描</small
              ><strong>{{ dateOf(status?.lastScanSucceededAt) }}</strong>
            </div>
            <div>
              <small>扫描器心跳</small
              ><strong>{{ dateOf(status?.workerHeartbeatAt) }}</strong>
            </div>
            <div>
              <small>待发送</small
              ><strong>{{ status?.pendingCount ?? 0 }}</strong>
            </div>
            <div>
              <small>失败重试</small
              ><strong>{{ status?.failedCount ?? 0 }}</strong>
            </div>
          </div>
          <p :class="['alert', status?.lastScanError ? 'error' : 'quiet']">
            最近错误：{{ status?.lastScanError || "暂无" }}
          </p>
          <div class="row-actions">
            <button @click="run(loadSelected)">刷新状态</button
            ><button
              :class="selectedUp.enabled ? 'danger' : 'primary'"
              :disabled="busy"
              @click="updateUp({ enabled: !selectedUp.enabled })"
            >
              {{ selectedUp.enabled ? "停用 UP" : "重新启用" }}
            </button>
          </div>
          <p class="hint">监控服务运行时会扫描已启用的 UP；停用后保留已有内容。</p>
        </section>
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>默认通知路由</h2>
              <p>运维群必选；默认评论群至少选一个。</p>
            </div>
          </div>
          <form
            class="form-stack"
            @submit.prevent="
              updateUp({ opsGroupId, defaultAllGroupId, defaultUpGroupId })
            "
          >
            <label
              >运维通知群<select v-model="opsGroupId" required>
                <option :value="null">请选择</option>
                <option v-for="g in groups" :key="g.id" :value="g.id">
                  {{ g.name }}
                </option>
              </select></label
            >
            <div class="two-fields">
              <label
                >默认全员评论群<select v-model="defaultAllGroupId">
                  <option :value="null">不配置</option>
                  <option v-for="g in groups" :key="g.id" :value="g.id">
                    {{ g.name }}
                  </option>
                </select></label
              ><label
                >默认 UP 评论群<select v-model="defaultUpGroupId">
                  <option :value="null">不配置</option>
                  <option v-for="g in groups" :key="g.id" :value="g.id">
                    {{ g.name }}
                  </option>
                </select></label
              >
            </div>
            <button class="primary" :disabled="busy">保存默认路由</button>
          </form>
        </section>
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>专属动态路由</h2>
              <p>覆盖默认群；删除后恢复默认路由。</p>
            </div>
            <span class="count">{{ routes.length }} 条</span>
          </div>
          <div v-if="!routes.length" class="empty">尚未配置专属动态。</div>
          <div v-for="route in routes" :key="route.dynamicId" class="list-row">
            <div class="row-main">
              <strong>动态 {{ route.dynamicId }}</strong
              ><small
                >全员：{{ groupNameOf(route.allGroupId) }} · UP：{{
                  groupNameOf(route.upGroupId)
                }}</small
              >
            </div>
            <div class="row-actions">
              <button
                @click="
                  routeInput = route.dynamicId;
                  routePreview = null;
                  allGroupId = route.allGroupId;
                  upGroupId = route.upGroupId;
                "
              >
                编辑</button
              ><button
                class="danger"
                :disabled="busy"
                @click="deleteRoute(route)"
              >
                删除
              </button>
            </div>
          </div>
          <div class="divider"></div>
          <h3>添加或编辑专属动态</h3>
          <p class="hint">
            预览核对作者、充电属性、内容类型和评论目标；保存时再次核对。
          </p>
          <div class="inline-input">
            <input
              v-model.trim="routeInput"
              placeholder="动态 ID 或链接"
              @input="routePreview = null"
            /><button :disabled="busy || !routeInput" @click="previewRoute">
              预览
            </button>
          </div>
          <div v-if="routePreview" class="route-preview">
            <strong>{{
              routePreview.title || `动态 ${routePreview.dynamicId}`
            }}</strong
            ><small
              >{{ routePreview.upName }} · {{ routePreview.type }} · 评论目标
              {{ routePreview.commentOid }}</small
            >
          </div>
          <form
            v-if="routePreview"
            class="form-stack"
            @submit.prevent="saveRoute"
          >
            <div class="two-fields">
              <label
                >全员评论群<select v-model="allGroupId">
                  <option :value="null">不配置</option>
                  <option v-for="g in groups" :key="g.id" :value="g.id">
                    {{ g.name }}
                  </option>
                </select></label
              ><label
                >UP 评论群<select v-model="upGroupId">
                  <option :value="null">不配置</option>
                  <option v-for="g in groups" :key="g.id" :value="g.id">
                    {{ g.name }}
                  </option>
                </select></label
              >
            </div>
            <button class="primary" :disabled="busy">保存专属路由</button>
          </form>
        </section>
      </template>
    </main>
    <nav v-if="loggedIn" class="bottom-nav" aria-label="管理导航">
      <button
        :class="{ active: tab === 'ups' }"
        @click="
          tab = 'ups';
          selectedUid = '';
        "
      >
        ◎<small>UP 管理</small></button
      ><button
        :class="{ active: tab === 'groups' }"
        @click="
          tab = 'groups';
          selectedUid = '';
        "
      >
        ▣<small>飞书群</small>
      </button>
    </nav>
  </div>
</template>
