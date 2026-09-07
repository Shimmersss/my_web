/** One request at a time; stopping also invalidates responses from uncooperative transports. */
export function createTaskPoller({ fetchTask, onTask, onError, schedule = setTimeout, cancel = clearTimeout }) {
  let generation = 0
  let timer = null
  let controller = null
  function stop() {
    generation += 1
    if (timer !== null) cancel(timer)
    timer = null
    controller?.abort()
    controller = null
  }
  function start(id) {
    stop()
    const current = generation
    let failures = 0
    async function tick() {
      timer = null
      if (generation !== current) return
      controller = new AbortController()
      let delay = 2000
      try {
        const response = await fetchTask(id, { signal: controller.signal })
        if (generation !== current) return
        failures = 0
        onError('')
        await onTask(response.data)
        if (generation !== current || ['done', 'error'].includes(response.data.status) && !response.data.compensationPending) return
      } catch (error) {
        if (generation !== current) return
        if ([401, 403, 404].includes(error.status)) {
          onError(error.status === 404 ? '任务已删除或过期。' : '访问已失效，请重新登录或兑换内测码。')
          return
        }
        failures += 1
        delay = Math.min(30000, 2000 * 2 ** Math.min(failures, 4))
        onError('连接中断，正在自动重新连接；后台任务仍会继续。')
      }
      if (generation === current) timer = schedule(tick, delay)
    }
    timer = schedule(tick, 0)
  }
  return { start, stop }
}
