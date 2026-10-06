import pathlib,json,subprocess,hashlib
from PIL import Image,ImageDraw,ImageFont
D=pathlib.Path('/agent/workspace/owl-native-prep/real-probe-default');P=D.parent
old=P/'real-probe'
# Immutable prior evidence fingerprint; never overwrite prior evidence.
(D/'prior-evidence-before.json').write_text(json.dumps({str(p.relative_to(old)):hashlib.sha256(p.read_bytes()).hexdigest() for p in old.rglob('*') if p.is_file()},indent=2))
fixtures={}
for id in ['A','B']:
 im=Image.new('RGB',(256,256),'#f3eee4');d=ImageDraw.Draw(im)
 f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',16);small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',11)
 d.text((12,10),'SYNTHETIC GUI TEST '+id,font=small,fill='#222222')
 buttons= [('Home',(12,45,112,97),'#255b96'),('Settings',(12,145,112,205),'#747474'),('Search',(134,145,246,205),'#bc153f')] if id=='A' else [('Search',(12,45,124,105),'#bc153f'),('Settings',(12,145,112,205),'#747474'),('Home',(134,145,246,205),'#255b96')]
 for label,box,color in buttons:
  d.rounded_rectangle(box,radius=8,fill=color);d.text(((box[0]+box[2])/2,(box[1]+box[3])/2),label,font=f,fill='white',anchor='mm')
 im.save(D/f'synthetic-gui-{id}-256.png');box=next(b for l,b,c in buttons if l=='Search')
 fixtures[id]={'synthetic':True,'real_phone_proof':False,'buttons':buttons,'search_bbox_pixels':box,'search_bbox_normalized':[v/256*1000 for v in box]}
(D/'fixture.json').write_text(json.dumps(fixtures,indent=2));Image.new('RGB',(256,256),'red').save(D/'synthetic-red-256.png')
cmd=json.loads((old/'compile-command.json').read_text());cmd=[s.replace('/real-probe/','/real-probe-default/') for s in cmd];cmd.append('/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/guiowl/OwlOutputCodec.kt')
(D/'compile-command.json').write_text(json.dumps(cmd,indent=2))
with (D/'compile.log').open('w') as out:subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,check=True)
files=[P/'production-host/libunoone_owl.so',P/'production-host/classes/com/unoone/agent/localbrain/owl/OwlLlamaRuntime.class']+[pathlib.Path(s) for s in cmd if s.endswith('.kt')]
(D/'source-binary-hashes.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
print('Prepared production-builder/codec harness; compiler exited')
