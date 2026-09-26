<script setup lang="ts">
import { onUnmounted, ref } from "vue";
defineProps<{ images: { position: number; status: string; url: string }[] }>();
const failed = ref<Record<number, boolean>>({});
const timers = new Map<number, ReturnType<typeof setTimeout>>();
function imageFailed(position: number) {
  failed.value[position] = true;
  if (timers.has(position)) clearTimeout(timers.get(position));
  timers.set(position, setTimeout(() => {
    failed.value[position] = false;
    timers.delete(position);
  }, 30000));
}
onUnmounted(() => { for (const timer of timers.values()) clearTimeout(timer); });
</script>

<template>
  <div v-if="images.length" class="media-grid">
    <div v-for="image in images" :key="image.position" class="media-tile">
      <img v-if="image.status === 'READY' && !failed[image.position]" :src="image.url" alt="动态或评论图片" loading="lazy" @error="imageFailed(image.position)" />
      <span v-else>{{ image.status === 'UNAVAILABLE' ? '图片来源不可用' : image.status === 'READY' ? '图片暂不可用' : '图片处理中' }}</span>
    </div>
  </div>
</template>
