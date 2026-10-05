# UnoOne Qwen MNN bridge

Source: Alibaba/MNN https://github.com/alibaba/MNN at exact revision
`024a946b0b8fcf87c8a418229fadd4cd7858ffba` (MNN CMake version 3.6.1).
Archive SHA-256: `7bf677961508973f113889aa8c99851c7426b29837fbdb10d9c94b6dd58800e6`.
MNN is Copyright Alibaba Group Holding Limited, Apache-2.0; see packaged
`licenses/MNN-LICENSE.txt`. Upstream third-party notices remain in the immutable
source archive; distribution must retain applicable component notices.

CMake downloads only that hash-verified archive. For an offline build, populate
CMake FetchContent's cache using that same archive or supply a **pristine checkout
of the exact revision** via `FETCHCONTENT_SOURCE_DIR_MNN`. No model is fetched by
CMake. Model installer must verify its separately pinned artifact manifest.

Actual upstream API inspected: llm.hpp, llm.cpp, omni.cpp, and
speculative_decoding/generate.cpp. Images use imdecode + PromptImagePart, never
file/HTTP prompt references. CPU-only, 2 threads, greedy generation. Android
arm64-v8a only, NDK 27.2.12479018, CMake 3.22.1. Build with max 2 workers.

The application must hold its process-wide model lease across load, generate,
and **successful close acknowledgement**. cancel only sets an atomic flag and
is observed between token calls; long vision/prefill operations are not forcibly
interrupted. close while busy returns false and does not unload. Chunk callbacks
are bytes on the generation thread and may split UTF-8. Cancelled/failed/partial
output must never be executed as actions.

This implementation is not evidence that any model/device passed inference.
Physical text, image, repeated-load/unload, stop/close races, memory, and latency
qualification remain mandatory. Keep candidate disabled until application gates
are satisfied.
