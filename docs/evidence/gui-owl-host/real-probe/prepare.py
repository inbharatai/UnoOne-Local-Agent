import pathlib,glob,subprocess,json,hashlib
from PIL import Image,ImageDraw,ImageFont
D=pathlib.Path('/agent/workspace/owl-native-prep/real-probe'); P=D.parent; R=pathlib.Path('/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent');java='/agent/workspace/toolchains/jdk17/bin/java'
im=Image.new('RGB',(256,256),'#f3eee4');d=ImageDraw.Draw(im)
f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',16);small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',11)
d.text((12,10),'SYNTHETIC GUI TEST',font=small,fill='#222222')
buttons=[('Home',(12,45,112,97),'#255b96'),('Settings',(12,145,112,205),'#747474'),('Search',(134,145,246,205),'#bc153f')]
for label,box,color in buttons:
 d.rounded_rectangle(box,radius=8,fill=color);d.text(((box[0]+box[2])/2,(box[1]+box[3])/2),label,font=f,fill='white',anchor='mm')
im.save(D/'synthetic-gui-256.png');Image.new('RGB',(256,256),'red').save(D/'synthetic-red-256.png')
(D/'fixture.json').write_text(json.dumps({'synthetic':True,'real_phone_proof':False,'buttons':buttons,'search_center_pixels':[190,175],'search_center_normalized':[742.1875,683.59375],'search_bounds_normalized':[523.4375,566.40625,960.9375,800.78125]},indent=2))
cp=(P/'production-host/runtime-classpath.txt').read_text();cache='/agent/workspace/toolchains/gradle-home/caches/modules-2/files-2.1/'
compiler=cp.split(':')+sum([glob.glob(cache+p,recursive=True) for p in ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21/**/*.jar','org.jetbrains.kotlin/kotlin-reflect/**/*.jar','org.jetbrains.kotlin/kotlin-script-runtime/**/*.jar','org.jetbrains.intellij.deps/trove4j/**/*.jar']],[])
cmd=[java,'-Xmx256m','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',str(P/'production-host/classes')+':'+cp,'-d',str(D/'classes'),str(D/'Probe.kt'),str(R/'core/src/main/java/com/unoone/agent/core/guiowl/OwlPromptBuilder.kt')]
(D/'compile-command.json').write_text(json.dumps(cmd,indent=2))
with open(D/'compile.log','w') as f:subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,check=True)
files=[P/'production-host/libunoone_owl.so',R/'localbrain/src/main/cpp/owl_jni.cpp',R/'localbrain/src/main/java/com/unoone/agent/localbrain/owl/OwlLlamaRuntime.kt',R/'core/src/main/java/com/unoone/agent/core/guiowl/OwlPromptBuilder.kt',D/'Probe.kt',D/'synthetic-gui-256.png',D/'synthetic-red-256.png']
(D/'source-binary-fixture-hashes.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
print('Harness and actual OwlPromptBuilder compiled; no model loaded')
