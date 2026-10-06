import pathlib,json,re,hashlib
D=pathlib.Path('/agent/workspace/owl-native-prep/real-probe-final');P=D.parent
fixtures=json.loads((D/'fixture.json').read_text());scores={}
for id in ['A','B']:
 rawpath=D/f'official-gui-{id}.output.txt';codecpath=D/f'production-codec-{id}.output.txt';err=D/f'production-codec-{id}.error.txt'
 s={'synthetic':True,'phone_proof':False,'eos_complete_raw_return':rawpath.exists(),'production_codec_accepted':codecpath.exists(),'search_bbox_normalized':fixtures[id]['search_bbox_normalized']}
 if rawpath.exists():s['exact_raw_model_output']=rawpath.read_text()
 if codecpath.exists():
  c=codecpath.read_text();s['production_codec_result']=c;m=re.match(r'action=([^;]+);x=([^;]+);y=([^;]+);',c)
  if m and m[1]=='click' and m[2]!='null' and m[3]!='null':
   x,y=float(m[2]),float(m[3]);x0,y0,x1,y1=s['search_bbox_normalized'];s.update(point=[x,y],point_inside_search_bbox=x0<=x<=x1 and y0<=y<=y1)
  else:s['point_inside_search_bbox']=False
 elif err.exists():s['production_codec_error']=err.read_text();s['point_inside_search_bbox']=None
 scores[id]=s
(D/'independent-scores.json').write_text(json.dumps(scores,ensure_ascii=False,indent=2))
lines=(D/'stderr.log').read_text(errors='backslashreplace').splitlines();(D/'native-key-lines.txt').write_text('\n'.join(l for l in lines if any(q in l for q in ['Owl admission:','CPU KV buffer','CPU compute buffer','vision','cancel','abort','failed'])))
old=P;before=json.loads((D/'prior-evidence-before.json').read_text());after={str(p.relative_to(old)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['real-probe','real-probe-default'] for p in (old/folder).rglob('*') if p.is_file()}
(D/'prior-evidence-preservation.json').write_text(json.dumps({'unchanged':before==after,'file_count_before':len(before),'file_count_after':len(after),'differences':[k for k in before.keys()|after.keys() if before.get(k)!=after.get(k)]},indent=2))
root=pathlib.Path('/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent')
files=[root/'localbrain/src/main/cpp/owl_jni.cpp',root/'localbrain/src/main/java/com/unoone/agent/localbrain/owl/OwlLlamaRuntime.kt']
(D/'production-source-hashes.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
(D/'evidence-manifest.sha256.json').write_text(json.dumps({str(p.relative_to(D)):hashlib.sha256(p.read_bytes()).hexdigest() for p in D.rglob('*') if p.is_file() and p.name!='evidence-manifest.sha256.json'},indent=2))
print(json.dumps(scores,ensure_ascii=False,indent=2));print('prior preserved',before==after)
