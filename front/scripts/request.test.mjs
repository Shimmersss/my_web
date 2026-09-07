import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

// Mirror Vite's environment replacement while testing the actual shared request code.
const source = (await readFile(new URL('../src/utils/request.js', import.meta.url), 'utf8')).replace('import.meta.env.VITE_API_BASE_URL', "''")
const { requestWithOptions } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

test('timeouts and caller cancellation abort fetch; HTTP status survives error normalization', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, originalError = console.error
  globalThis.localStorage = { getItem: () => 'test-csrf' }
  console.error = () => {}
  try {
    globalThis.fetch = (url, options) => new Promise((resolve, reject) => {
      const abort = () => reject(new DOMException('Aborted', 'AbortError'))
      if (options.signal.aborted) abort()
      else options.signal.addEventListener('abort', abort, { once: true })
    })
    await assert.rejects(requestWithOptions('/test', { timeoutMs: 5 }), { name: 'AbortError' })
    const controller = new AbortController()
    const pending = requestWithOptions('/test', { signal: controller.signal })
    controller.abort()
    await assert.rejects(pending, { name: 'AbortError' })
    globalThis.fetch = async (url, options) => {
      assert.equal(options.headers['X-CSRF-Token'], 'test-csrf')
      assert.equal(options.credentials, 'same-origin')
      return new Response(JSON.stringify({ message: 'Access expired' }), { status: 403 })
    }
    await assert.rejects(requestWithOptions('/test', { method: 'POST' }), { status: 403, message: 'Access expired' })
  } finally { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; console.error = originalError }
})
