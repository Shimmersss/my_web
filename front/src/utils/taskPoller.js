/** One request at a time; stopping also invalidates responses from uncooperative transports. */
export function createTaskPoller({
  fetchTask, onTask, onError = () => {},
  isTerminal = task => ['done', 'completed', 'failed', 'error', 'cancelled'].includes(task.status),
  isCompensating = task => task.compensationPending || task.refundPending,
  interval = 2000, schedule = setTimeout, cancel = clearTimeout,
}) {
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
      const context = { signal: controller.signal, isCurrent: () => generation === current }
      let delay = interval
      try {
        const response = await fetchTask(id, { signal: context.signal })
        if (!context.isCurrent()) return
        failures = 0
        onError('')
        await onTask(response.data, context)
        if (!context.isCurrent() || isTerminal(response.data) && !isCompensating(response.data)) return
      } catch (error) {
        if (!context.isCurrent()) return
        if ([401, 403, 404].includes(error.status)) {
          onError(error.status === 404 ? '任务已删除或过期。' : '访问已失效，请重新登录或兑换内测码。')
          return
        }
        failures += 1
        delay = Math.min(30000, interval * 2 ** Math.min(failures, 4))
        onError('连接中断，正在自动重新连接；后台任务仍会继续。')
      }
      if (context.isCurrent()) timer = schedule(tick, delay)
    }
    timer = schedule(tick, 0)
  }
  return { start, stop }
}
