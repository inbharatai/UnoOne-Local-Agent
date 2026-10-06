import pathlib,json,hashlib,shutil,subprocess
D=pathlib.Path('/agent/workspace/owl-native-prep/voice-prompt-ab');old=D.parent/'real-probe-final'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
prior=json.loads((old/'source-binary-hashes-before.json').read_text())
for p,h in prior.items():
 if '/UnoOne-Local-Agent/' in p or '/pinned-host/' in p or '/build/bin/' in p:
  assert sha(pathlib.Path(p))==h,('changed',p)
shutil.copytree(old/'pinned-host',D/'pinned-host')
for f in ['fixture.json','synthetic-gui-A-256.png','synthetic-gui-B-256.png']:shutil.copy2(old/f,D/f)
cmd=[x.replace('real-probe-final','voice-prompt-ab') for x in json.loads((old/'compile-command.json').read_text())]
candidate=pathlib.Path('/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/guiowl/newScopedOwlPrompt.kt')
cmd.append(str(candidate));(D/'compile-command.json').write_text(json.dumps(cmd,indent=2))
with (D/'compile.log').open('w') as f:subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,check=True)
files=[pathlib.Path(p) for p in prior if '/UnoOne-Local-Agent/' in p or '/build/bin/' in p]+list((D/'pinned-host').rglob('*.class'))+list((D/'pinned-host').glob('*.so'))+[candidate]
(D/'source-binary-hashes-before.json').write_text(json.dumps({str(p):sha(p) for p in files},indent=2))
shutil.copy2(candidate,D/'candidate-source.kt')
s=(old/'monitor.py').read_text().replace('real-probe-final','voice-prompt-ab').replace("'native_timeout_seconds':470","'native_timeout_seconds':180,'outer_timeout_seconds':900").replace('start>=470','start>=900')
(D/'monitor.py').write_text(s)
print('Production identity verified; actual candidate compiled; compiler exited before model loading.',flush=True)
