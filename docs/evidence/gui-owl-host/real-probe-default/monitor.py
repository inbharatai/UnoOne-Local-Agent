import pathlib,subprocess,os,time,json,signal,fcntl,hashlib,resource
D=pathlib.Path('/agent/workspace/owl-native-prep/real-probe-default');P=D.parent
lock=open(D/'exclusive.lock','w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
ps=subprocess.check_output(['ps','-eo','pid,ppid,rss,comm,args'],text=True);(D/'preflight-processes.txt').write_text(ps)
blocked=[]
for line in ps.splitlines()[1:]:
 parts=line.split(None,4)
 if len(parts)==5 and (parts[3] in ['java','cc1plus','cc1','g++','clang++','cmake','ninja','llama-cli','llama-mtmd-cli']):blocked.append(line)
if blocked:raise SystemExit('EXCLUSIVE_PREFLIGHT_FAILED '+repr(blocked))
models=[]
for p in pathlib.Path('/agent/workspace/owl-model').glob('*.gguf'):
 h=hashlib.sha256()
 with p.open('rb') as f:
  while b:=f.read(4*1024*1024):h.update(b)
 receipt=json.loads(pathlib.Path(str(p)+'.receipt.json').read_text());models.append({'path':str(p),'bytes':p.stat().st_size,'sha256':h.hexdigest(),'download_receipt':receipt})
 if h.hexdigest() not in json.dumps(receipt):raise SystemExit('MODEL_HASH_MISMATCH')
(D/'model-hash-reverification.json').write_text(json.dumps(models,indent=2))
cp=str(D/'classes')+':'+str(P/'production-host/classes')+':'+(P/'production-host/runtime-classpath.txt').read_text()
cmd=['/agent/workspace/toolchains/jdk17/bin/java','-Xms16m','-Xmx128m','-XX:ErrorFile='+str(D/'hs_err_pid%p.log'),'-Djava.library.path='+str(P/'production-host')+':'+str(P/'build/bin'),'-cp',cp,'ProbeKt']
env=dict(os.environ);env['LD_LIBRARY_PATH']=str(P/'production-host')+':'+str(P/'build/bin')
rss_limit=int(3.8*1024*1024);floor=256*1024
cg=pathlib.Path('/sys/fs/cgroup/memory.current')
(D/'invocation.json').write_text(json.dumps({'argv':cmd,'LD_LIBRARY_PATH':env['LD_LIBRARY_PATH'],'RLIMIT_AS':resource.getrlimit(resource.RLIMIT_AS),'native_timeout_seconds':330,'rss_cutoff_KiB':rss_limit,'memavailable_cutoff_KiB':floor,'sample_period_seconds':0.1,'memory_current_path':str(cg),'signal_target':'child PID only'},indent=2))
start=time.monotonic();peak=0;hwm=0;minimum=10**12;cgpeak=0;reason=None
with open(D/'stdout.log','w') as out,open(D/'stderr.log','w') as err,open(D/'memory.jsonl','w') as mem:
 p=subprocess.Popen(cmd,stdout=out,stderr=err,env=env,start_new_session=True);(D/'child.pid').write_text(str(p.pid))
 while p.poll() is None:
  try:
   stat={x.split(':',1)[0]:x.split(':',1)[1].strip() for x in pathlib.Path(f'/proc/{p.pid}/status').read_text().splitlines() if ':' in x}
   system={x.split(':',1)[0]:x.split(':',1)[1].strip() for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if ':' in x}
   rss=int(stat.get('VmRSS','0 kB').split()[0]);hw=int(stat.get('VmHWM','0 kB').split()[0]);avail=int(system['MemAvailable'].split()[0]);peak=max(peak,rss);hwm=max(hwm,hw);minimum=min(minimum,avail)
   current=int(cg.read_text()) if cg.exists() else None
   if current:cgpeak=max(cgpeak,current)
   row={'elapsed_seconds':round(time.monotonic()-start,3),'rss_KiB':rss,'VmHWM_KiB':hw,'VmSize':stat.get('VmSize'),'MemAvailable_KiB':avail,'memory_current_bytes':current};mem.write(json.dumps(row)+'\n');mem.flush()
   if rss>=rss_limit or hw>=rss_limit or avail<floor:reason='RESOURCE_LIMIT'
   elif time.monotonic()-start>=330:reason='TIME_LIMIT'
   if reason:
    p.terminate()
    try:p.wait(timeout=3)
    except subprocess.TimeoutExpired:p.kill()
    break
  except FileNotFoundError:pass
  time.sleep(.1)
 rc=p.wait()
summary={'exit_code':rc,'termination':reason or ('NORMAL_PROCESS_EXIT' if rc==0 else 'PROCESS_FAILURE'),'elapsed_seconds':time.monotonic()-start,'peak_RSS_KiB':peak,'peak_VmHWM_KiB':hwm,'peak_memory_current_bytes':cgpeak,'min_MemAvailable_KiB':minimum,'native_close_ack_seen':'FINAL_NATIVE_CLOSE_ACK=true' in (D/'stdout.log').read_text()}
(D/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary),flush=True)
