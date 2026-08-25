import assert from 'node:assert/strict'
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontDir = path.resolve(scriptDir, '..')
const sourceDist = path.resolve(process.argv[2] || path.join(frontDir, 'dist'))
const checker = path.join(scriptDir, 'check-pwa.mjs')
const testRoot = await mkdtemp(path.join(os.tmpdir(), 'shimmer-pwa-check-'))
const fixture = path.join(testRoot, 'dist')

function runCheck() {
  return spawnSync(process.execPath, [checker, fixture], {
    cwd: testRoot,
    encoding: 'utf8'
  })
}

try {
  const files = [
    'manifest.webmanifest',
    'sw.js',
    'offline.html',
    '.well-known/assetlinks.json',
    'icons/icon-192.png',
    'icons/icon-512.png',
    'icons/icon-maskable-512.png',
    'icons/apple-touch-icon.png',
    'icons/favicon-32.png'
  ]
  for (const file of files) {
    const target = path.join(fixture, file)
    await mkdir(path.dirname(target), { recursive: true })
    await copyFile(path.join(sourceDist, file), target)
  }

  const normal = runCheck()
  assert.equal(normal.status, 0, normal.stderr || normal.stdout)

  const assetLinksPath = path.join(fixture, '.well-known/assetlinks.json')
  const assetLinks = JSON.parse(await readFile(assetLinksPath, 'utf8'))
  assetLinks.push({
    relation: ['delegate_permission/common.handle_all_urls'],
    target: {
      namespace: 'android_app',
      package_name: 'attacker.example',
      sha256_cert_fingerprints: [
        '00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00'
      ]
    }
  })
  await writeFile(assetLinksPath, `${JSON.stringify(assetLinks, null, 2)}\n`)

  const appendedAuthorization = runCheck()
  assert.notEqual(appendedAuthorization.status, 0, 'an appended DAL authorization must fail the PWA check')
  assert.match(
    appendedAuthorization.stderr,
    /Digital Asset Links must contain exactly one authorization/,
    'the failure must identify the unexpected additional authorization'
  )
} finally {
  await rm(testRoot, { recursive: true, force: true })
}

console.log('[pwa-check-test] normal DAL passed and appended authorization was rejected')
