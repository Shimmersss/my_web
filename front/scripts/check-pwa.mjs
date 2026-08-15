import assert from 'node:assert/strict'
import { readFile, stat } from 'node:fs/promises'
import path from 'node:path'

const root = path.resolve(process.argv[2] || 'dist')
const read = file => readFile(path.join(root, file))

function pngDimensions(buffer) {
  assert.equal(buffer.subarray(1, 4).toString('ascii'), 'PNG', 'file must be a PNG')
  return [buffer.readUInt32BE(16), buffer.readUInt32BE(20)]
}

const manifest = JSON.parse(await read('manifest.webmanifest'))
assert.equal(manifest.id, '/')
assert.equal(manifest.name, '闪闪小站')
assert.equal(manifest.start_url, '/')
assert.equal(manifest.scope, '/')
assert.equal(manifest.display, 'standalone')
assert.equal(manifest.theme_color, '#b83126')
assert.equal(manifest.background_color, '#fbf9f3')

for (const [file, size] of [
  ['icons/icon-192.png', 192],
  ['icons/icon-512.png', 512],
  ['icons/icon-maskable-512.png', 512],
  ['icons/apple-touch-icon.png', 180],
  ['icons/favicon-32.png', 32]
]) {
  const dimensions = pngDimensions(await read(file))
  assert.deepEqual(dimensions, [size, size], `${file} must be ${size}x${size}`)
}

const worker = (await read('sw.js')).toString('utf8')
assert.match(worker, /request\.mode !== 'navigate'/)
assert.match(worker, /fetch\(request\)\.catch/)
for (const forbidden of ['/api', '/pptd-editor', 'event-stream']) {
  assert.ok(!worker.includes(forbidden), `Service Worker must not cache ${forbidden}`)
}

const assetLinks = JSON.parse(await read('.well-known/assetlinks.json'))
assert.equal(assetLinks[0].target.package_name, 'help.shimmer.app')
assert.match(assetLinks[0].target.sha256_cert_fingerprints[0], /^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/)
assert.ok((await stat(path.join(root, 'offline.html'))).size > 300)

console.log('[pwa-check] manifest, icons, offline policy and Digital Asset Links verified')
