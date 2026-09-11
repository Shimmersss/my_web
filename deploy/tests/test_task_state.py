import importlib.util
from pathlib import Path
import tempfile
import unittest

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

if __name__ == '__main__': unittest.main()
