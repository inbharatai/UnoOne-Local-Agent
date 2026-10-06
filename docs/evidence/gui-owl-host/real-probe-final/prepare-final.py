import pathlib,json,hashlib,shutil,subprocess
D=pathlib.Path('/agent/workspace/owl-native-prep/real-probe-final');P=D.parent;old=P/'real-probe-default';src=P/'race-production-host'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
expected={'libunoone_owl.so':'f3c85dac4703d17211a539c6649b6894fc4884068f13ab0f524e0b0e25610a22','classes/com/unoone/agent/localbrain/owl/OwlLlamaRuntime.class':'8884b30e8a7886bcd4e84fb448e0ea9b1220c38742852036f62af8b2c26068d3','classes/com/unoone/agent/localbrain/owl/OwlLlamaPlanner.class':'33f4dd45f0c94c9a7732613b0abdb7751261e3dbb6b98c90f38ec73c09668ed0'}
for f,h in expected.items():assert sha(src/f)==h,(f,'WRONG_REPAIRED_BINARY')
shutil.copytree(src,D/'pinned-host')
(D/'pinned-production-verification.json').write_text(json.dumps({'source':str(src),'expected_and_verified':expected},indent=2))
for f in ['fixture.json','synthetic-gui-A-256.png','synthetic-gui-B-256.png','synthetic-red-256.png']:shutil.copy2(old/f,D/f)
(D/'prior-evidence-before.json').write_text(json.dumps({str(p.relative_to(P)):sha(p) for folder in ['real-probe','real-probe-default'] for p in (P/folder).rglob('*') if p.is_file()},indent=2))
s=(old/'Probe.kt').read_text().replace('real-probe-default','real-probe-final')
s=s.replace(' val r=OwlLlamaRuntime();',' val probeStart=System.nanoTime()\n val r=OwlLlamaRuntime();')
s=s.replace(' event("LOAD default");',' val staleLoad=r.admitLoad();r.cancel();test("stale-load-permit"){r.load("/agent/workspace/owl-model",permit=staleLoad)}\n event("LOAD default");')
s=s.replace('event("CONFIG "+r.load("/agent/workspace/owl-model"))','event("CONFIG "+r.load("/agent/workspace/owl-model"));File(dir,"loaded-process-maps.txt").writeText(File("/proc/self/maps").readText())')
s=s.replace(' }catch(t:Throwable){File(dir,"fatal.error.txt")',' val ack=r.close();event("PRE_RELOAD_NATIVE_CLOSE_ACK=$ack status=${r.status}")\n if(ack && System.nanoTime()-probeStart < 380_000_000_000L){\n  test("reload-default"){r.load("/agent/workspace/owl-model")}\n  test("reload-text-sanity"){r.generate("Answer briefly.","What is 2+2?",null,16)}\n }else event("RELOAD_SKIPPED ack=$ack boundedTimeRemaining=false")\n }catch(t:Throwable){File(dir,"fatal.error.txt")')
(D/'Probe.kt').write_text(s)
cmd=[x.replace('real-probe-default','real-probe-final').replace(str(P/'production-host'),str(D/'pinned-host')) for x in json.loads((old/'compile-command.json').read_text())]
(D/'compile-command.json').write_text(json.dumps(cmd,indent=2))
with (D/'compile.log').open('w') as out:subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,check=True)
files=[pathlib.Path(x) for x in cmd if x.endswith('.kt')]+list((D/'pinned-host').rglob('*.class'))+list((D/'pinned-host').glob('*.so'))+list((P/'build/bin').glob('*.so*'))
root=pathlib.Path('/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent')
files += [root/'localbrain/src/main/cpp/owl_jni.cpp',root/'localbrain/src/main/java/com/unoone/agent/localbrain/owl/OwlLlamaRuntime.kt',root/'localbrain/src/main/java/com/unoone/agent/localbrain/owl/OwlLlamaPlanner.kt']
(D/'source-binary-hashes-before.json').write_text(json.dumps({str(p):sha(p) for p in files if p.is_file()},indent=2))
s=(old/'monitor.py').read_text().replace('real-probe-default','real-probe-final').replace("P/'production-host","D/'pinned-host").replace('330','470')
(D/'monitor.py').write_text(s)
s=(old/'analyze.py').read_text().replace('real-probe-default','real-probe-final')
s=s.replace("old=P/'real-probe';before=", "old=P;before=").replace("for p in old.rglob('*') if p.is_file()", "for folder in ['real-probe','real-probe-default'] for p in (old/folder).rglob('*') if p.is_file()")
(D/'analyze.py').write_text(s)
print('Ready: exact repaired production pinned; fixtures copied unchanged; standalone harness compiler finished.')
