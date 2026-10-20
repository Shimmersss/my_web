// Entry point for 月下占卜 requests. `VITE_TAROT_MOCK=1 npm run dev` swaps in an in-browser
// mock of the agreed contract so the page can be built before the backend lands; production
// builds always use the live API (import.meta.env.DEV is statically false there).
import * as live from "@/api";

const useMock = import.meta.env.DEV && import.meta.env.VITE_TAROT_MOCK === "1";
let mockModule = null;

async function api() {
  if (!useMock) return live;
  mockModule ||= await import("./tarotReadingMock.js");
  return mockModule;
}

export const tarotApiMode = useMock ? "mock" : "live";

export const getMatchmakingStatus = async () => (await api()).getMatchmakingStatus();
export const getMatchmakingTasks = async () => (await api()).getMatchmakingTasks();
export const getMatchmakingTask = async (taskId, options) => (await api()).getMatchmakingTask(taskId, options);
export const getMatchmakingReportArchive = async (page, pageSize, owner) => (await api()).getMatchmakingReportArchive(page, pageSize, owner);
export const getMatchmakingReport = async reportId => (await api()).getMatchmakingReport(reportId);
export const deleteMatchmakingReport = async reportId => (await api()).deleteMatchmakingReport(reportId);
export const deleteMatchmakingData = async () => (await api()).deleteMatchmakingData();
export const createTarotDraw = async payload => (await api()).createTarotDraw(payload);
export const pickTarotCards = async (drawId, positions) => (await api()).pickTarotCards(drawId, positions);
export const getCurrentMatchmakingTarot = async () => (await api()).getCurrentMatchmakingTarot();
export const createTarotReadingReport = async drawId => (await api()).createTarotReadingReport(drawId);
