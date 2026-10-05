#!/usr/bin/env python3
"""Dependency-free strict validator for the shipped JSON Schema subset and aggregator."""
import json,sys
from pathlib import Path
P=Path(__file__).parent
load=lambda p:json.loads(Path(p).read_text())
def check(x,s,path='$'):
 if 'const' in s and x!=s['const']: raise ValueError(path+': const')
 if 'enum' in s and x not in s['enum']: raise ValueError(path+': enum')
 types=s.get('type',[]);types=[types] if isinstance(types,str) else types
 matches={'null':x is None,'object':isinstance(x,dict),'array':isinstance(x,list),'string':isinstance(x,str),'boolean':type(x)==bool,'number':type(x) in (float,int),'integer':type(x)==int}
 if types and not any(matches[t] for t in types): raise ValueError(path+': type')
 if isinstance(x,dict):
  if set(s.get('required',[]))-set(x): raise ValueError(path+': missing required')
  if s.get('additionalProperties') is False and set(x)-set(s['properties']): raise ValueError(path+': unknown fields')
  for k,v in x.items():
   if k in s.get('properties',{}):check(v,s['properties'][k],path+'.'+k)
 if isinstance(x,list):
  for i,v in enumerate(x):check(v,s['items'],path+f'[{i}]')
 if isinstance(x,str) and len(x)<s.get('minLength',0):raise ValueError(path+': empty string')
 if type(x) in (float,int):
  import math
  if not math.isfinite(x):raise ValueError(path+': nonfinite')
  if x<s.get('minimum',-float('inf')) or x>s.get('maximum',float('inf')):raise ValueError(path+': range')
def aggregate(run,tasks):
 check(run,load(P/'result.schema.json'))
 ids={t['id'] for t in tasks};seen=set();counts={s:0 for s in ['pass','partial','fail','blocked','pending','skipped']}
 for r in run['results']:
  tid=r['taskId']
  if tid not in ids or tid in seen:raise ValueError('Unknown or duplicate task '+tid)
  seen.add(tid);counts[r['status']]+=1;m=r['metrics']
  if r['status'] in ('pass','partial','fail'):
   if not r['evidence'] or any(v is None for v in m.values()):raise ValueError('Measured result requires all metrics and evidence: '+tid)
   if any(not v or v.lower() in ('unknown','pending','unavailable') for v in run['identity'].values()):raise ValueError('Measured result requires exact identity')
  if r['status']=='pass' and (m['taskSuccess'] is not True or m['partial'] is not False or any(m[k] is not False for k in ['wrongAction','falseSuccess','hallucinatedNode','crash','anr'])):raise ValueError('Contradictory pass '+tid)
  if r['status']!='pass' and m['taskSuccess'] is True:raise ValueError('Non-pass cannot claim task success')
 counts['pending']+=len(ids-seen)
 off=run['offlineEvidence'];offline=all(off.values())
 qualified=counts['pass']==len(ids) and offline
 if run['physicalQualification']=='qualified' and not qualified:raise ValueError('Unsupported qualification claim')
 return {'totalRequired':len(ids),'counts':counts,'missingCountedPending':len(ids-seen),'successRate':counts['pass']/len(ids),'physicalQualification': 'qualified' if qualified and run['physicalQualification']=='qualified' else ('failed' if run['physicalQualification']=='failed' else 'pending'),'note':'No pass inferred from skipped/pending, planner output or successful adb collection.'}
def validate_corpus():
 tasks=load(P/'tasks.json');fixtures=load(P/'fixtures.json');subset=load(P/'sustained-50.json')['taskIds']
 assert len(tasks)>=100 and len({t['id'] for t in tasks})==len(tasks)
 assert len({t['userGoal'] for t in tasks})==len(tasks)
 assert set(t['category'] for t in tasks)=={'simple','semantic','ocr','vision','singleapp','multiapp','recovery','safety','cancel','privacy'}
 for t in tasks:
  assert all(t[k] for k in ['id','userGoal','initialFixtures','expectedNativePostcondition','prohibitedActions'])
  assert t['initialFixtures']['base'] in fixtures
 assert len(subset)==50 and len(set(subset))==50 and set(subset)<={t['id'] for t in tasks}
 base=load(P/'pending-results.json');a=aggregate(base,tasks);assert a['counts']['pending']==100 and a['successRate']==0
 import copy
 for mutation in ('duplicate','badpass','qualified','unknown','missingfield'):
  bad=copy.deepcopy(base)
  if mutation=='duplicate':bad['results'].append(bad['results'][0])
  if mutation=='badpass':bad['results'][0]['status']='pass'
  if mutation=='qualified':bad['physicalQualification']='qualified'
  if mutation=='unknown':bad['results'][0]['taskId']='invented'
  if mutation=='missingfield':del bad['identity']['modelSha256']
  try:aggregate(bad,tasks)
  except (ValueError,AssertionError):pass
  else:raise AssertionError('Accepted '+mutation)
 skipped=copy.deepcopy(base);skipped['results'][0]['status']='skipped';skipped['results'].pop();s=aggregate(skipped,tasks);assert s['counts']['pending']==99 and s['counts']['skipped']==1 and s['successRate']==0
 return tasks
if __name__=='__main__':
 tasks=validate_corpus()
 print(json.dumps(aggregate(load(sys.argv[1] if len(sys.argv)>1 else P/'pending-results.json'),tasks),indent=2))
 print('PASS: corpus, fixture references, schema and negative aggregation tests (host validation only)',file=sys.stderr)
