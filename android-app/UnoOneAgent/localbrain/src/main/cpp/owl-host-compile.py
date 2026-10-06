#!/usr/bin/env python3
"""Compile actual production Owl JNI + Kotlin, never load weights. No Gradle.
Usage: owl-host-compile.py PINNED_SOURCE UPSTREAM_BUILD JAVA_HOME OUTPUT_DIR
Requires existing Kotlin compiler/dependency jars in GRADLE_USER_HOME/caches.
"""
import glob, os, pathlib, subprocess, sys
source, build, java, output = map(pathlib.Path, sys.argv[1:5])
output.mkdir(parents=True, exist_ok=True)
cpp=pathlib.Path(__file__).resolve().parent
root=cpp.parents[3]
cmd=['g++','-std=c++17','-O1','-fPIC','-shared',str(cpp/'owl_jni.cpp')]
cmd += ['-I'+str(p) for p in [java/'include',java/'include/linux',source/'include',source/'ggml/include',source/'tools/mtmd',source/'vendor/stb']]
cmd += ['-L'+str(build/'bin'),'-Wl,-rpath,'+str(build/'bin'),'-Wl,--no-undefined','-lmtmd','-lllama','-lggml','-lggml-base','-pthread','-o',str(output/'libunoone_owl.so')]
subprocess.run(cmd,check=True)
cache=os.environ.get('GRADLE_USER_HOME','/agent/workspace/toolchains/gradle-home')+'/caches/modules-2/files-2.1'
def jars(p):return glob.glob(cache+'/'+p,recursive=True)
runtime=sum([jars(p) for p in ['org.jetbrains.kotlin/kotlin-stdlib/2.2.21/**/*.jar','org.jetbrains/annotations/**/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/**/*.jar','org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.8.0/**/*.jar','org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.8.0/**/*.jar']],[])
compiler=runtime+sum([jars(p) for p in ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21/**/*.jar','org.jetbrains.kotlin/kotlin-reflect/**/*.jar','org.jetbrains.kotlin/kotlin-script-runtime/**/*.jar','org.jetbrains.intellij.deps/trove4j/**/*.jar']],[])
model=root/'core/src/main/java/com/unoone/agent/core/model'
sources=[model/f for f in ['BrainModel.kt','GuiOwlArtifact.kt','E4bRuntimeCoordinator.kt','Result.kt']]+list((cpp.parent/'java/com/unoone/agent/localbrain/owl').glob('*.kt'))
subprocess.run([str(java/'bin/java'),'-Xmx512m','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(runtime),'-d',str(output/'classes')]+[str(p) for p in sources],check=True)
(output/'compile.complete').write_text('Production owl_jni.cpp and Owl Kotlin compiled; NO inference performed.\n')
(output/'runtime-classpath.txt').write_text(':'.join(runtime))
