import pathlib
import re
import subprocess
import unittest

SOURCE = pathlib.Path(__file__).parents[1] / 'deploy-server-improved.sh'

class InstallContract(unittest.TestCase):
    def test_all_generated_scripts_parse_and_startup_keeps_maintenance_lock(self):
        source = SOURCE.read_text()
        subprocess.run(['bash', '-n', str(SOURCE)], check=True)
        scripts = {}
        for delimiter in ['INSTALL_SCRIPT', 'RESUME_SCRIPT']:
            scripts[delimiter] = source.split("<<'" + delimiter + "'\n", 1)[1].split('\n' + delimiter, 1)[0]
            subprocess.run(['bash', '-n'], input=scripts[delimiter], text=True, check=True)
        installed = scripts['INSTALL_SCRIPT'].split('INSTALL_MUTATED=1\ninfo "Installing files', 1)[1]
        self.assertNotRegex(installed, r'rm\s+-f\s+"\$DEPLOYMENT_LOCK"')
        self.assertIn('systemctl start "$SERVICE_NAME.service"', installed)
        self.assertIn('MemoryHigh=2600M', installed)
        self.assertIn('MemoryMax=2800M', installed)
        self.assertIn('== deployment', scripts['RESUME_SCRIPT'])

if __name__ == '__main__': unittest.main()
