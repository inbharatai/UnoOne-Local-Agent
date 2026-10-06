from pathlib import Path
import subprocess,json,time,hashlib,os,resource
D=Path(__file__).parent; W=Path('/agent/workspace'); R=W/'UnoOne-Local-Agent/android-app/UnoOneAgent'; C=W/'toolchains/gradle-home/caches/modules-2/files-2.1'; J=str(W/'toolchains/jdk17/bin/java')
def jar(g,a): return str(sorted((C/g/a).glob('**/*.jar'))[-1])
jars=[jar(g,a) for g,a in [('org.jetbrains.kotlin','kotlin-stdlib'),('org.jetbrains.kotlin','kotlin-compiler-embeddable'),('org.jetbrains.kotlin','kotlin-reflect'),('org.jetbrains.kotlin','kotlin-script-runtime'),('org.jetbrains','annotations'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm'),('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm'),('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm')]]
cp=':'.join(jars+[str(R/'core/build/tmp/kotlin-classes/debug'),str(R/'localbrain/build/tmp/kotlin-classes/debug')])
files=[D/'Harness.kt',D/'LocalBrainDraftBridge.kt',R/'core/src/main/java/com/unoone/agent/core/model/Result.kt']+[R/'core/src/main/java/com/unoone/agent/core/task'/n for n in ['TaskCoordinator.kt','TaskContract.kt','TaskResourceArbiter.kt','DraftRequest.kt','DraftConstraintPolicy.kt']]+[R/'localbrain/src/main/java/com/unoone/agent/localbrain'/n for n in ['QwenMnnPlanner.kt','qwen/QwenMnnRuntime.kt']]
cmd=[J,'-Xmx384m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-Xfriend-paths='+str(R/'localbrain/build/tmp/kotlin-classes/debug'),'-classpath',cp,'-jvm-target','17','-d',str(D/'harness.jar')]+list(map(str,files))
p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=60); (D/'compile.log').write_text(p.stdout); print(p.stdout,flush=True)
if p.returncode: raise SystemExit(p.returncode)
cmd=[J,'-Xms32m','-Xmx384m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-Djava.library.path=/agent/workspace/qwen-jni-host-validation','-cp',str(D/'harness.jar')+':'+cp,'HarnessKt',str(D)]
start=time.monotonic(); peak=0; maps_seen=set()
with (D/'stdout.txt').open('w') as out,(D/'stderr.txt').open('w') as err,(D/'rss.jsonl').open('w') as rss:
 p=subprocess.Popen(cmd,stdout=out,stderr=err)
 while p.poll() is None:
  try:
   maps_seen.update(x.split()[-1] for x in Path('/proc/%s/maps'%p.pid).read_text().splitlines() if '/' in x); stat=Path('/proc/%s/status'%p.pid).read_text(); mem={x.split(':')[0]:x.split(':')[1].strip() for x in stat.splitlines() if ':' in x}; v=int(mem.get('VmRSS','0 kB').split()[0])*1024; peak=max(v,peak); rss.write(json.dumps({'seconds':time.monotonic()-start,'rss_bytes':v})+'\n'); rss.flush()
  except FileNotFoundError: pass
  if time.monotonic()-start>185: p.kill(); break
  time.sleep(.2)
 rc=p.wait()
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()
model=[p for p in (W/'qwen-validation-model').glob('*') if p.is_file()]
(D/'loaded-file-hashes.json').write_text(json.dumps({s:sha(Path(s)) for s in sorted(maps_seen) if Path(s).is_file()},indent=2))
(D/'compiled-dependency-hashes.json').write_text(json.dumps({str(p):sha(p) for root in [R/'core/build/tmp/kotlin-classes/debug',R/'localbrain/build/tmp/kotlin-classes/debug'] for p in root.rglob('*') if p.is_file()} | {s:sha(Path(s)) for s in jars},indent=2))
result={'exit_code':rc,'wall_seconds':time.monotonic()-start,'peak_sampled_rss_bytes':peak,'command':cmd,'no_address_space_or_rss_limit':True,'host':'x86_64','sha256':{str(p):sha(p) for p in files+model+[W/'qwen-jni-host-validation/libunoone_qwen.so',W/'mnn-host-validation/build/libMNN.so']}}
(D/'run-result.json').write_text(json.dumps(result,indent=2)); print(json.dumps({k:v for k,v in result.items() if k not in ['sha256','command']}))
print((D/'stdout.txt').read_text()); print((D/'stderr.txt').read_text())
