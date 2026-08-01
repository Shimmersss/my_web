// 不预置项目；GitHub 展示列表由后台配置决定，接口失败时也保持为空。
export const defaultGithubProjects = []

export const githubProjectFallback = {
  description: 'GitHub 开源项目',
  language: 'Unknown',
  stargazers_count: 0,
  forks_count: 0,
  open_issues_count: 0,
  topics: [],
  license: null,
  homepage: '',
  pushed_at: ''
}
