<script setup lang="ts">
import { ref } from "vue";
defineProps<{ images: { position: number; status: string; url: string }[] }>();
const failed = ref<Record<number, boolean>>({});
</script>

<template>
  <div v-if="images.length" class="media-grid">
    <div v-for="image in images" :key="image.position" class="media-tile">
      <img v-if="image.status === 'READY' && !failed[image.position]" :src="image.url" alt="动态或评论图片" loading="lazy" @error="failed[image.position] = true" />
      <span v-else>{{ image.status === 'UNAVAILABLE' ? '图片来源不可用' : image.status === 'READY' ? '图片暂不可用' : '图片处理中' }}</span>
    </div>
  </div>
</template>
