#!/usr/bin/env python3
"""Standalone Kotlin/JUnit evaluation only; no Gradle, JNI load or model inference."""
import json, os, pathlib, subprocess, tempfile
here = pathlib.Path(__file__).resolve().parent
repo = here.parents[1]
archive = repo / 'docs/evidence/floating-voice-prompt'
# Preserve historical commands; derive an isolated host-only test compilation.
cmd = json.loads((archive / 'voice-prompt-v2/compile-command.json').read_text())
source_start = next(i for i, arg in enumerate(cmd) if arg.endswith('/Probe.kt'))
cmd = cmd[:source_start]
out = pathlib.Path(tempfile.mkdtemp(prefix='unoone-prompt-negative-'))
cmd[cmd.index('-d') + 1] = str(out)
cache = pathlib.Path(os.environ.get('GRADLE_USER_HOME', '/agent/workspace/toolchains/gradle-home')) / 'caches/modules-2/files-2.1'
def jar(group, artifact, version):
    found = sorted((cache / group / artifact / version).glob('*/*.jar'))
    if not found: raise SystemExit(f'Missing preinstalled dependency: {group}:{artifact}:{version}')
    return str(found[0])
extra = [jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
cp_i = cmd.index('-classpath') + 1
cmd[cp_i] += ':' + ':'.join(extra)
core = repo / 'android-app/UnoOneAgent/core/src'
main = core / 'main/java/com/unoone/agent/core/guiowl'
cmd += [str(main / name) for name in ['OwlPromptBuilder.kt', 'OwlOutputCodec.kt', 'OwlNativeBinding.kt']]
cmd += [str(here / 'src/newScopedOwlPrompt.kt'), str(core / 'test/java/com/unoone/agent/core/guiowl/OwlProtocolTest.kt')]
cmd += [str(p) for p in sorted((here / 'tests').glob('*.kt'))]
(out / 'compile-command.json').write_text(json.dumps(cmd, indent=2))
subprocess.run(cmd, check=True)
classes = ['ScopedOwlPromptTest', 'ScopedOwlPromptV2Test', 'RejectedRawOutputTest']
run = [cmd[0], '-Xmx256m', '-Dvoice.evidence=' + str(archive), '-cp', str(out) + ':' + cmd[cp_i], 'org.junit.runner.JUnitCore'] + ['com.unoone.agent.core.guiowl.' + c for c in classes]
(out / 'test-command.json').write_text(json.dumps(run, indent=2))
subprocess.run(run, check=True)
print('Evaluation-only receipts/classes:', out)
