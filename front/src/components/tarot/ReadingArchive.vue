<template>
  <section class="reading-archive tarot-panel" aria-labelledby="reading-archive-title">
    <header class="archive-header">
      <div>
        <p class="tarot-eyebrow">YOUR ARCHIVE</p>
        <h2 id="reading-archive-title">{{ isRoot ? "全站占卜记录" : "占卜记录" }}</h2>
        <small v-if="archive.total">共 {{ archive.total }} 份</small>
      </div>
      <div class="archive-actions">
        <button type="button" class="tarot-text-button" :aria-expanded="expanded" @click="$emit('toggle')">{{ expanded ? "收起" : "展开全部" }}</button>
        <button v-if="canDeleteAll" type="button" class="tarot-text-button danger" @click="$emit('delete-all')">删除全部记录</button>
      </div>
    </header>

    <form v-if="expanded && isRoot" class="archive-filter" @submit.prevent="$emit('filter', ownerDraft)">
      <label>按用户名筛选<input v-model.trim="ownerDraft" maxlength="80" placeholder="输入完整或部分用户名" /></label>
      <button type="submit" class="tarot-button quiet">筛选</button>
      <button v-if="ownerDraft" type="button" class="tarot-button quiet" @click="ownerDraft = ''; $emit('filter', '')">清除</button>
    </form>

    <p v-if="!archive.total" class="empty-copy">还没有占卜记录。完成一次 AI 深度解读后，它会安静地留在这里。</p>
    <ul v-else class="archive-rows">
      <li v-for="item in visibleItems" :key="item.id">
        <router-link class="archive-row" :to="reportLink(item)">
          <span v-if="isRoot" class="row-owner">{{ item.ownerLabel || "未知用户" }}</span>
          <span class="row-title">{{ isTarot(item) ? (item.title || "占卜报告") : "旧版报告（只读）" }}</span>
          <span class="row-type">{{ isTarot(item) ? (item.subtitle || item.personalityLabel || "月下占卜") : "保存期内可查看" }}</span>
          <span class="row-date">{{ formatDate(item.createdAt) }}</span>
          <span aria-hidden="true">→</span>
        </router-link>
      </li>
    </ul>

    <nav v-if="expanded && archive.totalPages > 1" class="archive-pagination" aria-label="占卜记录分页">
      <button type="button" class="tarot-button quiet" :disabled="archive.page <= 1" @click="$emit('page', archive.page - 1)">上一页</button>
      <span>第 {{ archive.page }} / {{ archive.totalPages }} 页</span>
      <button type="button" class="tarot-button quiet" :disabled="archive.page >= archive.totalPages" @click="$emit('page', archive.page + 1)">下一页</button>
    </nav>
  </section>
</template>

<script setup>
import { computed, ref } from "vue";
import { TAROT_READING_VERSION } from "@/utils/tarotReading";

const props = defineProps({
  archive: { type: Object, required: true },
  expanded: { type: Boolean, default: false },
  isRoot: { type: Boolean, default: false },
  canDeleteAll: { type: Boolean, default: false },
});
defineEmits(["toggle", "filter", "page", "delete-all"]);

const ownerDraft = ref("");
const visibleItems = computed(() => (props.expanded ? props.archive.items : props.archive.items.slice(0, 3)));
// Legacy rows may carry no report_version at all, so anything that is not a tarot reading is legacy.
const isTarot = item => item.reportVersion === TAROT_READING_VERSION;
const reportLink = item => (isTarot(item) ? `/tarot-reading/report/${item.id}` : `/tarot-reading/legacy/${item.id}`);
const formatDate = value => (value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—");
</script>

<style scoped>
.archive-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; }
.archive-header h2 { margin: 0; color: #fff4dc; font: 500 26px/1.3 "Moonlit Serif", serif; }
.archive-header small { color: #9ea4b8; }
.archive-actions { display: flex; gap: 12px; align-items: center; }
.archive-filter { display: flex; gap: 9px; align-items: end; margin-top: 18px; padding: 14px; border-radius: 12px; background: #ffffff08; }
.archive-filter label { display: grid; flex: 1; gap: 6px; color: #9ea4b8; font-size: 12px; }
.archive-filter input { min-width: 0; padding: 10px 12px; border: 1px solid #b8975c88; border-radius: 9px; background: #0b1124cc; color: #fff8e8; font: inherit; }
.empty-copy { margin: 18px 0 0; color: #9ea4b8; line-height: 1.8; }
.archive-rows { display: grid; gap: 8px; margin: 18px 0 0; padding: 0; list-style: none; }
.archive-row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
  padding: 14px 16px;
  border: 1px solid #8d7a5855;
  border-radius: 12px;
  background: #ffffff07;
  color: #f4ead4;
  text-decoration: none;
  transition: border-color .2s ease, transform .2s ease;
}
.archive-row:hover { border-color: #e0be78; transform: translateX(3px); }
.archive-row:focus-visible { outline: 2px solid #f1d18b; outline-offset: 2px; }
.row-owner, .row-type, .row-date { color: #9ea4b8; font-size: 12px; }
.row-title { font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-date { white-space: nowrap; }
.archive-row:has(.row-owner) { grid-template-columns: minmax(80px, .7fr) minmax(0, 1.4fr) minmax(0, 1fr) auto auto; }
.archive-pagination { display: flex; justify-content: center; align-items: center; gap: 14px; margin-top: 20px; color: #9ea4b8; font-size: 13px; }

@media (max-width: 760px) {
  .archive-header { flex-direction: column; align-items: flex-start; }
  .archive-row, .archive-row:has(.row-owner) { grid-template-columns: minmax(0, 1fr) auto; gap: 4px 10px; }
  .archive-row > span:not(:last-child) { grid-column: 1; }
  .archive-row > span:last-child { grid-column: 2; grid-row: 1 / span 4; }
  .archive-filter { flex-wrap: wrap; }
  .archive-filter label { flex-basis: 100%; }
}
@media (prefers-reduced-motion: reduce) { .archive-row { transition: none; } }
</style>
