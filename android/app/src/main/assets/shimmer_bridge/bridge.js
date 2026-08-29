(() => {
  'use strict'
  window.addEventListener('message', event => {
    if (event.source !== window || event.origin !== 'https://shimmer.help') return
    const envelope = event.data
    if (!envelope || envelope.__shimmerGeckoNative !== true) return
    const payload = envelope.payload
    if (!payload || typeof payload !== 'object') return
    browser.runtime.sendNativeMessage('shimmer', payload).catch(() => {})
  })
})()
