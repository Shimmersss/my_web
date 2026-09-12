import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('task_state', Path(__file__).parents[1] / 'task_state.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class TaskStateTest(unittest.TestCase):
    def test_terminal_compensation_blocks_and_unknown_state_fails_closed(self):
        for status in ['completed', 'done', 'failed', 'error', 'cancelled']:
            self.assertFalse(module.unsettled({'status': status}))
            self.assertTrue(module.unsettled({'status': status, 'refundPending': True}))
            self.assertTrue(module.unsettled({'status': status, 'compensationPending': True}))
        with self.assertRaises(ValueError): module.unsettled({'status': 'unexpected'})

    def test_corrupt_snapshot_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            file = Path(root) / '.run/image-generation-tasks/test/task.json'
            file.parent.mkdir(parents=True)
            file.write_text('{broken')
            with self.assertRaises(ValueError): list(module.disk_tasks(root))

    def test_first_install_accepts_absent_sql_task_table_only_after_querying_schema(self):
        with patch.object(module, 'mysql_command', return_value=b'0\n') as mysql:
            self.assertEqual([], list(module.sql_tasks()))
            self.assertEqual(1, mysql.call_count)
        with patch.object(module, 'mysql_command', side_effect=RuntimeError('database unavailable')):
            with self.assertRaises(RuntimeError): list(module.sql_tasks())

    def test_legacy_matchmaking_evidence_is_included(self):
        with tempfile.TemporaryDirectory() as root:
            file = Path(root) / '.run/matchmaking-tasks/legacy.json'
            file.parent.mkdir(parents=True)
            file.write_text('{"status":"error","compensationPending":true}')
            tasks = list(module.disk_tasks(root))
            self.assertEqual(1, len(tasks))
            self.assertTrue(module.unsettled(tasks[0]))

if __name__ == '__main__': unittest.main()
