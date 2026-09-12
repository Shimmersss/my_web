import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { ref, effectScope } from 'vue'

const source = (await readFile(new URL('../src/views/PptGenerate/composables/usePptPreview.js', import.meta.url), 'utf8'))
  .replace("from 'vue'", `from '${new URL('../node_modules/vue/index.mjs', import.meta.url).href}'`)
  .replace("import { getPptPreview, getPptPreviewImage, getPptHtmlPreview } from '@/api'", `const getPptPreview = (...args) => globalThis.__previewTransport.metadata(...args);\nconst getPptPreviewImage = (...args) => globalThis.__previewTransport.image(...args);\nconst getPptHtmlPreview = (...args) => globalThis.__previewTransport.html(...args);`)
const { usePptPreview } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

test('switching tasks releases both partial and late preview URLs', async () => {
  let releaseLate, firstReady
  const first = new Promise(resolve => { firstReady = resolve })
  globalThis.__previewTransport = {
    metadata: async () => ({ data: { slides: [{ imageFile: 'first' }, { imageFile: 'late' }] } }),
    image: async (_, file) => file === 'first' ? (firstReady(), 'blob:first') : new Promise(resolve => { releaseLate = resolve }),
  }
  const revoked = [], original = URL.revokeObjectURL
  URL.revokeObjectURL = url => revoked.push(url)
  const scope = effectScope()
  try {
    const taskId = ref('old')
    const preview = scope.run(() => usePptPreview({ taskId, taskAccessToken: ref(''), activeTask: ref({ status: 'completed' }), outputFormatLabel: () => 'PPTX' }))
    const loading = preview.loadPreview()
    await first; await Promise.resolve()
    taskId.value = 'new'; preview.clearPreview()
    assert.deepEqual(revoked, ['blob:first'])
    releaseLate('blob:late'); await loading
    assert.deepEqual(revoked.sort(), ['blob:first', 'blob:late'])
    assert.deepEqual(preview.previewImageUrls.value, {})
    assert.equal(preview.previewLoading.value, false)
  } finally { scope.stop(); URL.revokeObjectURL = original; delete globalThis.__previewTransport }
})
