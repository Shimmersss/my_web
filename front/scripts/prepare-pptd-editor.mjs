import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const projectRoot = path.resolve(frontRoot, '..');
const upstream = path.join(projectRoot, 'vendor/open-kimi-ppt-skill/editor');
const overlay = path.join(frontRoot, 'pptd-editor-overlay');
const target = path.join(frontRoot, 'public/pptd-editor');

await fs.access(path.join(upstream, 'neo-ppt/index.html'));
await fs.rm(target, { recursive: true, force: true });
await fs.mkdir(target, { recursive: true });
await fs.cp(upstream, path.join(target, 'upstream'), { recursive: true });
await fs.cp(overlay, target, { recursive: true, force: true });
const editorIndexPath = path.join(target, 'upstream/index.html');
let editorIndex = await fs.readFile(editorIndexPath, 'utf8');
const bridgeScript = '<script type="module" src="./local-bridge.js"></script>';
const officialAppScript = '<script type="module" crossorigin src="./neo-ppt/assets/index-jtNAhQeK.js"></script>';
if (!editorIndex.includes(bridgeScript) || !editorIndex.includes(officialAppScript)) {
  throw new Error('Unexpected upstream editor index shape; refuse to apply website bootstrap overlay')
}
// Independent module scripts do not have a deterministic evaluation order.  The official
// editor can otherwise start before __NEODECK_CONNECT__ exists, leaving a blank canvas.
// Keep the vendored index untouched: this is applied only to the copied website runtime.
editorIndex = editorIndex
  .replace('</head>', '    <base href="/pptd-editor/upstream/" />\n</head>')
  .replace(bridgeScript, '<script type="module">\n      import "./local-bridge.js";\n      const connect = window.__NEODECK_CONNECT__;\n      if (typeof connect !== "function") throw new Error("PPTD 本地桥接未初始化");\n      // The patched editor loads its SDK module lazily. Keep the local connector alive\n      // until that module asks for it instead of falling back to a parent-window Penpal handshake.\n      Object.defineProperty(window, "__NEODECK_CONNECT__", { configurable: false, value: connect });\n      const app = document.createElement("script");\n      app.type = "module";\n      app.crossOrigin = "anonymous";\n      app.src = "./neo-ppt/assets/index-jtNAhQeK.js";\n      document.head.append(app);\n    </script>')
  .replace(officialAppScript, '')
// The vendored editor's router is built for its own server root. It mounts only its
// empty shell when served beneath /pptd-editor/upstream/. The base tag keeps every
// asset relative to the copied runtime while the URL exposed to the editor remains /.
editorIndex = editorIndex.replace(
  'const connect = window.__NEODECK_CONNECT__;',
  'if (location.pathname !== "/") history.replaceState(null, "", `/${location.search}${location.hash}`);\n      const connect = window.__NEODECK_CONNECT__;'
)
await fs.writeFile(editorIndexPath, editorIndex)
const bridgePath = path.join(target, 'upstream/local-bridge.js');
const remoteBridge = await fs.readFile(path.join(overlay, 'remote-bridge.js'), 'utf8');
let bridge = await fs.readFile(bridgePath, 'utf8');
if (!bridge.includes('onSave,') || !bridge.includes('if (!state.manifestPath) openDemo()')) {
  throw new Error('Unexpected upstream local-bridge.js shape; refuse to apply website overlay')
}
bridge = bridge.replace('    onSave,', '    onSave: remoteAwareSave,')
bridge = bridge.replace(
  'if (!state.manifestPath) openDemo().catch((e) => console.error(e));',
  'if (remoteTaskId) loadRemoteProject().catch((e) => { toast(e.message || String(e), "error"); console.error(e); }); else if (!state.manifestPath) openDemo().catch((e) => console.error(e));'
)
await fs.writeFile(bridgePath, `${bridge}\n\n// Website overlay; upstream files above remain byte-for-byte sourced before this append.\n${remoteBridge}`)
process.stdout.write(`Prepared vendored PPTD editor at ${target}\n`);
