package com.unoone.agent.modelmanager

/** Immutable upstream release identity; metadata verification is not device qualification. */
object QwenMnnArtifact {
    const val REVISION = "35781816d7b6a9dcb273a6765ac9563401951c3c"
    const val MANIFEST_ID = "qwen3.5-2b-mnn"
    const val LICENSE = "Apache-2.0"
    val files: List<ModelFile> = listOf(
        ModelFile(name = "config.json", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/config.json", sha256 = "92853033efe602f95efca3e1c05cd8b108f973c8beed417843a9671f8147ed8d", sizeBytes = 652L),
        ModelFile(name = "export_args.json", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/export_args.json", sha256 = "a5b3a7d2c45e53c887e539259696d5fcec793637c9a3e9fbc7cd0dd008af6a87", sizeBytes = 1040L),
        ModelFile(name = "llm.mnn", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm.mnn", sha256 = "23df98f8b341b277365e0bbca025c1d192939e3d32d7f79776352c6f32e77960", sizeBytes = 2148136L),
        ModelFile(name = "llm.mnn.json", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm.mnn.json", sha256 = "7131ff4f1a441add1039d371815ad94652ae6801f579591cb2f9ad90b5954025", sizeBytes = 5344018L),
        ModelFile(name = "llm.mnn.weight", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm.mnn.weight", sha256 = "c93f71a2dbecf9328782bd38861656d8faa82e95e7f99607350074768a482054", sizeBytes = 1176647702L),
        ModelFile(name = "llm_config.json", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm_config.json", sha256 = "a88234b36c2af0eff8e5c89667011badf71c15e30459eb0e21030a8f3f9ed240", sizeBytes = 8692L),
        ModelFile(name = "tokenizer.txt", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/tokenizer.txt", sha256 = "7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4", sizeBytes = 6465727L),
        ModelFile(name = "visual.mnn", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/visual.mnn", sha256 = "88fc40a7b676e90eb2cb86d854db15cb90b9eb1f34087ab0f48c5e43572c8dac", sizeBytes = 488096L),
        ModelFile(name = "visual.mnn.weight", url = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/visual.mnn.weight", sha256 = "8f90e106f5b9ae9a939faed240305cfdd5c6740ae91d3fc418a990bee0cce36b", sizeBytes = 195587264L)
    )
}
