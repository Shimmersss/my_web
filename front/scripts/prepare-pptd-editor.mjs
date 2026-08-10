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
