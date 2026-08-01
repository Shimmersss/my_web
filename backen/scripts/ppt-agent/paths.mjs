import path from 'node:path';

export function assertInsideStorage(storageRoot, value) {
  if (!value) return '';
  const root = path.resolve(storageRoot);
  const resolved = path.resolve(value);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
    throw new Error(`Agent 文件路径越界: ${resolved}`);
  }
  return resolved;
}
