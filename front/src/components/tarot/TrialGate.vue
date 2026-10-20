<template>
  <section class="trial-gate tarot-panel" aria-labelledby="trial-gate-title">
    <p class="tarot-eyebrow">BEFORE THE RITUAL</p>
    <h2 id="trial-gate-title">登录后开始占卜</h2>
    <p>洗牌、选牌与翻牌都会记在你的账号下；刷新或换设备也能继续这次牌阵。即时牌义免费，AI 深度解读按次计费。</p>
    <div class="gate-actions">
      <button class="tarot-button primary" type="button" @click="$emit('login')">登录 / 注册</button>
    </div>
    <details class="trial-code">
      <summary>我有内测邀请码</summary>
      <form @submit.prevent="$emit('redeem', code)">
        <label class="sr-only" for="tarot-trial-code">内测邀请码</label>
        <input id="tarot-trial-code" v-model.trim="code" autocomplete="off" spellcheck="false" placeholder="MM-…" />
        <button class="tarot-button quiet" :disabled="redeeming || !code">{{ redeeming ? "正在验证…" : "使用邀请码" }}</button>
      </form>
      <small>邀请码已停止发放；仍在有效期内的邀请码可兑换一次免费的 AI 深度解读。</small>
    </details>
    <p v-if="error" class="gate-error" role="alert">{{ error }}</p>
  </section>
</template>

<script setup>
import { ref } from "vue";

defineProps({ redeeming: { type: Boolean, default: false }, error: { type: String, default: "" } });
defineEmits(["login", "redeem"]);
const code = ref("");
</script>

<style scoped>
.trial-gate h2 { margin: 0 0 10px; color: #fff4dc; font: 500 26px/1.3 "Moonlit Serif", serif; }
.trial-gate > p:not(.tarot-eyebrow) { margin: 0; color: #c7cbd8; line-height: 1.8; }
.gate-actions { margin-top: 18px; }
.trial-code { margin-top: 18px; color: #c7cbd8; font-size: 13px; }
.trial-code summary { width: fit-content; color: #e7c886; cursor: pointer; }
.trial-code form { display: flex; gap: 9px; margin: 12px 0 8px; }
.trial-code input { flex: 1; min-width: 0; padding: 10px 12px; border: 1px solid #b8975c88; border-radius: 9px; background: #0b1124cc; color: #fff8e8; font: inherit; }
.trial-code small { color: #9ea4b8; line-height: 1.6; }
.gate-error { margin: 14px 0 0; color: #f2b9a8; }
@media (max-width: 420px) { .trial-code form { flex-direction: column; } }
</style>
