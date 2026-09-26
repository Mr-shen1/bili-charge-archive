<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import MediaImages from "./MediaImages.vue";

type Page = { pageSize: number; nextCursor: string | null; prevCursor: string | null; hasNext: boolean; hasPrev: boolean };
type PageResult<T> = { data: T[]; page: Page };
type Up = { uid: string; displayName: string; avatarUrl: string | null; enabled: boolean };
type Image = { position: number; status: "PENDING" | "RETRY" | "READY" | "UNAVAILABLE"; url: string };
type Dynamic = { dynamicId: string; upUid: string; upName: string; upAvatarUrl: string | null;
  upEnabled: boolean; title: string | null; text: string; publishedAt: string;
  storedCommentCount: number; sourceUnavailable: boolean; images: Image[] };
type Comment = { dynamicId: string; rpid: string; rootRpid: string | null; parentRpid: string | null;
  authorMid: string; authorName: string; authorAvatarUrl: string | null; authorLevel: number;
  text: string; publishedAt: string; likeCount: number; replyCount: number;
  storedReplyCount: number; isUp: boolean; sourceUnavailable: boolean; images: Image[] };
type Thread = { open: boolean; data: PageResult<Comment> | null; loading: boolean; error: string; cursor: string | null };

const props = defineProps<{ route: string }>();
const ups = ref<Up[]>([]);
const list = ref<PageResult<Dynamic> | null>(null);
const detail = ref<Dynamic | null>(null);
const roots = ref<PageResult<Comment> | null>(null);
const threads = ref<Record<string, Thread>>({});
const loading = ref(false), error = ref("");
const selectedUp = ref(""), listKey = ref(""), listScroll = ref(0);
const detailId = ref(""), rootCursor = ref<string | null>(null);

