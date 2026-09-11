#!/usr/bin/env python3
"""Create a private, checksummed recovery bundle after service stop and drain."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tarfile
from task_state import mysql_command


def create(root, environment, destination):
    root, environment, destination = Path(root), Path(environment), Path(destination)
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    os.chmod(destination, 0o700)
    # Keep incomplete bundles explicitly incomplete; never advertise them as recoverable.
    (destination / 'INCOMPLETE').touch(mode=0o600)
    sql_path = destination / 'database.sql'
    with sql_path.open('xb') as output:
        os.chmod(sql_path, 0o600)
        mysql_command('mysqldump', ['--single-transaction', '--routines', '--triggers', '--events', '--hex-blob', '--no-tablespaces'], output=output)
        output.flush()
        os.fsync(output.fileno())
    with tarfile.open(destination / 'runtime.tar.gz', 'w:gz') as archive:
        runtime = root / '.run'
        if runtime.exists(): archive.add(runtime, arcname='.run')
        # Include configured task roots even when stored outside .run.
        for variable in ['TRANSLATION_STORAGE_DIR', 'PPT_GENERATION_STORAGE_DIR', 'IMAGE_GENERATION_STORAGE_DIR', 'MATCHMAKING_STORAGE_DIR']:
            configured = os.environ.get(variable)
            if configured:
                source = Path(configured)
                if not source.is_absolute(): source = (root / 'backen' / source).resolve()
                if source.exists() and not source.is_relative_to(runtime.resolve()):
                    archive.add(source, arcname='external/' + variable)
    (destination / 'web.env').write_bytes(environment.read_bytes())
    manifest = {'phase': 'stopped-before-install', 'externalPaths': {key: os.environ[key] for key in ['TRANSLATION_STORAGE_DIR', 'PPT_GENERATION_STORAGE_DIR', 'IMAGE_GENERATION_STORAGE_DIR', 'MATCHMAKING_STORAGE_DIR'] if key in os.environ}, 'sha256': {}}
    for name in ['database.sql', 'runtime.tar.gz', 'web.env']:
        path = destination / name
        os.chmod(path, 0o600)
        digest = hashlib.sha256()
        with path.open('rb') as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b''): digest.update(chunk)
        manifest['sha256'][name] = digest.hexdigest()
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    os.chmod(destination / 'manifest.json', 0o600)
    (destination / 'INCOMPLETE').unlink()

if __name__ == '__main__':
    try: create(*sys.argv[1:])
    except Exception:
        print('[x] Recovery backup failed; incomplete evidence retained. No application files should be replaced.', file=sys.stderr)
        sys.exit(1)
