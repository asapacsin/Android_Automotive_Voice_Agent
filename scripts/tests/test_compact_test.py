import unittest
import tempfile
import os
import sys
import subprocess
from pathlib import Path

class TestCompactTest(unittest.TestCase):
    def setUp(self):
        # Save the current working directory
        self.original_cwd = os.getcwd()
        
    def tearDown(self):
        # Restore the original working directory
        os.chdir(self.original_cwd)
        
    def test_repo_root_in_git_repo_with_gradlew(self):
        # Create a temporary directory for our test git repo
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            
            # Initialize a git repo
            subprocess.run(["git", "init"], cwd=temp_path, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=temp_path, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=temp_path, check=True, capture_output=True)
            
            # Create an empty gradlew file
            (temp_path / "gradlew").touch()
            
            # Change to this directory
            os.chdir(temp_path)
            
            # Import the module and test repo_root
            import importlib.util
            spec = importlib.util.spec_from_file_location("compact_test", Path(__file__).parent.parent / "compact_test.py")
            compact_test = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(compact_test)
            
            # The repo_root should return this temp directory
            self.assertEqual(compact_test.repo_root(), temp_path)
            
    def test_repo_root_in_non_git_directory(self):
        # Create a temporary directory that is not a git repo
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            os.chdir(temp_path)
            
            # Import the module and test repo_root
            import importlib.util
            spec = importlib.util.spec_from_file_location("compact_test", Path(__file__).parent.parent / "compact_test.py")
            compact_test = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(compact_test)
            
            # The repo_root should return the script's parent repo
            self.assertEqual(compact_test.repo_root(), compact_test.REPO)
            
    def test_repo_root_in_git_repo_without_gradlew(self):
        # Create a temporary directory for our test git repo without gradlew
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            
            # Initialize a git repo
            subprocess.run(["git", "init"], cwd=temp_path, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=temp_path, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=temp_path, check=True, capture_output=True)
            
            # Do NOT create gradlew file
            os.chdir(temp_path)
            
            # Import the module and test repo_root
            import importlib.util
            spec = importlib.util.spec_from_file_location("compact_test", Path(__file__).parent.parent / "compact_test.py")
            compact_test = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(compact_test)
            
            # The repo_root should return the script's parent repo since there's no gradlew
            self.assertEqual(compact_test.repo_root(), compact_test.REPO)

if __name__ == "__main__":
    unittest.main()
