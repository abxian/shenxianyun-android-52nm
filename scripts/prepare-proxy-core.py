"""271 Apply audited proxy accounting to pinned Android submodule, idempotent.
Python3/git; no source download or remote writes. Fail on wrong HEAD or conflicts.
Rollback git apply -R scripts/proxy-traffic.patch in owned clean submodule only.
"""
import subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1];core=root/'core/src/foss/golang/clash';patch=root/'scripts/proxy-traffic.patch'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=core,text=True).strip()=='a84724665eb7f989809abe463c05f5723bd24975'
def call(*args):return subprocess.run(['git',*args],cwd=core,capture_output=True)
if call('apply','--reverse','--check',str(patch)).returncode: 
 assert call('apply','--check',str(patch)).returncode==0,'incompatible core modifications'
 subprocess.check_call(['git','apply',str(patch)],cwd=core)
