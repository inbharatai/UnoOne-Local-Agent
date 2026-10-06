import pathlib,json,hashlib,shutil,subprocess,time
from PIL import Image,ImageDraw,ImageFont
D=pathlib.Path('/agent/workspace/owl-native-prep/voice-prompt-v2');old=D.parent/'voice-prompt-ab'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
prior=json.loads((old/'source-binary-hashes-before.json').read_text())
for p,h in prior.items():
 if not p.endswith('newScopedOwlPrompt.kt'):assert sha(pathlib.Path(p))==h,('changed',p)
shutil.copytree(old/'pinned-host',D/'pinned-host')
root=pathlib.Path('/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core')
fixtures=[dict(id='N1',operation='CLICK',label='Library',bbox=[16,90,126,132],focused=False),dict(id='N2',operation='CLICK',label='Settings',bbox=[128,182,240,226],focused=False),dict(id='F1',operation='FOCUS',label='Search query',bbox=[24,152,230,193],focused=False),dict(id='W1',operation='WRITE',label='Draft title',bbox=[24,72,230,115],focused=True,value='AbC 42')]
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',14);small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',10)
for f in fixtures:
 im=Image.new('RGB',(256,256),'#edf0f5');dr=ImageDraw.Draw(im);dr.rectangle([0,0,256,37],fill='#1c2940');dr.text((10,12),'SYNTHETIC GUI / TEST',font=small,fill='white')
 dr.text((14,48),'Demo workspace',font=font,fill='#202535')
 b=f['bbox'];field=f['operation'] in ['FOCUS','WRITE'];dr.rounded_rectangle(b,radius=6,fill='white' if field else '#345dd1',outline='#1267ed' if f['focused'] else '#8290a7',width=3 if f['focused'] else 1)
 dr.text((b[0]+9,b[1]+12),f['label'] if not f['focused'] else '',font=font,fill='#414b5c' if field else 'white')
 if f['focused']:
  dr.text((24,56),f['label'],font=small,fill='#303848');dr.line([36,83,36,103],fill='#1267ed',width=2);dr.text((24,127),'Field focused',font=small,fill='#1267ed')
 if f['id']=='N1':dr.rounded_rectangle([142,168,240,209],radius=6,fill='#d8dfe9');dr.text((157,181),'Settings',font=font,fill='#303848')
 if f['id']=='N2':dr.rounded_rectangle([18,82,124,124],radius=6,fill='#d8dfe9');dr.text((40,95),'Library',font=font,fill='#303848')
 if f['id']=='F1':dr.text((26,90),'Find an item locally',font=small,fill='#303848')
 dr.text((12,239),'No real app / no dispatched actions',font=small,fill='#586477')
 p=D/(f['id']+'.png');im.save(p);f['image_sha256']=sha(p);f['bbox_normalized']=[v/256*1000 for v in b];f['truth_origin']='renderer geometry frozen before any model call';f['native_semantic']='FORM_FIELD' if field else 'NAVIGATION'
(D/'fixtures.json').write_text(json.dumps(fixtures,indent=2));(D/'fixtures-frozen-at.txt').write_text(str(time.time()))
cmd=[x.replace('voice-prompt-ab','voice-prompt-v2') for x in json.loads((old/'compile-command.json').read_text())]
deps=str(root/'build/tmp/kotlin-classes/debug');idx=cmd.index('-classpath')+1;cmd[idx]+=':'+deps
cmd.append(str(root/'src/main/java/com/unoone/agent/core/guiowl/OwlNativeBinding.kt'))
(D/'compile-command.json').write_text(json.dumps(cmd,indent=2))
with (D/'compile.log').open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
(D/'pinned-host/runtime-classpath.txt').write_text((D/'pinned-host/runtime-classpath.txt').read_text()+':'+deps)
files=[pathlib.Path(p) for p in prior]+list((D/'pinned-host').rglob('*.class'))+list((D/'pinned-host').glob('*.so'))+list((root/'src/main/java/com/unoone/agent/core/device').glob('*.kt'))+list(pathlib.Path(deps).rglob('*.class'))
(D/'source-binary-hashes-before.json').write_text(json.dumps({str(p):sha(p) for p in files},indent=2))
shutil.copy2(root/'src/main/java/com/unoone/agent/core/guiowl/newScopedOwlPrompt.kt',D/'candidate-source.kt')
s=(old/'monitor.py').read_text().replace('voice-prompt-ab','voice-prompt-v2');(D/'monitor.py').write_text(s)
print('Prepared new fixtures and compiled actual V2, production codec/binding. Runtime identities preserved. Compiler exited.')
