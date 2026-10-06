"""Mutation tests exercise the real catalogue validator without editing repository assets."""
import copy
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
import check_repo_invariants as gate

class ManifestContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = gate.load_json('android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json')
    def check(self, value):
        errors = []
        with patch.object(gate, 'load_json', return_value=value):
            gate.validate_model_manifest(errors)
        return errors
    def test_exact_four_profiles(self):
        self.assertEqual([], self.check(self.manifest))
        for mutate in [lambda m: m['models'].pop(0), lambda m: m['models'].append(copy.deepcopy(m['models'][0]))]:
            bad = copy.deepcopy(self.manifest); mutate(bad)
            self.assertTrue(self.check(bad))
    def test_both_pins_and_transport_are_enforced(self):
        for index in (0, 1):
            for key, value in [('sha256','0'*64), ('sizeBytes',1), ('url','https://attacker.invalid/resolve/main/model'), ('name','bad-web.litertlm'), ('archive',True), ('asset','override')]:
                bad=copy.deepcopy(self.manifest); bad['models'][index]['files'][0][key]=value
                self.assertTrue(self.check(bad), (index,key))
    def test_schema_ram_and_path_guardrails(self):
        for field, value in [('manifestVersion',3), ('manifestVersion',4)]:
            bad=copy.deepcopy(self.manifest);bad[field]=value;self.assertTrue(self.check(bad))
        for field,value in [('folder','../escape'),('minRamMb',1),('id','unknown')]:
            bad=copy.deepcopy(self.manifest);bad['models'][0][field]=value;self.assertTrue(self.check(bad))
    def test_qwen_every_artifact_is_exact_and_required(self):
        qi = next(i for i,m in enumerate(self.manifest['models']) if m['id'] == gate.QWEN_ID)
        for index in range(9):
            for key,value in [('sha256','0'*64),('sizeBytes',1),('url','https://attacker.invalid/remote'),('name','missing'),('archive',True),('asset','override')]:
                bad=copy.deepcopy(self.manifest);bad['models'][qi]['files'][index][key]=value
                self.assertTrue(self.check(bad),(index,key))
            bad=copy.deepcopy(self.manifest);bad['models'][qi]['files'].pop(index)
            self.assertTrue(self.check(bad),index)
        for key,value in [('backend','gpu'),('version','main'),('folder','brain/other'),('minRamMb',1)]:
            bad=copy.deepcopy(self.manifest);bad['models'][qi][key]=value
            self.assertTrue(self.check(bad),key)
    def test_owl_exact_pair_and_unknown_profile(self):
        oi = next(i for i,m in enumerate(self.manifest['models']) if m['id'] == gate.OWL_ID)
        for index in range(2):
            for key,value in [('sha256','0'*64),('sizeBytes',1),('name','wrong.gguf'),('archive',True),('asset','override'),('url', self.manifest['models'][oi]['files'][index]['url'].replace(gate.OWL_REVISION, 'main'))]:
                bad=copy.deepcopy(self.manifest);bad['models'][oi]['files'][index][key]=value
                self.assertTrue(self.check(bad),(index,key))
            bad=copy.deepcopy(self.manifest);bad['models'][oi]['files'].pop(index)
            self.assertTrue(self.check(bad),index)
        bad=copy.deepcopy(self.manifest);bad['models'][oi]['files'].append(copy.deepcopy(bad['models'][oi]['files'][0]))
        self.assertTrue(self.check(bad))
        for key,value in [('backend','gpu'),('version','main'),('folder','brain/other'),('minRamMb',1),('id','unknown-llm'),('type','asr')]:
            bad=copy.deepcopy(self.manifest);bad['models'][oi][key]=value
            self.assertTrue(self.check(bad),key)
        bad=copy.deepcopy(self.manifest);unknown=copy.deepcopy(bad['models'][oi]);unknown['id']='unknown';unknown['folder']='brain/unknown';bad['models'].append(unknown)
        self.assertTrue(self.check(bad))

    def test_v3_schema_corpus_and_negative_cases(self):
        errors=[];gate.validate_v3_corpus(errors);self.assertEqual([],errors)

class OwlSourceContractTest(unittest.TestCase):
    def test_current_contract(self):
        errors=[];gate.validate_owl_source_contract(errors);self.assertEqual([],errors)

    def test_runtime_provenance_and_verification_mutations(self):
        original=Path.read_text
        mutations = [
            ('BrainModel.kt','BrainRuntime.LLAMA_CPP','BrainRuntime.MNN'),
            ('BrainModel.kt','supportsBrowserProtocol = false','supportsBrowserProtocol = true'),
            ('BrainModel.kt','isDeviceVerified = false','isDeviceVerified = true'),
            ('BrainModel.kt','experimentalLabel = "EXPERIMENTAL','experimentalLabel = "VERIFIED'),
            ('BrainModel.kt','GuiOwlArtifact.PROVENANCE_DISCLOSURE','"cleared"'),
            ('GuiOwlArtifact.kt',gate.OWL_REVISION,'main'),
            ('GuiOwlArtifact.kt',gate.OWL_DISCLOSURE,'Commercially cleared'),
            ('GuiOwlArtifact.kt','2497282208L','1L'),
            ('GuiOwlArtifact.kt','453974336L','1L'),
        ]
        for filename,old,new in mutations:
            with self.subTest(filename=filename,old=old):
                def changed(path,*args,**kwargs):
                    text=original(path,*args,**kwargs)
                    return text.replace(old,new) if path.name == filename else text
                errors=[]
                with patch.object(Path,'read_text',changed):gate.validate_owl_source_contract(errors)
                self.assertTrue(errors)

class SourceScanTest(unittest.TestCase):
    def test_generated_metadata_skipped_but_native_app_sources_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);active=root/'android-app/UnoOneAgent'
            source=active/'localbrain/src/main/java/App.kt'
            generated=active/'localbrain/.cxx/Debug/targets.json'
            for path in (source,generated):
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text('gemma3n')
            with patch.object(gate,'ROOT',root),patch.object(gate,'ACTIVE_ROOTS',(active,)):
                self.assertEqual([source],list(gate.iter_active_files()))
                errors=[];gate.scan_prohibited_text(errors)
                self.assertEqual(1,len(errors));self.assertIn('src/main/java/App.kt',errors[0])
                source.write_text('safe app source')
                errors=[];gate.scan_prohibited_text(errors);self.assertEqual([],errors)

class BrowserBoundaryTest(unittest.TestCase):
    def test_current_boundary(self):
        errors=[]; gate.validate_secure_browser(errors); self.assertEqual([],errors)

    def test_rejects_bridge_reintroduction_and_dynamic_code(self):
        from pathlib import Path
        original = Path.read_text
        for filename, injected in [('SecureWebViewController.kt', '\naddJavascriptInterface('), ('dom-adapter.js', '\nMODEL_INVOKE'), ('dom-adapter.js', '\neval(')]:
            def changed(path, *args, **kwargs):
                text=original(path,*args,**kwargs)
                return text + injected if path.name == filename else text
            errors=[]
            with patch.object(Path,'read_text',changed): gate.validate_secure_browser(errors)
            self.assertTrue(errors,(filename,injected))

if __name__ == '__main__': unittest.main()