async function request<T>(path: string): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin" });
  const result = await response.json() as { data?: T; message?: string };
  if (response.status === 401) window.dispatchEvent(new Event("auth-expired"));
  if (!response.ok) throw new Error(result.message || `请求失败 (${response.status})`);
  return result as T;
}
function go(path: string) {
  window.history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
function date(value: string) { return new Date(value).toLocaleString("zh-CN"); }
function query(upUid: string, cursor: string | null) {
  const params = new URLSearchParams();
  if (upUid) params.set("upUid", upUid);
  if (cursor) params.set("cursor", cursor);
  return `/dynamics${params.size ? `?${params}` : ""}`;
}
function pagePath(base: string, cursor: string | null) {
  return cursor ? `${base}?cursor=${encodeURIComponent(cursor)}` : base;
}
function listPage(cursor: string | null) { go(query(selectedUp.value, cursor)); }
function selectUp(uid: string) { go(query(uid, null)); }
function open(dynamicId: string) {
  listScroll.value = window.scrollY;
  go(`/dynamics/${dynamicId}`);
  window.scrollTo(0, 0);
}
function back() {
  if (listKey.value) window.history.back();
  else go("/dynamics");
}
async function loadList() {
  const url = new URL(window.location.href);
  selectedUp.value = url.searchParams.get("upUid") || "";
  const cursor = url.searchParams.get("cursor");
  const key = `${selectedUp.value}|${cursor || ""}`;
  if (list.value && listKey.value === key) {
    await nextTick();
    window.scrollTo(0, listScroll.value);
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const params = new URLSearchParams();
    if (selectedUp.value) params.set("upUid", selectedUp.value);
    if (cursor) params.set("cursor", cursor);
    list.value = await request<PageResult<Dynamic>>(`/api/dynamics${params.size ? `?${params}` : ""}`);
    listKey.value = key;
    await nextTick();
    window.scrollTo(0, 0);
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "加载失败"; }
  finally { loading.value = false; }
}
async function loadRoots(cursor: string | null) {
  rootCursor.value = cursor;
  roots.value = await request<PageResult<Comment>>(pagePath(`/api/dynamics/${detailId.value}/comments`, cursor));
  threads.value = {};
}
async function loadDetail(id: string) {
  if (detailId.value === id && detail.value) return;
  loading.value = true;
  error.value = "";
  detail.value = null;
  roots.value = null;
  detailId.value = id;
  try {
    const [d, c] = await Promise.all([
      request<{ data: Dynamic }>(`/api/dynamics/${id}`),
      request<PageResult<Comment>>(`/api/dynamics/${id}/comments`),
    ]);
    if (detailId.value !== id) return;
    detail.value = d.data;
    roots.value = c;
    rootCursor.value = null;
    threads.value = {};
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "加载失败"; }
  finally { loading.value = false; }
}
async function changeRoots(cursor: string | null) {
  error.value = "";
  try { await loadRoots(cursor); window.scrollTo(0, 0); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "评论加载失败"; }
}
async function toggleReplies(root: Comment) {
  const current = threads.value[root.rpid];
  if (current?.open) { current.open = false; return; }
  if (current?.data) { current.open = true; return; }
  threads.value[root.rpid] = { open: true, data: null, loading: true, error: "", cursor: null };
  await changeReplies(root.rpid, null);
}
async function changeReplies(rpid: string, cursor: string | null) {
  const thread = threads.value[rpid];
  thread.loading = true;
  thread.error = "";
  thread.cursor = cursor;
  try {
    thread.data = await request<PageResult<Comment>>(pagePath(
      `/api/dynamics/${detailId.value}/comments/${rpid}/replies`, cursor));
  } catch (cause) { thread.error = cause instanceof Error ? cause.message : "回复加载失败"; }
  finally { thread.loading = false; }
}
async function routeChanged() {
  const match = props.route.match(/^\/dynamics\/([1-9][0-9]*)$/);
  if (match) await loadDetail(match[1]);
  else await loadList();
}
onMounted(async () => {
  try { ups.value = (await request<{ data: Up[] }>("/api/ups")).data; }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "UP 加载失败"; }
  await routeChanged();
});
watch(() => props.route, routeChanged);
let imageRefresh: ReturnType<typeof setInterval> | undefined;
let refreshing = false;
function pending(images: Image[]) { return images.some(image => image.status === "PENDING" || image.status === "RETRY"); }
async function refreshImages() {
  if (refreshing || loading.value) return;
  const detailRoute = props.route.match(/^\/dynamics\/([1-9][0-9]*)$/);
  const hasPending = detailRoute
    ? !!detail.value && (pending(detail.value.images) || (roots.value?.data.some(row => pending(row.images)) ?? false)
      || Object.values(threads.value).some(thread => thread.open && thread.data?.data.some(row => pending(row.images))))
    : (list.value?.data.some(row => pending(row.images)) ?? false);
  if (!hasPending) return;
  refreshing = true;
  try {
    if (detailRoute) {
      const id = detailRoute[1];
      const currentDetail = await request<{ data: Dynamic }>(`/api/dynamics/${id}`);
      const currentRoots = await request<PageResult<Comment>>(pagePath(`/api/dynamics/${id}/comments`, rootCursor.value));
      if (props.route !== `/dynamics/${id}`) return;
      detail.value = currentDetail.data;
      roots.value = currentRoots;
      for (const [rpid, thread] of Object.entries(threads.value)) {
        if (thread.open && thread.data) {
          const updated = await request<PageResult<Comment>>(pagePath(`/api/dynamics/${id}/comments/${rpid}/replies`, thread.cursor));
          if (props.route === `/dynamics/${id}` && threads.value[rpid] === thread) thread.data = updated;
        }
      }
    } else {
      const route = window.location.pathname + window.location.search;
      const updated = await request<PageResult<Dynamic>>(`/api${route}`);
      if (window.location.pathname + window.location.search === route) list.value = updated;
    }
  } catch { /* The saved text stays visible while a later refresh retries. */ }
  finally { refreshing = false; }
}
imageRefresh = setInterval(refreshImages, 20000);
onUnmounted(() => { if (imageRefresh) clearInterval(imageRefresh); });
</script>

