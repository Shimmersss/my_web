<template>
  <div class="tarot-page">
    <CelestialBackdrop />
    <div class="tarot-shell">
      <p v-if="error" class="tarot-notice error" role="alert">
        {{ error }} <router-link class="tarot-text-button" to="/tarot-reading">返回月下占卜</router-link>
      </p>
      <p v-else-if="loading" class="tarot-notice" role="status">正在展开占卜报告…</p>
      <TarotReadingReport v-else-if="report" :report="report" @delete="deleteReport" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { deleteMatchmakingReport, getMatchmakingReport } from "@/api/tarotReading";
import { useAuthStore } from "@/stores/auth";
import { trialDeleteWarning } from "@/utils/matchmakingTrial";
import { TAROT_READING_VERSION } from "@/utils/tarotReading";
import CelestialBackdrop from "@/components/tarot/CelestialBackdrop.vue";
import TarotReadingReport from "./TarotReadingReport.vue";
import "@/styles/tarot-reading.scss";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(true), error = ref(""), report = ref(null);

onMounted(async () => {
  try {
    const response = await getMatchmakingReport(route.params.reportId);
    // Reports saved before 月下占卜 keep their own read-only viewer until they expire.
    if (response.data?.reportVersion !== TAROT_READING_VERSION) {
      await router.replace(`/tarot-reading/legacy/${route.params.reportId}`);
      return;
    }
    report.value = response.data;
  } catch (e) {
    error.value = e.message || "报告加载失败";
  } finally {
    loading.value = false;
  }
});

async function deleteReport() {
  if (!window.confirm(trialDeleteWarning(auth.isMatchmakingTrial))) return;
  try {
    await deleteMatchmakingReport(route.params.reportId);
    await router.push("/tarot-reading");
  } catch (e) {
    error.value = e.message || "删除失败";
  }
}
</script>
