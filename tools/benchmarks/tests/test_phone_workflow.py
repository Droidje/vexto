"""Exercise the actual trusted-provenance workflow shell using local API fixtures."""
import json, os, subprocess, tempfile, unittest, shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
def provenance_shell():
    lines = (ROOT / '.github/workflows/phone-apk.yml').read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == '- name: Validate successful main CI provenance')
    start = next(i for i in range(start, len(lines)) if lines[i] == '        run: |') + 1
    body = []
    for line in lines[start:]:
        if line and not line.startswith('          '): break
        body.append(line[10:])
    return '\n'.join(body)
SHELL = provenance_shell()
COMMIT='a'*40
MAIN='b'*40
TAG='c'*40

@unittest.skipUnless(shutil.which("jq") and shutil.which("bash"), "Workflow shell tests require bash and jq")
class TagBootstrapTest(unittest.TestCase):
    def invoke(self, branch='v0.5.1', kind='commit', target=COMMIT, annotated=None, event='bootstrap', metadata_override=None):
        metadata={'conclusion':'success','path':'.github/workflows/phone-apk.yml','head_repository':{'full_name':'Droidje/vexto'},'head_branch':branch,'event':'workflow_dispatch','head_sha':COMMIT}
        if event=='automatic': metadata.update(path='.github/workflows/ci.yml',event='push')
        metadata.update(metadata_override or {})
        bodies={'repos/Droidje/vexto/actions/runs/42':json.dumps(metadata),'repos/Droidje/vexto/git/ref/tags/'+branch:json.dumps({'object':{'type':kind,'sha':target}}),'repos/Droidje/vexto/git/ref/heads/main':MAIN}
        if annotated is not None: bodies['repos/Droidje/vexto/git/tags/'+TAG]=json.dumps({'object':{'type':annotated[0],'sha':annotated[1]}})
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for i,(key,value) in enumerate(bodies.items()): (root/str(i)).write_text(value)
            cases='\n'.join(json.dumps(key)+') cat '+json.dumps(str(root/str(i)))+';;' for i,key in enumerate(bodies))
            mock='gh() { case "$2" in\n'+cases+'\n*) echo "Unexpected API route" >&2; return 90;; esac; }\n'
            env=os.environ|{'EVENT_NAME':'workflow_dispatch' if event=='bootstrap' else 'workflow_run','INSTALLER_RUN':'42' if event=='bootstrap' else '', 'BASE_RUN':'' if event=='bootstrap' else '42','REQUESTED_SHA':MAIN if event=='bootstrap' else COMMIT,'REPOSITORY':'Droidje/vexto','GITHUB_OUTPUT':str(root/'out')}
            run=subprocess.run(['bash','-e','-o','pipefail','-c',mock+SHELL],env=env,capture_output=True,text=True)

            return run.returncode,(root/'out').read_text() if (root/'out').exists() else ''
    def test_fork_run_is_rejected(self):
        self.assertNotEqual(0, self.invoke(metadata_override={'head_repository': {'full_name': 'outsider/vexto'}})[0])
    def test_failed_run_is_rejected(self):
        self.assertNotEqual(0, self.invoke(metadata_override={'conclusion': 'failure'})[0])
    def test_lightweight_matching_tag_passes(self): self.assertEqual(0,self.invoke()[0])
    def test_moved_tag_fails(self): self.assertNotEqual(0,self.invoke(target=MAIN)[0])
    def test_matching_annotated_tag_passes(self): self.assertEqual(0,self.invoke(kind='tag',target=TAG,annotated=('commit',COMMIT))[0])
    def test_tag_cycle_hits_bounded_peel_limit(self): self.assertNotEqual(0,self.invoke(kind='tag',target=TAG,annotated=('tag',TAG))[0])
    def test_unversioned_branch_is_not_trusted_for_bootstrap(self): self.assertNotEqual(0,self.invoke(branch='feature/anything')[0])
    def test_main_bootstrap_still_passes(self): self.assertEqual(0,self.invoke(branch='main')[0])
    def test_normal_automatic_main_passes(self): self.assertEqual(0,self.invoke(branch='main',event='automatic')[0])
    def test_normal_automatic_tag_still_rejected(self): self.assertNotEqual(0,self.invoke(event='automatic')[0])

if __name__=='__main__': unittest.main(verbosity=2)
