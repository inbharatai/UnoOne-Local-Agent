import glob,os,subprocess,sys
from pathlib import Path
import glob,os,subprocess,sys
cache='/agent/workspace/toolchains/gradle-home/caches/modules-2/files-2.1'
def jars(p): return glob.glob(cache+'/'+p,recursive=True)
runtime=sum([jars(p) for p in ['org.jetbrains.kotlin/kotlin-stdlib/2.2.21/**/*.jar','junit/junit/4.13.2/**/*.jar','org.hamcrest/hamcrest-core/1.3/**/*.jar','org.jetbrains/annotations/**/*.jar']],[])
compiler=runtime+sum([jars(p) for p in ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21/**/*.jar','org.jetbrains.kotlin/kotlin-reflect/**/*.jar','org.jetbrains.kotlin/kotlin-script-runtime/**/*.jar','org.jetbrains.intellij.deps/trove4j/**/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/**/*.jar']],[])
r='/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent'

w=Path('/agent/workspace/owl-native-prep/race-tests');w.mkdir(exist_ok=True)
java='/agent/workspace/toolchains/jdk17/bin/java'; jdk=Path('/agent/workspace/toolchains/jdk17')
test=Path(__file__).resolve().parent / 'OwlLoadRaceTest.kt'
root=w/'bundle';root.mkdir(exist_ok=True)
for name,size in [('GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf',2497282208),('GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf',453974336)]:
 with (root/name).open('wb') as f:f.truncate(size)
subprocess.run(['g++','-std=c++17','-shared','-fPIC','-I'+str(jdk/'include'),'-I'+str(jdk/'include/linux'),str(Path(__file__).resolve().parent / 'owl-race-native.cpp'),'-o',str(w/'libunoone_owl.so')],check=True)
runtime+=jars('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/**/*.jar')+glob.glob(r+'/*/build/tmp/kotlin-classes/debug')
base=Path(r);sources=[str(base/'localbrain/src/main/java/com/unoone/agent/localbrain/owl'/n) for n in ['OwlLlamaRuntime.kt','OwlLlamaPlanner.kt']]+[str(base/'core/src/main/java/com/unoone/agent/core/model/E4bRuntimeCoordinator.kt'),str(test)]
subprocess.run([java,'-Xmx512m','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(runtime),'-d',str(w/'classes')]+sources,check=True)
subprocess.run([java,'-Djava.library.path='+str(w),'-Drace.root='+str(root),'-cp',str(w/'classes')+':'+':'.join(runtime),'com.unoone.agent.localbrain.owl.OwlLoadRaceTestKt'],check=True)
# Compile exact production continuation block against pinned actual llama_batch ABI.
s=(base/'localbrain/src/main/cpp/owl_jni.cpp').read_text();block=s[s.index(' llama_token token[1]'):s.index('next++;check(*h);')]
validator=s[s.index('void strictUtf8'):s.index('void plain')]
cpp='''#include "llama.h"
#include <cassert>
#include <stdexcept>
#include <string>
static int mode=0;
int llama_decode(llama_context*,llama_batch b){assert(b.n_tokens==1&&b.token[0]==42&&b.embd==nullptr&&b.n_seq_id[0]==1&&b.seq_id[0][0]==0&&b.logits[0]==1);for(int i=0;i<4;i++)assert(b.pos[i]==17);if(mode==1)throw std::runtime_error("shim");return mode==2?1:0;}
'''+validator+'''\nvoid run(){struct H{struct C{llama_context*get(){return nullptr;}}ctx;};H hh;H*h=&hh;llama_token tok=42;llama_pos next=17;'''+block+'''}
int main(){run();for(mode=1;mode<=2;mode++){bool threw=false;try{run();}catch(const std::runtime_error&){threw=true;}assert(threw);}strictUtf8("\\xF0\\x9F\\x98\\x80\\xF4\\x8F\\xBF\\xBF");for(auto s:{"\\xF0\\x9F","\\xF4\\x90\\x80\\x80","\\xED\\xA0\\x80"}){bool threw=false;try{strictUtf8(s);}catch(...){threw=true;}assert(threw);}}
'''
(w/'batch.cpp').write_text(cpp)
inc='/agent/workspace/owl-native-prep/llama.cpp-4f5406761517648c23dbd60ea5ade37f77a316c9'
subprocess.run(['g++','-std=c++17','-I'+inc+'/include','-I'+inc+'/ggml/include',str(w/'batch.cpp'),'-o',str(w/'batch')],check=True)
subprocess.run([str(w/'batch')],check=True)
print('PASS exact production stack batch ABI/4 planes, throwing/failing decode, wide/invalid UTF scalar tests')