<template>
  <main class="reader">
    <template v-if="!detailId || !route.match(/^\/dynamics\//)">
      <div class="reader-heading"><span class="eyebrow">充电内容</span><h1>动态</h1><p>已保存的充电专属动态与评论</p></div>
      <div class="up-filter" aria-label="筛选 UP">
        <button :class="{ active: !selectedUp }" @click="selectUp('')">全部</button>
        <button v-for="up in ups" :key="up.uid" :class="{ active: selectedUp === up.uid }" @click="selectUp(up.uid)">
          <img v-if="up.avatarUrl" :src="up.avatarUrl" referrerpolicy="no-referrer" alt="" />{{ up.displayName }}
        </button>
      </div>
      <p v-if="loading" class="empty">正在加载…</p>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <p v-if="!loading && list && !list.data.length" class="empty">暂无已保存的动态</p>
      <button v-for="item in list?.data || []" :key="item.dynamicId" class="dynamic-card" @click="open(item.dynamicId)">
        <div class="reader-author"><img v-if="item.upAvatarUrl" :src="item.upAvatarUrl" referrerpolicy="no-referrer" alt="" />
          <div><strong>{{ item.upName }}</strong><small>{{ date(item.publishedAt) }}</small></div>
          <span v-if="!item.upEnabled" class="reader-tag">UP 已停用</span></div>
        <strong v-if="item.title" class="dynamic-title">{{ item.title }}</strong>
        <p class="reader-text clamp">{{ item.text }}</p>
        <MediaImages :images="item.images" />
        <div class="reader-meta"><span>本站评论 {{ item.storedCommentCount }} 条</span><span v-if="item.sourceUnavailable" class="unavailable">来源不可用 · 已存内容保留</span></div>
      </button>
      <div v-if="list" class="reader-pager"><button :disabled="!list.page.hasPrev" @click="listPage(list.page.prevCursor)">上一页</button><button :disabled="!list.page.hasNext" @click="listPage(list.page.nextCursor)">下一页</button></div>
    </template>
    <template v-else>
      <button class="reader-back" @click="back">‹ 返回动态列表</button>
      <p v-if="loading" class="empty">正在加载…</p>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <article v-if="detail" class="dynamic-card detail-card">
        <div class="reader-author"><img v-if="detail.upAvatarUrl" :src="detail.upAvatarUrl" referrerpolicy="no-referrer" alt="" />
          <div><strong>{{ detail.upName }}</strong><small>{{ date(detail.publishedAt) }}</small></div>
          <span v-if="!detail.upEnabled" class="reader-tag">UP 已停用</span></div>
        <h1 v-if="detail.title" class="dynamic-title">{{ detail.title }}</h1>
        <p class="reader-text">{{ detail.text }}</p><MediaImages :images="detail.images" />
        <p v-if="detail.sourceUnavailable" class="unavailable">来源不可用，已保存内容仍可查看</p>
        <div class="reader-meta">本站评论 {{ detail.storedCommentCount }} 条</div>
      </article>
      <section v-if="detail" class="comments-panel"><h2>评论</h2>
        <p v-if="roots && !roots.data.length" class="empty">暂无已保存的评论</p>
        <div v-for="root in roots?.data || []" :key="root.rpid" class="comment-card">
          <div class="reader-author"><img v-if="root.authorAvatarUrl" :src="root.authorAvatarUrl" referrerpolicy="no-referrer" alt="" />
            <div><strong>{{ root.authorName }} <span v-if="root.isUp" class="up-label">UP</span></strong><small>{{ date(root.publishedAt) }}</small></div></div>
          <p class="reader-text">{{ root.text }}</p><MediaImages :images="root.images" />
          <p v-if="root.sourceUnavailable" class="unavailable">来源不可用，已保存内容仍可查看</p>
          <div class="reader-meta"><span>赞 {{ root.likeCount }}</span><button v-if="root.storedReplyCount" @click="toggleReplies(root)">{{ threads[root.rpid]?.open ? '收起回复' : `查看 ${root.storedReplyCount} 条回复` }}</button></div>
          <div v-if="threads[root.rpid]?.open" class="replies"><p v-if="threads[root.rpid]?.loading">正在加载回复…</p><p v-if="threads[root.rpid]?.error" class="alert error">{{ threads[root.rpid].error }}</p>
            <div v-for="reply in threads[root.rpid]?.data?.data || []" :key="reply.rpid" class="reply-card">
              <div class="reader-author"><img v-if="reply.authorAvatarUrl" :src="reply.authorAvatarUrl" referrerpolicy="no-referrer" alt="" /><div><strong>{{ reply.authorName }} <span v-if="reply.isUp" class="up-label">UP</span></strong><small>{{ date(reply.publishedAt) }}</small></div></div>
              <p class="reader-text">{{ reply.text }}</p><MediaImages :images="reply.images" /><p v-if="reply.sourceUnavailable" class="unavailable">来源不可用</p>
            </div>
            <div v-if="threads[root.rpid]?.data" class="reader-pager"><button :disabled="!threads[root.rpid].data?.page.hasPrev" @click="changeReplies(root.rpid, threads[root.rpid].data!.page.prevCursor)">上一页</button><button :disabled="!threads[root.rpid].data?.page.hasNext" @click="changeReplies(root.rpid, threads[root.rpid].data!.page.nextCursor)">下一页</button></div>
          </div>
        </div>
        <div v-if="roots" class="reader-pager"><button :disabled="!roots.page.hasPrev" @click="changeRoots(roots.page.prevCursor)">上一页</button><button :disabled="!roots.page.hasNext" @click="changeRoots(roots.page.nextCursor)">下一页</button></div>
      </section>
    </template>
  </main>
</template>
