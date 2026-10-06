---
license: mit
language:
- en
tags:
- arxiv:2602.16855
---

## Introduction
<img src="https://github.com/X-PLUG/MobileAgent/blob/main/Mobile-Agent-v3.5/assets/gui_owl_15_logo.png?raw=true" width="80%"/>

GUI-Owl 1.5 is the next-generation native GUI agent model family built on Qwen3-VL. It supports multi-platform GUI automation across desktops, mobile devices, browsers, and more. Powered by a scalable hybrid data flywheel, unified agent capability enhancement, and multi-platform environment RL (MRPO), GUI-Owl 1.5 offers a full spectrum of models.

* **Paper**: https://arxiv.org/abs/2602.16855
* **GitHub Repository**: https://github.com/X-PLUG/MobileAgent
* **Online Demo**: http://modelscope.cn/studios/MobileAgentTest/computer_use
  
**Key highlights:**
- 🏆 **State-of-the-art** among multi-platform GUI models on OSWorld-Verified, AndroidWorld, Mobile-World, WindowsAA, ScreenSpot-v2, ScreenSpot-Pro, and more.
- 🔧 **Tool & MCP calling**: Native support for external tool invocation and MCP server coordination, achieving top performance on OSWorld-MCP and Mobile-World.
- 🧠 **Long-horizon memory**: Built-in memory capability without external workflow orchestration, leading all native agent models on MemGUI-Bench.
- 🤝 **Multi-agent ready**: Serves both as a standalone end-to-end agent and as specialized roles (planner, executor, verifier, notetaker) within the Mobile-Agent-v3.5 framework.
- ⚡ **Instruct & Thinking variants**: Smaller instruct models for fast inference and edge deployment; larger thinking models for complex tasks requiring planning and reflection.


## Performance
### End-to-End Online Benchmarks

| Model | OSWorld-Verified | AndroidWorld | OSWorld-MCP | Mobile-World | WindowsAA | WebArena | VisualWebArena | WebVoyager | Online-Mind2Web
|-------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| GUI-Owl-1.5-2B-Instruct | 43.5 | 67.9 | 33.0 | 31.3 | 25.8 | - | - | - | - |
| GUI-Owl-1.5-4B-Instruct | 48.2 | 69.8 | 31.7 | 32.3 | 29.4 | - | - | - | - |
| GUI-Owl-1.5-8B-Instruct | 52.3 | 69.0 | 41.8 | 41.8 | 31.7 | 45.7 | 39.4 | 69.9 | 41.7 |
| GUI-Owl-1.5-8B-Thinking | 52.9 | **71.6** | 38.8 | 33.3 | 35.1 | 46.7 | 40.8 | 78.1 | **48.6** |
| GUI-Owl-1.5-32B-Instruct | **56.5** | 69.4 | **47.6** | **46.8** | **44.8** | - | - | - | - |
| GUI-Owl-1.5-32B-Thinking | 56.0 | 68.2 | 43.8 | 42.8 | 44.1 | **48.4** | **46.6** | **82.1** | - |


### Grounding Benchmarks

Please refer to the technical report for detailed results on ScreenSpot-v2, ScreenSpot-Pro, OSWorld-G, MMBench-GUI, and more.

## Usage

Please refer to our cookbook.

## Deploy

We recommand deploy GUI-Owl-1.5 through vllm

This script has been validated on an A100 with 96 GB of VRAM.
```bash
PIXEL_ARGS='{"size": {"longest_edge": 3072000, "shortest_edge": 65536}}'
IMAGE_LIMIT_ARGS='image=5'
MP_SIZE=1

vllm serve $CKPT \
    --max-model-len 32768 \
    --mm-processor-kwargs "$PIXEL_ARGS" \
    --limit-mm-per-prompt "$IMAGE_LIMIT_ARGS" \
    --tensor-parallel-size $MP_SIZE \
    --allowed-local-media-path '/' \
    --port 4243 \
```

## Citation

If you find this model useful, please cite our paper:

```bibtex
@article{MobileAgentv3.5,
  title={Mobile-Agent-v3.5: Multi-platform Fundamental GUI Agents},
  author={Haiyang Xu, Xi Zhang, Haowei Liu, Junyang Wang, Zhaozai Zhu, Shengjie Zhou, Xuhao Hu, Feiyu Gao, Junjie Cao, Zihua Wang, Zhiyuan Chen, Jitong Liao, Qi Zheng, Jiahui Zeng, Ze Xu, Shuai Bai, Junyang Lin, Jingren Zhou, Ming Yan},
  journal={arXiv preprint arXiv:2602.16855},
  year={2026}
}
