import { reactive, ref } from 'vue'
import { adjustUserCredits, createInviteCode, createMatchmakingTrialCode, deleteInviteCode, updateAdminUserStatus, updateInviteStatus, updateMatchmakingTrialCode } from '@/api'
import { trialCodeStatus as resolveTrialCodeStatus } from '@/utils/matchmakingTrial'

/** Account balances, invitation lifecycle and trial-code presentation. */
export function useAccountManagement({ message, errorMsg, loadDashboard }) {
  const users = ref([]);

  const invites = ref([]);

  const trialCodes = ref([]);

  const trialExpiresAt = ref(defaultTrialExpiry());

  const createdTrialCode = ref("");

  const trialCodeModalOpen = ref(false);

  const transactions = ref([]);

  const adjustForms = reactive({});

  const inviteForm = reactive({
    code: "",
    credits: 10,
    maxUses: 1,
    expiresAt: "",
  });

  async function createInvite() {
    try {
      const code = (await createInviteCode(inviteForm)).data.code;
      await navigator.clipboard?.writeText(code).catch(() => {});
      message.success(`邀请码 ${code} 已生成`);
      inviteForm.code = "";
      inviteForm.expiresAt = "";
      await loadDashboard();
    } catch (e) {
      errorMsg.value = e.message || "邀请码生成失败";
    }
  }

  async function createTrialCode() {
    try {
      const expiresAt = trialExpiresAt.value ? new Date(trialExpiresAt.value).toISOString() : "";
      const result = (await createMatchmakingTrialCode(expiresAt)).data || {};
      createdTrialCode.value = result.code || "";
      await navigator.clipboard?.writeText(createdTrialCode.value).catch(() => {});
      trialCodeModalOpen.value = true;
      await loadDashboard();
    } catch (e) {
      errorMsg.value = e.message || "婚恋内测邀请码生成失败";
    }
  }

  async function copyCreatedTrialCode() {
    await copyTrialCode(createdTrialCode.value);
  }

  async function copyTrialCode(code) {
    if (!code) return;
    await navigator.clipboard?.writeText(code).catch(() => {});
    message.success("邀请码已复制");
  }

  async function toggleTrialCode(code) {
    try {
      const expiresAt = code.expiresAt ? new Date(code.expiresAt).toISOString() : "";
      const result = await updateMatchmakingTrialCode(code.id, !code.enabled, expiresAt);
      trialCodes.value = result.data || [];
    } catch (e) {
      errorMsg.value = e.message || "婚恋内测邀请码状态更新失败";
    }
  }

  function trialCodeStatus(code) { return resolveTrialCodeStatus(code); }

  function defaultTrialExpiry() {
    const date = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    const offset = date.getTimezoneOffset() * 60 * 1000;
    return new Date(date.getTime() - offset).toISOString().slice(0, 16);
  }

  async function toggleInvite(i) {
    try {
      await updateInviteStatus(
        i.id,
        !i.enabled,
        i.expires_at ? new Date(i.expires_at).toISOString() : "",
      );
      await loadDashboard();
    } catch (e) {
      errorMsg.value = e.message || "邀请码状态更新失败";
    }
  }

  async function deleteInvite(i) {
    if (!window.confirm(`确定删除未使用的邀请码 ${i.code} 吗？此操作不可恢复。`))
      return;
    try {
      await deleteInviteCode(i.id);
      message.success("邀请码已删除");
      await loadDashboard();
    } catch (e) {
      errorMsg.value = e.message || "邀请码删除失败";
    }
  }

  async function toggleUser(u) {
    try {
      await updateAdminUserStatus(u.id, !u.enabled);
      await loadDashboard();
    } catch (e) {
      errorMsg.value = e.message || "用户状态更新失败";
    }
  }

  async function adjustCredits(id) {
    const amount = Number(adjustForms[id] || 0);
    if (!amount) return;
    try {
      await adjustUserCredits({ userId: id, amount, note: "root 后台调整" });
      adjustForms[id] = 0;
      await loadDashboard();
    } catch (e) {
      errorMsg.value = e.message || "额度调整失败";
    }
  }

  function inviteStatus(i) {
    if (!i.enabled) return "已撤销";
    if (i.used_count >= i.max_uses) return "已用尽";
    if (i.expires_at && new Date(i.expires_at) <= new Date()) return "已过期";
    return "可使用";
  }
  return { users, invites, trialCodes, trialExpiresAt, createdTrialCode, trialCodeModalOpen, transactions, adjustForms, inviteForm, createInvite, createTrialCode, copyCreatedTrialCode, copyTrialCode, toggleTrialCode, trialCodeStatus, defaultTrialExpiry, toggleInvite, deleteInvite, toggleUser, adjustCredits, inviteStatus };
}
