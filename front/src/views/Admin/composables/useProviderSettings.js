import { reactive, ref } from 'vue'
import { updateAdminApiSettings, testAdminApiSettings } from '@/api'

/** Owns provider drafts, masked-key hydration, saves and connection tests. */
export function useProviderSettings({ message, errorMsg, rankingForm, applyApiSettings }) {
  const testingProvider = ref("");

  const testResults = reactive({});

  const apiKeyHints = reactive({
    llm: "未配置",
    matchmaking: "未配置",
    research: "未配置",
    babeldoc: "未配置",
    zotero: "未配置",
  });

  const apiForm = reactive({
    llm: { baseUrl: "", model: "", apiKey: "", protocol: "auto" },
    babeldoc: { baseUrl: "", model: "", apiKey: "" },
    zotero: { baseUrl: "", userId: "", apiKey: "" },
    research: {
      baseUrl: "https://api.tavily.com/search",
      apiKey: "",
      maxSearches: 4,
    },
  });

  const providerCards = [
    {
      key: "llm",
      name: "LLM 通用模型",
      description: "翻译、GitHub 摘要与通用文本任务（演示生成不使用）",
    },
    {
      key: "research",
      name: "Tavily 联网研究与配图",
      description:
        "为 PPTX/HTML 提供网页研究及图片候选，开放素材源同时补充可复用图片",
    },
    { key: "babeldoc", name: "BabelDOC 翻译", description: "PDF 排版翻译链路" },
    { key: "zotero", name: "Zotero 文献库", description: "文献缓存与附件代理" },
  ];

  async function saveApiSettings() {
    try {
      applyApiSettings(
        (await updateAdminApiSettings({ ...apiForm, githubRanking: rankingForm }))
          .data,
      );
      message.success("API 配置已保存");
    } catch (e) {
      errorMsg.value = e.message || "API 配置保存失败";
    }
  }

  async function testApiConnection(provider) {
    testingProvider.value = provider;
    testResults[provider] = { type: "info", text: "正在测试…" };
    try {
      const result =
        (await testAdminApiSettings(provider, apiForm[provider])).data || {};
      testResults[provider] = {
        type: "success",
        text: result.message || "连接成功",
        latencyMs: result.latencyMs,
      };
    } catch (e) {
      testResults[provider] = { type: "error", text: e.message || "连接失败" };
    } finally {
      testingProvider.value = "";
    }
  }

  function applyProviderSettings(d = {}) {
    for (const k of ["llm", "research", "babeldoc", "zotero"])
      if (d[k]) {
        apiForm[k].baseUrl = d[k].baseUrl || "";
        if ("model" in apiForm[k]) apiForm[k].model = d[k].model || "";
        if (k === "zotero") apiForm.zotero.userId = d[k].userId || "";
        if (k === "research")
          apiForm.research.maxSearches = Number(d[k].maxSearches || 4);
        apiForm[k].apiKey = "";
        apiKeyHints[k] = d[k].apiKeyHint || "未配置";
      }
    if (d.llm?.protocol)
      apiForm.llm.protocol = String(d.llm.protocol).toLowerCase();
  }

  return { testingProvider, testResults, apiKeyHints, apiForm, providerCards, saveApiSettings, testApiConnection, applyProviderSettings };
}
