import assert from 'node:assert/strict'
import fs from 'node:fs'
import {
  isAndroidMobileWeb,
  isShimmerAndroid,
  requestAndroidDownload,
  requestAndroidTextSave
} from '../src/utils/androidBridge.js'

const messages = []
const appRuntime = {
  navigator: { userAgent: 'Android WebView ShimmerAndroid/0.2' },
  location: { href: 'https://shimmer.help/image-generate' },
  ShimmerNative: { postMessage: value => messages.push(JSON.parse(value)) }
}

assert.equal(isShimmerAndroid(appRuntime.navigator.userAgent), true)
assert.equal(isShimmerAndroid('Mozilla/5.0 Chrome/140'), false)
assert.equal(isAndroidMobileWeb('Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36'), true)
assert.equal(isAndroidMobileWeb(appRuntime.navigator.userAgent), false)
assert.equal(isAndroidMobileWeb('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile'), false)
assert.equal(requestAndroidDownload({
  url: '/api/image-generate/result/task-1',
  filename: 'result.png',
  mimeType: 'image/png'
}, appRuntime), true)
assert.deepEqual(messages[0], {
  protocolVersion: 1,
  type: 'download',
  url: 'https://shimmer.help/api/image-generate/result/task-1',
  filename: 'result.png',
  mimeType: 'image/png'
})
assert.equal(requestAndroidDownload({
  url: 'https://example.com/file',
  filename: 'blocked.bin'
}, appRuntime), false)
assert.equal(messages.length, 1)
assert.equal(requestAndroidTextSave({
  text: '@article{example}',
  filename: 'example.bib'
}, appRuntime), true)
assert.equal(messages[1].type, 'saveText')
assert.equal(messages[1].protocolVersion, 1)
assert.equal(messages[1].filename, 'example.bib')

const geckoMessages = []
const geckoRuntime = {
  navigator: { userAgent: 'Mozilla/5.0 Gecko/142 Firefox/142 ShimmerAndroid/0.3' },
  location: { href: 'https://shimmer.help/', origin: 'https://shimmer.help' },
  postMessage: (value, target) => geckoMessages.push({ value, target })
}
assert.equal(requestAndroidTextSave({ text: 'hello', filename: 'report.txt' }, geckoRuntime), true)
assert.equal(geckoMessages[0].target, 'https://shimmer.help')
assert.equal(geckoMessages[0].value.__shimmerGeckoNative, true)
assert.equal(geckoMessages[0].value.payload.type, 'saveText')

const appHeader = fs.readFileSync(new URL('../src/components/common/AppHeader.vue', import.meta.url), 'utf8')
assert.match(appHeader, /<form class="auth-form" autocomplete="on" @submit\.prevent="submitAuth">/)
assert.match(appHeader, /name: 'username'[\s\S]*autocomplete: 'username'/)
assert.match(appHeader, /name: 'password'[\s\S]*'current-password'[\s\S]*'new-password'/)
assert.doesNotMatch(appHeader, /authForm\.password[^\n]*show-password-on/)

console.log('[android-container-check] Gecko bridge, mobile Web, and login form focus gates verified')
