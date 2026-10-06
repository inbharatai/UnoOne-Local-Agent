import pathlib,subprocess,os,time,json,signal,fcntl,hashlib,resource
D=pathlib.Path('/agent/workspace/owl-native-prep/real-probe');P=D.parent
lock=open(D/'exclusive.lock','w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
ps=subprocess.check_output(['ps','-eo','pid,ppid,rss,comm,args'],text=True);(D/'preflight-processes.txt').write_text(ps)
blocked=[]
for line in ps.splitlines()[1:]:
 parts=line.split(None,4)
 if len(parts)==5 and (parts[3] in ['cc1plus','cc1','g++','clang++','cmake','ninja'] or ('java' in parts[3] and ('GradleDaemon' in parts[4] or 'K2JVMCompiler' in parts[4]))):blocked.append(line)
if blocked:raise SystemExit('EXCLUSIVE_PREFLIGHT_FAILED '+repr(blocked))
models=[]
for p in pathlib.Path('/agent/workspace/owl-model').glob('*.gguf'):
 h=hashlib.sha256()
 with p.open('rb') as f:
  while b:=f.read(4*1024*1024):h.update(b)
 receipt=json.loads(pathlib.Path(str(p)+'.receipt.json').read_text());result={'path':str(p),'bytes':p.stat().st_size,'sha256':h.hexdigest(),'download_receipt':receipt};models.append(result)
 if h.hexdigest() not in json.dumps(receipt):raise SystemExit('MODEL_HASH_MISMATCH')
(D/'model-hash-reverification.json').write_text(json.dumps(models,indent=2))
cp=str(D/'classes')+':'+str(P/'production-host/classes')+':'+(P/'production-host/runtime-classpath.txt').read_text()
cmd=['/agent/workspace/toolchains/jdk17/bin/java','-Xms16m','-Xmx128m','-XX:ErrorFile='+str(D/'hs_err_pid%p.log'),'-Djava.library.path='+str(P/'production-host')+':'+str(P/'build/bin'),'-cp',cp,'ProbeKt']
env=dict(os.environ);env['LD_LIBRARY_PATH']=str(P/'production-host')+':'+str(P/'build/bin')
(D/'invocation.json').write_text(json.dumps({'argv':cmd,'LD_LIBRARY_PATH':env['LD_LIBRARY_PATH'],'RLIMIT_AS':resource.getrlimit(resource.RLIMIT_AS),'native_timeout_seconds':480,'rss_cutoff_KiB':3407872,'memavailable_cutoff_KiB':163840,'sample_period_seconds':0.1,'fallback_headroom':'peakRSS < 2.75GiB AND minMemAvailable > 512MiB'},indent=2))
start=time.monotonic();peak=0;minimum=10**12;reason=None
with open(D/'stdout.log','w') as out,open(D/'stderr.log','w') as err,open(D/'memory.jsonl','w') as mem:
 p=subprocess.Popen(cmd,stdout=out,stderr=err,env=env,start_new_session=True);(D/'child.pid').write_text(str(p.pid))
 while p.poll() is None:
  try:
   stat={x.split(':',1)[0]:x.split(':',1)[1].strip() for x in pathlib.Path(f'/proc/{p.pid}/status').read_text().splitlines() if ':' in x}
   system={x.split(':',1)[0]:x.split(':',1)[1].strip() for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if ':' in x}
   rss=int(stat.get('VmRSS','0 kB').split()[0]);avail=int(system['MemAvailable'].split()[0]);peak=max(peak,rss);minimum=min(minimum,avail)
   row={'elapsed_seconds':round(time.monotonic()-start,3),'rss_KiB':rss,'VmHWM':stat.get('VmHWM'),'VmSize':stat.get('VmSize'),'MemAvailable_KiB':avail};mem.write(json.dumps(row)+'\n');mem.flush()
   allowed=D/'headroom.allowed'
   if peak<2883584 and minimum>524288:allowed.write_text(json.dumps(row))
   elif allowed.exists():allowed.unlink()
   if rss>=3407872 or avail<163840:reason='RESOURCE_LIMIT'
   elif time.monotonic()-start>=480:reason='TIME_LIMIT'
   if reason:
    os.killpg(p.pid,signal.SIGTERM)
    try:p.wait(timeout=3)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL)
    break
  except FileNotFoundError:pass
  time.sleep(.1)
 rc=p.wait()
summary={'exit_code':rc,'termination':reason or ('NORMAL_PROCESS_EXIT' if rc==0 else 'PROCESS_FAILURE'),'elapsed_seconds':time.monotonic()-start,'peak_RSS_KiB':peak,'min_MemAvailable_KiB':minimum,'native_close_ack_seen':'FINAL_NATIVE_CLOSE_ACK=true' in (D/'stdout.log').read_text()}
(D/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary),flush=True)
