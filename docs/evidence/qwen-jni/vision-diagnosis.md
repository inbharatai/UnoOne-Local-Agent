# Qwen native vision diagnosis — controlled production JNI comparison

## Result
Same exact production Kotlin runtime, native owner, model files, greedy policy, prompts, and diagnostic harness.jar. Only the JNI processor policy/bounded resize admission changed. Expected color remains **red** for BOTH fixtures; no assertion weakened. Original red32 white failure remains untouched in qwen-jni-host-validation and duplicated in before/original-*.

| Policy | Input | Raw returned output | Callback exact equality |
|---|---|---|---|
| before | red 32x32 | `white` | True |
| before | red 256x256 | `red` | True |
| after | red 32x32 | `red` | True |
| after | red 256x256 | `red` | True |

The controlled baseline independently reproduces red32 → white and red256 → red through the SAME JNI bridge (not a CLI-vs-JNI confound). After restoration both return red. This establishes the incorrect processor minimum as the cause of this fixture regression; it does not establish broad model accuracy.

## Actual graph/config and diagnosis
Model llm_config.json reports model_type=qwen3_5, mean=[127.5]*3, norm=[0.00784313725490196]*3. Saved verified visual graph inputs are patches, position_ids, attention_mask, idx_tensor, weight_tensor. Pinned MNN 024a946 omni.cpp:740–774 selects isQwen3VL from five inputs with idx_tensor at index 3: patch=16, merge=2, alignment=32, DEFAULT minimum=65536. Original wrapper forcibly set 784. No model config file was edited.
At 784, red32 stays 32x32 (4 raw spatial patches, 1 merged image token). At 65536 it becomes 256x256 (256 raw spatial patches, 64 merged image tokens), same geometry as the successful 256 fixture. Restored graph-selected defaults: Qwen3 path min=65536/factor32, audited Qwen2 path min=3136/factor28; max=589824 remains. Unsupported graph signatures reject at load.

## Bounds — actual upstream smart resize, exhaustive differential test
Input decoding remains 32..768 on EACH axis and <=589824 input pixels. Output encoder dimensions are NOT each <=768: very thin inputs may upscale beyond that. Total output area is independently computed and checked BEFORE decode/encoder, failing closed on invalid/over-budget resize. Integer products use wide arithmetic. Resize guard mirrors pinned upstream ties-to-even float rounding and double sqrt/floor/ceil, not guessed nearest rounding.
```
factor=28 min=3136 cases=543169 maxSide=756 maxArea=571536
factor=32 min=65536 cases=543169 maxSide=1280 maxArea=589824
```
543169 accepted width/height combinations per supported processor (737²) compared exactly against a verbatim standalone copy of upstream qwenVlSmartResize; every output coordinate matched, every area stayed bounded. Actual Qwen3 maximum axis=1280, maximum area=589824. Qwen2 maximum axis=756, maximum area=571536. Production unit test exhausts both grids, verifies red32/red256 become256², thin output exceeds768, and invalid factor/pixel budget rejects.

Observed receipt assertions passed: before minimum784 → after65536; maximum remains589824. Production Kotlin, strict image header parser, request epoch header, and libMNN SHA-256 all match original build evidence.

## Color-order verification
**Correction to prior shorthand:** the saved original CLI red.png actually decodes as **224x224**, not256x256. The new controlled JNI diagnostic deliberately uses a separately generated actual256x256 fixture alongside the unchanged32x32 fixture.
CLI Omni path uses CV::imread; JNI uses CV::imdecode(...IMREAD_COLOR). Both call the same buildImgVARP: stb RGB bytes → RGB2BGR; qwen2VisionProcess converts BGR2RGB while resizing and normalizing. A native byte probe verified ALL decoded bytes, including the original CLI red fixture:
```
The device supports: i8sdot:0, fp16:0, i8mm: 0, sve2: 0, sme2: 0, perfCores: 0
${DIAGNOSTIC}/before/red32.png 32x32 imread==imdecode all 3072 bytes; every BGR pixel=(0,0,255)
${DIAGNOSTIC}/before/red256.png 256x256 imread==imdecode all 196608 bytes; every BGR pixel=(0,0,255)
${MNN_HOST}/red.png 224x224 imread==imdecode all 150528 bytes; every BGR pixel=(0,0,255)
```

