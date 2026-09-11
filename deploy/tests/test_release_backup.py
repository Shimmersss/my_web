import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
from release_backup import create

class BackupTest(unittest.TestCase):
    def test_complete_bundle_and_failure_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.run').mkdir()
            (root / '.run/task.json').write_text('{"status":"done"}')
            env = root / 'env'; env.write_text('TEST=value')
            bundle = root / 'backup'
            with patch('release_backup.mysql_command', side_effect=lambda *args, output: output.write(b'-- SQL backup')):
                create(root, env, bundle)
            manifest = json.loads((bundle / 'manifest.json').read_text())
            for name, digest in manifest['sha256'].items():
                self.assertEqual(digest, hashlib.sha256((bundle / name).read_bytes()).hexdigest())
                self.assertEqual(0o600, (bundle / name).stat().st_mode & 0o777)
            self.assertFalse((bundle / 'INCOMPLETE').exists())
            with patch('release_backup.mysql_command', side_effect=RuntimeError('dump failed')):
                with self.assertRaises(RuntimeError): create(root, env, root / 'failed')
            self.assertTrue((root / 'failed/INCOMPLETE').exists())

if __name__ == '__main__': unittest.main()
