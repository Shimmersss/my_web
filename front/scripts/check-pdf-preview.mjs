import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { parse as parseJavaScript } from '@babel/parser'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { createPdfRenderCoordinator } from '../src/utils/pdfRenderCoordinator.js'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontDir = path.resolve(scriptDir, '..')
const componentPath = path.join(frontDir, 'src/components/PdfCanvasPreview.vue')

function cancellationError() {
  const error = new Error('cancelled')
  error.name = 'RenderingCancelledException'
  return error
}

function deferredTask() {
  let resolve
  let reject
  const task = {
    cancelCount: 0,
    promise: new Promise((resolvePromise, rejectPromise) => {
      resolve = resolvePromise
      reject = rejectPromise
    }),
    cancel() {
      task.cancelCount += 1
    }
  }
  return { task, resolve, reject }
}

async function checkCoordinatorBehavior() {
  const coordinator = createPdfRenderCoordinator()
  const first = deferredTask()
  const firstToken = await coordinator.prepare()
  assert.equal(coordinator.attach(firstToken, first.task), true)
  const firstWait = coordinator.wait(firstToken, first.task)

  let secondPrepared = false
  const secondPrepare = coordinator.prepare().then(token => {
    secondPrepared = true
    return token
  })
  await Promise.resolve()
  assert.equal(first.task.cancelCount, 1, 'the next render must cancel the active task')
  assert.equal(secondPrepared, false, 'the next render must wait for cancellation to settle')

  first.reject(cancellationError())
  const secondToken = await secondPrepare
  await firstWait
  assert.equal(coordinator.isCurrent(firstToken), false, 'superseded render tokens must become stale')
  assert.equal(coordinator.isCurrent(secondToken), true)

  const second = deferredTask()
  assert.equal(coordinator.attach(secondToken, second.task), true)
  await coordinator.wait(firstToken, first.task)

  const thirdPrepare = coordinator.prepare()
  await Promise.resolve()
  assert.equal(second.task.cancelCount, 1, 'settling an old task must not clear the current task')
  second.reject(cancellationError())
  await thirdPrepare
}

async function checkLegacyImports() {
  const source = fs.readFileSync(componentPath, 'utf8')
  const { descriptor, errors } = parseSfc(source, { filename: componentPath })
  assert.deepEqual(errors, [], 'PdfCanvasPreview.vue must parse as a Vue SFC')
  assert.ok(descriptor.scriptSetup, 'PdfCanvasPreview.vue must contain script setup')

  const program = parseJavaScript(descriptor.scriptSetup.content, { sourceType: 'module' }).program
  const specifiers = program.body
    .filter(node => node.type === 'ImportDeclaration')
    .map(node => node.source.value)
  assert.ok(specifiers.includes('pdfjs-dist/legacy/build/pdf.mjs'), 'PDF.js main module must use the legacy build')
  assert.ok(specifiers.includes('pdfjs-dist/legacy/build/pdf.worker.min.mjs?worker'), 'PDF.js worker must use the matching legacy build')
  assert.equal(specifiers.includes('pdfjs-dist'), false, 'the modern PDF.js entry must not be imported')
}

function checkBuiltWorker(distDir) {
  const assetsDir = path.join(distDir, 'assets')
  const workers = fs.readdirSync(assetsDir).filter(name => name.startsWith('pdf.worker') && name.endsWith('.js'))
  assert.equal(workers.length, 1, 'production build must contain exactly one PDF worker with a .js suffix')
  assert.equal(fs.readdirSync(assetsDir).some(name => name.startsWith('pdf.worker') && name.endsWith('.mjs')), false)

  const workerSource = fs.readFileSync(path.join(assetsDir, workers[0]), 'utf8')
  assert.match(workerSource, /withResolvers/, 'legacy worker should contain its Promise.withResolvers compatibility path')
  assert.match(workerSource, /core-js|defineBuiltIn|createNonEnumerableProperty/, 'legacy worker must contain compatibility helpers')
}

await checkLegacyImports()
await checkCoordinatorBehavior()

const distArgIndex = process.argv.indexOf('--dist')
if (distArgIndex >= 0) {
  const distArg = process.argv[distArgIndex + 1]
  assert.ok(distArg, '--dist requires a directory')
  checkBuiltWorker(path.resolve(process.cwd(), distArg))
}

console.log('PDF preview checks passed')
