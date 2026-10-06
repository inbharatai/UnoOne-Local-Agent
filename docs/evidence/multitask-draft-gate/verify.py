from pathlib import Path
import json,hashlib
D=Path(__file__).parent; W=Path('/agent/workspace'); E=D.parent

def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()
old=json.loads((E/'multitask-host/run-result.json').read_text())['sha256']
pins={s:{'expected':v,'actual':sha(Path(s))} for s,v in old.items() if '/qwen-validation-model/' in s or s.endswith('.so')}
source=W/'UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt'
s=source.read_text(); method=s[s.index('    suspend fun draftText('):s.index('\n    /**',s.index('    suspend fun draftText('))]
before=json.loads((D/'original-evidence-before.json').read_text()); after={str(p.relative_to(E/'multitask-host')):sha(p) for p in (E/'multitask-host').rglob('*') if p.is_file()}
result={'pinned_model_and_native_files':pins,'all_pins_match':all(x['expected']==x['actual'] for x in pins.values()),'original_failed_evidence_unchanged':before==after,'LocalBrain_source_sha256':sha(source),'exact_draftText_method_in_bridge':method in (D/'LocalBrainDraftBridge.kt').read_text(),'bridge_limits':'Exact extracted LocalBrain.draftText method body compiled unchanged in host bridge; controllerRequest forwards directly to actual QwenMnnPlanner with identical defaults. Android LocalBrain composition/lifecycle and UI not executed. Shared lease and model budget enforced by actual coordinator worker callback.'}
(D/'provenance-verification.json').write_text(json.dumps(result,indent=2)); print({k:v for k,v in result.items() if k!='pinned_model_and_native_files'})
