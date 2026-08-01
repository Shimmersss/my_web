import test from 'node:test';
import assert from 'node:assert/strict';
import { assertInsideStorage } from './paths.mjs';

test('task paths cannot traverse outside the storage root or exploit prefix collisions', () => {
  assert.equal(assertInsideStorage('/srv/tasks', '/srv/tasks/a/job.json'), '/srv/tasks/a/job.json');
  assert.throws(() => assertInsideStorage('/srv/tasks', '/srv/tasks-evil/job.json'), /路径越界/);
  assert.throws(() => assertInsideStorage('/srv/tasks', '/srv/tasks/a/../../../etc/passwd'), /路径越界/);
});