## Builds and safety
Host JNI rebuilt from actual production qwen_jni.cpp with -Wl,-z,defs and unchanged host libMNN. Android arm64 incrementally rebuilt with ninja -j1 unoone_qwen: exactly C++ compile + link, no Gradle. First background-shell Android attempt was interrupted before completion; synchronous retry exited0, saved android-build.log. Production Kotlin unchanged. Native blockers regression passes; no edits to image_admission.h or request_epoch.h. Encoded byte cap, strict PNG/JPEG header gates, actual-token decoder admission, stale permits and cancellation retained.
Runs serialized after builds and automatic heavy-process preflight; no overlapping model/compiler/Gradle. AS=6GiB, sampled process-group RSS cap=2.8GiB (3,006,477,107 bytes), 100ms poll, 110s wall. Conditional near-cgroup guard: >=3.9GB AND (clean reclaimable cache<400MB OR estimated unreclaimable>=3.5GB). Owned-file POSIX_FADV_DONTNEED only; never global cache drops or unrelated process kills. Full samples/config/receipts in before/after.

| Run | Exit | Seconds | Peak sampled RSS bytes | Peak HWM bytes | Stop reason |
|---|---:|---:|---:|---:|---|
| before | 1 | 39.449 | 2044821504 | 2088931328 | None |
| after | 0 | 48.539 | 2080821248 | 2096349184 | None |

Memory events unchanged before: True; after: True. Historical oom_kill=1 remains historical.

## After full harness output
```
ACTUAL_HOST os=Linux arch=amd64
STATIC_UNMEASURED_BACKEND_LABEL=MNN CPU arm64 / 024a946b0b8fcf87c8a418229fadd4cd7858ffba
PASS load/config-receipt
PASS stale-permit-before-generate
PASS stale-no-callback
PASS empty-image-size
PASS oversize-image-size
PASS invalid-image-header
PASS over-context-before-decoder
PASS text-json-full-output
PASS real-image-bytes-full-output-32
PASS real-image-bytes-full-output-256
PASS close-ack
PASS reload
PASS reload-close-ack
FINAL_CLOSE_ACK=true
ALL_HARNESS_ASSERTIONS_PASSED
The device supports: i8sdot:0, fp16:0, i8mm: 0, sve2: 0, sme2: 0, perfCores: 0
```

## Key SHA-256 evidence (complete source/model/output inventory: sha256.json)

- `${DIAGNOSTIC}/harness.jar`: `0d1a72dba56f0688a03bd089cc29ed1c07cd67cb75298d9a0ce4f2b471053634`
- `${DIAGNOSTIC}/before/libunoone_qwen.so`: `c8b200d125e38ba85e67c3bffa4f25e5d7e17ca0229833a421a3a2b267b5d898`
- `${DIAGNOSTIC}/after/libunoone_qwen.so`: `0affc14993a57e6c2e7d8311ecf34a0fa39c5cd9fa37ea7b1d9e5b5987fc7b16`
- `${DIAGNOSTIC}/before/red32.png`: `becf0cf30d6978325d9ccdea315923e2dbbf7e591c2857b92b5f82e41957bb98`
- `${DIAGNOSTIC}/before/red256.png`: `4f55763bbb5f30e77c6a573b1c56d42126c2febfa2686da24fc32e2e76d99ce0`
- `${DIAGNOSTIC}/before/image-32-output.txt`: `018fa96a44715c90bf93be148069cb28dd45d398f2cc75aa1565311f6e55d174`
- `${DIAGNOSTIC}/after/image-32-output.txt`: `b1f51a511f1da0cd348b8f8598db32e61cb963e5fc69e2b41485bf99590ed75a`
- `${DIAGNOSTIC}/after/image-256-output.txt`: `b1f51a511f1da0cd348b8f8598db32e61cb963e5fc69e2b41485bf99590ed75a`
- `${REPO}/android-app/UnoOneAgent/localbrain/src/main/cpp/qwen_jni.cpp`: `9bba13ed5277c51ec11de2c1881d872e25bbdfabf930c0fd56117c28829240e6`
- `${REPO}/android-app/UnoOneAgent/localbrain/src/main/cpp/vision_budget.h`: `b1f4381499e41deb419df0df20db86ca6390657de2d1dd9ce067e3ccd09b7214`
- `${MNN_HOST}/build/libMNN.so`: `ddcb418443e7ae0adbcfdffc28d5d52c0339f2d33f901255b5b9c931a1135475`

Evidence root: ${DIAGNOSTIC}/. Original failure evidence was never overwritten. Same model config path and weights in both runs; complete actual model hashes saved after runs. No model-content writes performed. No commits. Android link success is not Android-device inference validation.
