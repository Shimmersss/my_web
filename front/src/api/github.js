import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


// ==================== GitHub 开源项目 API ====================

export function getGithubProjects() {
  return get("/github-projects");
}

export function getGithubRankings() {
  return get("/github-projects/rankings");
}
export function getHomeDailyStatus() {
  return get("/home/daily-status");
}

export function loginGithubProjectsAdmin(key) {
  return post("/github-projects/login", { key });
}

export function saveGithubProjects(projects, adminKey) {
  return requestWithOptions("/github-projects", {
    method: "PUT",
    headers: {
      "X-Admin-Key": adminKey,
    },
    body: JSON.stringify(projects),
  });
}

export async function getGithubProjectReadme(fullName) {
  const res = await fetch(`/api/github-projects/${fullName}/readme`);
  if (!res.ok) throw new Error(await res.text());
  return res.text();
}
