import test from 'node:test'
import assert from 'node:assert/strict'
import { createTaskPoller } from '../src/utils/taskPoller.js'

function setup(fetchTask) {
  const pending = new Map(), states = [], errors = [], delays = []
  let id = 0
  const poller = createTaskPoller({ fetchTask, onTask: task => states.push(task), onError: text => errors.push(text),
    schedule(fn, ms) { delays.push(ms); pending.set(++id, fn); return id }, cancel(id) { pending.delete(id) } })
  return { poller, pending, states, errors, delays, async tick() { const [id, fn] = pending.entries().next().value; pending.delete(id); await fn() } }
}
test('slow requests do not overlap and stopped responses cannot replace a new task', async () => {
  let resolve, signal
  const s = setup((id, options) => { signal = options.signal; return new Promise(r => { resolve = r }) })
  s.poller.start('old')
  const running = s.tick()
  assert.equal(s.pending.size, 0)
  s.poller.start('new')
  assert.equal(signal.aborted, true)
  resolve({ data: { id: 'old', status: 'done' } })
  await running
  assert.equal(s.states.length, 0)
  assert.equal(s.pending.size, 1)
})
test('network failures back off and recover without manual reload', async () => {
  let calls = 0
  const s = setup(async () => { if (++calls < 3) throw new Error('offline'); return { data: { status: 'done' } } })
  s.poller.start('task')
  await s.tick(); await s.tick(); await s.tick()
  assert.deepEqual(s.delays, [0, 4000, 8000])
  assert.equal(s.errors.at(-1), '')
  assert.equal(s.pending.size, 0)
})
test('pending compensation remains observable; access errors stop retries', async () => {
  let calls = 0
  const s = setup(async () => { if (++calls === 1) return { data: { status: 'error', compensationPending: true } }; throw Object.assign(new Error(), { status: 403 }) })
  s.poller.start('task'); await s.tick(); await s.tick()
  assert.equal(s.pending.size, 0)
  assert.match(s.errors.at(-1), /访问已失效/)
})

test('all task terminal states wait for durable refunds before stopping', async () => {
  for (const status of ['completed', 'failed', 'cancelled', 'error', 'done']) {
    let calls = 0
    const s = setup(async () => ({ data: { status, refundPending: ++calls === 1 } }))
    s.poller.start('task'); await s.tick()
    assert.equal(s.pending.size, 1)
    await s.tick(); assert.equal(s.pending.size, 0)
  }
})
test('async task handlers can reject stale side effects after switching tasks', async () => {
  let finish, context
  const pending = []
  const poller = createTaskPoller({ fetchTask: async () => ({ data: { status: 'completed' } }),
    onTask: async (_, current) => { context = current; await new Promise(resolve => { finish = resolve }) },
    schedule: fn => { pending.push(fn); return pending.length }, cancel: () => {} })
  poller.start('old'); const running = pending.shift()()
  await Promise.resolve(); await Promise.resolve()
  poller.start('new'); assert.equal(context.isCurrent(), false); assert.equal(context.signal.aborted, true)
  finish(); await running
})
