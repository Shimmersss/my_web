export const defaultGithubProjects = [
  {
    repo: 'vuejs/core',
    highlight: 'Vue 3 核心框架，当前站点前端技术栈的基础。',
    category: 'Frontend',
    featured: true
  },
  {
    repo: 'vitejs/vite',
    highlight: '极速前端构建工具，本项目开发环境由 Vite 驱动。',
    category: 'Tooling',
    featured: true
  },
  {
    repo: 'tusen-ai/naive-ui',
    highlight: 'Vue 3 组件库，负责项目里的主要交互控件与信息展示。',
    category: 'UI',
    featured: true
  }
]

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
