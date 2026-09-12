#!/usr/bin/env python3
"""Fail-closed disk and SQL task drain check; never print task payloads."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit, unquote


def disk_tasks(root):
    backend = Path(root) / 'backen'
    specs = [('TRANSLATION_STORAGE_DIR', 'translation-tasks'),
             ('PPT_GENERATION_STORAGE_DIR', 'ppt-generation-tasks'),
             ('IMAGE_GENERATION_STORAGE_DIR', 'image-generation-tasks')]
    specs.append(('MATCHMAKING_STORAGE_DIR', 'matchmaking-tasks'))
    for env, directory in specs:
        path = Path(os.environ.get(env, '../.run/' + directory))
        if not path.is_absolute(): path = (backend / path).resolve()
        pattern = '*.json' if env == 'MATCHMAKING_STORAGE_DIR' else '*/task.json'
        for metadata in path.glob(pattern):
            with metadata.open(encoding='utf-8') as handle:
                yield json.load(handle)


def unsettled(task):
    if not isinstance(task, dict) or not isinstance(task.get('status'), str):
        raise ValueError('invalid task snapshot')
    status = task['status'].lower()
    if status not in {'preview', 'creating', 'queued', 'running', 'translating', 'generating', 'completed', 'done', 'failed', 'error', 'cancelled'}:
        raise ValueError('unknown task status')
    return status in {'creating', 'queued', 'running', 'translating', 'generating'} or bool(task.get('refundPending') or task.get('compensationPending'))


def mysql_command(program, args, output=None):
    url = os.environ.get('DB_URL', '')
    if not url.startswith('jdbc:mysql://'): raise ValueError('MySQL configuration required')
    parsed = urlsplit(url[5:])
    database = unquote(parsed.path.lstrip('/'))
    if not database or '/' in database: raise ValueError('invalid database')
    def quote(value):
        return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'
    with tempfile.NamedTemporaryFile(mode='w', prefix='web-mysql-', encoding='utf-8') as config:
        config.write('[client]\n' + '\n'.join(f'{key}={quote(value)}' for key, value in {
            'host': parsed.hostname or 'localhost', 'port': parsed.port or 3306,
            'user': os.environ['DB_USERNAME'], 'password': os.environ['DB_PASSWORD'],
        }.items()) + '\n')
        config.flush()
        return subprocess.run([program, '--defaults-extra-file=' + config.name, *args, database],
                              stdout=output if output is not None else subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def sql_tasks():
    # JSON is stored as TEXT; fetch payloads without passing private data to argv/logs.
    exists = mysql_command('mysql', ['--batch', '--skip-column-names', '-e', "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='matchmaking_tasks'"])
    if exists.strip() == b'0': return
    raw = mysql_command('mysql', ['--batch', '--skip-column-names', '--raw', '-e', 'SELECT payload FROM matchmaking_tasks'])
    for line in raw.decode('utf-8').splitlines():
        yield json.loads(line)


def main():
    try:
        tasks = list(disk_tasks(sys.argv[1])) + list(sql_tasks())
        count = sum(unsettled(task) for task in tasks)
        if count:
            print(f'[x] Deployment blocked: {count} active or compensating tasks.', file=sys.stderr)
            return 42
        return 0
    except Exception:
        print('[x] Task state could not be verified; deployment blocked. Preserve snapshots and inspect database access.', file=sys.stderr)
        return 42

if __name__ == '__main__':
    sys.exit(main())
