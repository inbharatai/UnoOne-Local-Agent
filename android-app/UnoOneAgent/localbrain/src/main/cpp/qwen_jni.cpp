#include <jni.h>
#include <llm/llm.hpp>
#include <cv/imgcodecs.hpp>
#include "vision_budget.h"
#include "request_epoch.h"
#include "image_admission.h"
#include <MNN/expr/Module.hpp>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <stdexcept>
#include <streambuf>
#include <algorithm>
#include <rapidjson/document.h>
#include <rapidjson/writer.h>
#include <rapidjson/stringbuffer.h>

using MNN::Transformer::Llm;
namespace {
struct Runtime {
    std::unique_ptr<Llm, void(*)(Llm*)> llm{nullptr, Llm::destroy};
    std::mutex operation;
    RequestEpoch cancellation;
    int contextLimit = 2048, maxOutput = 256;
    std::string receipt;
    bool visual = false;
    int imagePad = 151859;
    int imageFactor = 0, imageMinPixels = 0;
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Runtime>> registry;
jlong nextHandle = 1;
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::shared_ptr<Runtime> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = registry.find(handle);
    if (it == registry.end()) throw std::runtime_error("Stale native runtime handle");
    return it->second;
}
std::string bytes(JNIEnv* env, jbyteArray array, size_t limit) {
    if (!array) throw std::runtime_error("Missing input");
    const auto size = env->GetArrayLength(array);
    if (size < 0 || static_cast<size_t>(size) > limit) throw std::runtime_error("Input limit exceeded");
    std::string result(size, '\0');
    if (size) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(&result[0]));
    if (env->ExceptionCheck()) throw std::runtime_error("JNI input copy failed");
    return result;
}
jbyteArray array(JNIEnv* env, const std::string& text) {
    auto result = env->NewByteArray(static_cast<jsize>(text.size()));
    if (result && !text.empty()) env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
    return result;
}
// Reject invalid, overlong and incomplete UTF-8 instead of replacement-decoding actions.
bool validUtf8(const std::string& s) {
    for (size_t i = 0; i < s.size();) {
        unsigned char c = s[i++];
        if (c < 0x80) { if (!c) return false; continue; }
        int n; unsigned int value, minimum;
        if (c >= 0xc2 && c <= 0xdf) { n=1; value=c&31; minimum=0x80; }
        else if (c >= 0xe0 && c <= 0xef) { n=2; value=c&15; minimum=0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { n=3; value=c&7; minimum=0x10000; }
        else return false;
        if (i+n > s.size()) return false;
        while (n--) { unsigned char d=s[i++]; if ((d&0xc0)!=0x80) return false; value=(value<<6)|(d&63); }
        if (value < minimum || value > 0x10ffff || (value>=0xd800 && value<=0xdfff)) return false;
    }
    return true;
}
// MNN flushes decoded token bytes to this stream on the calling generation thread.
struct Output : std::streambuf {
    JNIEnv* env; jobject callback; jmethodID method; Runtime& runtime;
    std::string result, pending;
    Output(JNIEnv* e, jobject c, Runtime& r) : env(e), callback(c), runtime(r) {
        auto cls = env->GetObjectClass(c);
        method = env->GetMethodID(cls, "onChunk", "([B)V"); env->DeleteLocalRef(cls);
        if (!method) throw std::runtime_error("Missing chunk callback");
    }
    std::streamsize xsputn(const char* s, std::streamsize count) override {
        if (runtime.cancellation.cancelled.load() || env->ExceptionCheck()) return count;
        if (count < 0 || result.size() + count > 1024 * 1024) { runtime.cancellation.cancelled.store(true); return count; }
        result.append(s, count); pending.append(s, count); return count;
    }
    int overflow(int c) override { if (c != traits_type::eof()) { char b = c; xsputn(&b, 1); } return traits_type::not_eof(c); }
    int sync() override {
        if (!pending.empty() && !validUtf8(pending)) return 0; // retain split token bytes until complete
        if (!pending.empty() && !runtime.cancellation.cancelled.load() && !env->ExceptionCheck()) {
            auto chunk = array(env, pending);
            if (chunk) { env->CallVoidMethod(callback, method, chunk); env->DeleteLocalRef(chunk); }
            if (env->ExceptionCheck()) runtime.cancellation.cancelled.store(true);
        }
        pending.clear(); return 0;
    }
};
// Reject inline resource tags: images must be provided in memory, never opened by model text.
void rejectResources(const std::string& s) {
    for (auto tag : {"<img>", "<audio>", "<video>"})
        if (s.find(tag) != std::string::npos) throw std::runtime_error("Inline media resources are forbidden");
}
}
#define JNI_METHOD(name) Java_com_unoone_agent_localbrain_qwen_QwenMnnRuntime_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeLoad)(JNIEnv* env, jobject, jbyteArray config, jint contextLimit, jint maxOutput) {
    try {
        // Enforce one MNN instance. The app MUST additionally serialize against Gemma via its global lease.
        std::lock_guard<std::mutex> lock(registryMutex);
        if (!registry.empty()) throw std::runtime_error("Another MNN runtime is resident");
        auto path = bytes(env, config, 8192);
        if (path.empty() || path[0] != '/' || path.find('\0') != std::string::npos) throw std::runtime_error("Invalid config path");
        if (contextLimit < 1 || contextLimit > 4096 || maxOutput < 1 || maxOutput > 256 || maxOutput >= contextLimit)
            throw std::runtime_error("Invalid context/output budget");
        auto runtime = std::make_shared<Runtime>();
        runtime->contextLimit = contextLimit; runtime->maxOutput = maxOutput;
        runtime->llm.reset(Llm::createLLM(path));
        if (!runtime->llm) throw std::runtime_error("MNN createLLM failed");
        // Preserve all source config fields, including the model's Jinja template.
        rapidjson::Document resolved;
        resolved.Parse(runtime->llm->dump_config().c_str());
        if (resolved.HasParseError() || !resolved.IsObject()) throw std::runtime_error("Invalid MNN config");
        auto& alloc = resolved.GetAllocator();
        auto put = [&](const char* key, rapidjson::Value value) {
            if (resolved.HasMember(key)) resolved.RemoveMember(key);
            resolved.AddMember(rapidjson::Value(key, alloc), value, alloc);
        };
        put("backend_type", rapidjson::Value("cpu", alloc));
        put("thread_num", rapidjson::Value(2));
        put("precision", rapidjson::Value("low", alloc));
        put("memory", rapidjson::Value("low", alloc));
        if (!resolved.HasMember("mllm")) resolved.AddMember("mllm", rapidjson::Value(rapidjson::kObjectType), alloc);
        auto& visionConfig = resolved["mllm"];
        if (!visionConfig.IsObject()) throw std::runtime_error("Invalid mllm config");
        auto visionPut = [&](const char* key, rapidjson::Value value) {
            if (visionConfig.HasMember(key)) visionConfig.RemoveMember(key);
            visionConfig.AddMember(rapidjson::Value(key, alloc), value, alloc);
        };
        visionPut("backend_type", rapidjson::Value("cpu", alloc));
        visionPut("thread_num", rapidjson::Value(2));
        visionPut("precision", rapidjson::Value("low", alloc));
        visionPut("memory", rapidjson::Value("low", alloc));
        put("use_mmap", rapidjson::Value(true));
        put("reuse_kv", rapidjson::Value(false));
        put("ignore_eos", rapidjson::Value(false));
        put("use_template", rapidjson::Value(false));
        put("sampler_type", rapidjson::Value("greedy", alloc));
        put("speculative_type", rapidjson::Value("none", alloc));
        // Context is enforced by our actual-token admission, not an unsupported MNN key.
        put("image_max_pixels", rapidjson::Value(589824));
        put("async", rapidjson::Value(false)); // finish encoder work in the bounded stage
        runtime->visual = resolved.HasMember("is_visual") && resolved["is_visual"].IsBool() && resolved["is_visual"].GetBool();
        if (resolved.HasMember("image_pad") && resolved["image_pad"].IsInt()) runtime->imagePad = resolved["image_pad"].GetInt();
        if (runtime->visual) {
            // Verify the actual graph selects Omni's Qwen patch-image path, the path whose
            // image_min/max_pixels semantics we audited. Never advertise other vision graphs.
            std::string model = "visual.mnn";
            if (resolved.HasMember("visual_model") && resolved["visual_model"].IsString()) model = resolved["visual_model"].GetString();
            auto modelPath = path.substr(0, path.find_last_of('/') + 1) + model;
            MNN::ScheduleConfig probeConfig;
            MNN::BackendConfig probeBackend;
            probeConfig.type = MNN_FORWARD_CPU; probeConfig.numThread = 2;
            probeBackend.precision = MNN::BackendConfig::Precision_Low;
            probeBackend.memory = MNN::BackendConfig::Memory_Low;
            probeConfig.backendConfig = &probeBackend;
            std::shared_ptr<MNN::Express::Executor::RuntimeManager> probeRuntime(
                MNN::Express::Executor::RuntimeManager::createRuntimeManager(probeConfig));
            if (!probeRuntime) throw std::runtime_error("Visual probe runtime failed");
            std::unique_ptr<MNN::Express::Module> probe(MNN::Express::Module::load({}, {}, modelPath.c_str(), probeRuntime));
            if (!probe || probe->getInfo()->inputNames.size() < 3 || probe->getInfo()->inputNames[0] != "patches")
                throw std::runtime_error("Unsupported visual graph: bounded Qwen patches required");
            const auto& names = probe->getInfo()->inputNames;
            const bool qwen3 = names.size() == 5 && names[3] == "idx_tensor";
            const bool qwen2 = names.size() == 3 || (names.size() == 4 && names[3] == "window_index");
            if (!qwen3 && !qwen2) throw std::runtime_error("Unsupported Qwen visual processor");
            // Match the processor selected by the ACTUAL graph, not a guessed model name.
            runtime->imageFactor = qwen3 ? 32 : 28;
            runtime->imageMinPixels = qwen3 ? 65536 : 3136;
            put("image_min_pixels", rapidjson::Value(runtime->imageMinPixels));
        }
        put("max_new_tokens", rapidjson::Value(maxOutput));
        if (!resolved.HasMember("jinja")) resolved.AddMember("jinja", rapidjson::Value(rapidjson::kObjectType), alloc);
        auto& jinja = resolved["jinja"];
        if (!jinja.IsObject()) throw std::runtime_error("Invalid Jinja config");
        if (!jinja.HasMember("context")) jinja.AddMember("context", rapidjson::Value(rapidjson::kObjectType), alloc);
        auto& ctx = jinja["context"];
        if (!ctx.IsObject()) throw std::runtime_error("Invalid Jinja context");
        if (ctx.HasMember("enable_thinking")) ctx.RemoveMember("enable_thinking");
        ctx.AddMember("enable_thinking", false, alloc);
        rapidjson::StringBuffer buffer; rapidjson::Writer<rapidjson::StringBuffer> writer(buffer);
        resolved.Accept(writer);
        if (!runtime->llm->set_config(buffer.GetString()) || !runtime->llm->load())
            throw std::runtime_error("MNN model load failed");
        rapidjson::Document receipt;
        receipt.Parse(runtime->llm->dump_config().c_str());
        auto policyMatches = [](const rapidjson::Value& config) {
            auto equals = [&](const char* key, const char* value) {
                return config.HasMember(key) && config[key].IsString() && std::string(config[key].GetString()) == value;
            };
            return config.IsObject() && equals("backend_type", "cpu") && equals("precision", "low") && equals("memory", "low") &&
                config.HasMember("thread_num") && config["thread_num"].IsInt() && config["thread_num"].GetInt() == 2;
        };
        if (receipt.HasParseError() || !policyMatches(receipt) || !receipt.HasMember("mllm") || !policyMatches(receipt["mllm"]))
            throw std::runtime_error("Observed text/vision runtime policy mismatch");
        auto& ra = receipt.GetAllocator();
        rapidjson::Value admission(rapidjson::kObjectType);
        admission.AddMember("max_images", 1, ra);
        admission.AddMember("max_encoded_image_bytes", 4194304, ra);
        admission.AddMember("max_decoded_dimension", 768, ra);
        admission.AddMember("max_encoder_pixels", 589824, ra);
        admission.AddMember("decoder_context_tokens", contextLimit, ra);
        admission.AddMember("stages", "bounded image encoder/tokenizer; actual prompt tokens + output admission before decoder prefill", ra);
        receipt.AddMember("unoone_admission", admission, ra);
        rapidjson::StringBuffer rb; rapidjson::Writer<rapidjson::StringBuffer> rw(rb); receipt.Accept(rw);
        runtime->receipt = rb.GetString();
        auto handle = nextHandle++;
        registry.emplace(handle, runtime); return handle;
    } catch (const std::exception& e) { fail(env, e.what()); } catch (...) { fail(env, "Unknown MNN load failure"); }
    return 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeReceipt)(JNIEnv* env, jobject, jlong handle) {
    try { return array(env, lookup(handle)->receipt); }
    catch (const std::exception& e) { fail(env, e.what()); }
    return nullptr;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeImageSupported)(JNIEnv* env, jobject, jlong handle) {
    try { return lookup(handle)->visual ? JNI_TRUE : JNI_FALSE; }
    catch (const std::exception& e) { fail(env, e.what()); return JNI_FALSE; }
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeEpoch)(JNIEnv* env, jobject, jlong handle) {
    try { return static_cast<jlong>(lookup(handle)->cancellation.admit()); }
    catch (const std::exception& e) { fail(env, e.what()); return -1; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativePrepare)(JNIEnv* env, jobject, jlong handle, jlong expectedEpoch) {
    try { if (!lookup(handle)->cancellation.prepare(expectedEpoch)) throw std::runtime_error("Stale cancellation epoch"); }
    catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeGenerate)(JNIEnv* env, jobject, jlong handle, jlong expectedEpoch, jbyteArray system, jbyteArray text, jbyteArray image, jint tokenLimit, jobject callback) {
    try {
        auto runtime = lookup(handle);
        std::unique_lock<std::mutex> op(runtime->operation, std::try_to_lock);
        if (!op.owns_lock()) throw std::runtime_error("MNN runtime is busy");
        if (!runtime->cancellation.begin(expectedEpoch)) throw std::runtime_error("Generation cancelled before entry");
        if (!runtime->llm) throw std::runtime_error("MNN runtime closed");
        if (tokenLimit < 1 || tokenLimit > runtime->maxOutput) throw std::runtime_error("Token limit exceeds loaded output budget");
        auto sys = bytes(env, system, 131072), user = bytes(env, text, 131072);
        rejectResources(sys); rejectResources(user);
        if (!validUtf8(sys) || !validUtf8(user)) throw std::runtime_error("Malformed UTF-8 input");
        if (runtime->cancellation.cancelled.load()) throw std::runtime_error("Generation cancelled");
        auto& llm = *runtime->llm;
        llm.reset();
        MNN::Express::ExecutorScope scope(llm.getExecutor());
        MNN::Transformer::MultimodalPrompt prompt;
        // Stage 1: encoded bytes and decoded dimensions bound vision work. This is
        // NOT a zero-neural-work token admission: Omni tokenization runs the encoder.
        if (image) {
            if (!runtime->visual) throw std::runtime_error("Model is not visual");
            auto encoded = bytes(env, image, vision_budget::maxEncodedBytes);
            if (!vision_budget::encoded(encoded.size())) throw std::runtime_error("Empty or oversized image");
            int admittedWidth = 0, admittedHeight = 0;
            if (!image_admission::inspect(reinterpret_cast<const unsigned char*>(encoded.data()), encoded.size(), admittedWidth, admittedHeight))
                throw std::runtime_error("Unsupported, malformed or oversized PNG/JPEG header");
            if (!vision_budget::encoder(admittedWidth, admittedHeight, runtime->imageFactor, runtime->imageMinPixels))
                throw std::runtime_error("Resized image encoder pixel budget exceeded");
            if (runtime->cancellation.cancelled.load()) throw std::runtime_error("Cancelled before image decode");
            std::vector<uint8_t> buffer(encoded.begin(), encoded.end());
            auto pixels = MNN::CV::imdecode(buffer, MNN::CV::IMREAD_COLOR);
            if (pixels.get() == nullptr || !pixels->getInfo() || pixels->getInfo()->dim.size() != 3)
                throw std::runtime_error("Invalid encoded image");
            auto dims = pixels->getInfo()->dim;
            if (dims[1] != admittedWidth || dims[0] != admittedHeight || !vision_budget::dimensions(dims[1], dims[0])) throw std::runtime_error("Decoded image budget exceeded (32..768 each dimension)");
            prompt.images.emplace("unoone_screen", MNN::Transformer::PromptImagePart{pixels, dims[1], dims[0]});
            user = "<img>unoone_screen</img>\n" + user;
        }
        prompt.prompt_template = llm.apply_chat_template(MNN::Transformer::ChatMessages{{"system", sys}, {"user", user}});
        if (image) {
            const std::string marker = "<img>unoone_screen</img>";
            auto at = prompt.prompt_template.find(marker);
            if (at == std::string::npos || prompt.prompt_template.find(marker, at + marker.size()) != std::string::npos)
                throw std::runtime_error("Chat template must preserve exactly one image marker");
        }
        // Omni::reset alone does not clear image embeddings. Empty multimodal tokenization
        // explicitly clears vision/deepstack features, with no prefill or image encoder.
        struct ClearFeatures {
            Llm& llm;
            ~ClearFeatures() { try { llm.tokenizer_encode(MNN::Transformer::MultimodalPrompt{}); llm.reset(); } catch (...) {} }
        } clear{llm};
        Output output(env, callback, *runtime); std::ostream stream(&output);
        // Prefill once then decode ONE token per call. No cross-thread mutation of MNN context.
        if (runtime->cancellation.cancelled.load()) throw std::runtime_error("Generation cancelled before encoder/tokenizer");
        size_t textImagePads = 0;
        if (image) {
            auto textPrompt = prompt.prompt_template;
            const std::string marker = "<img>unoone_screen</img>";
            textPrompt.erase(textPrompt.find(marker), marker.size());
            MNN::Transformer::MultimodalPrompt baseline; baseline.prompt_template = textPrompt;
            auto textIds = llm.tokenizer_encode(baseline);
            textImagePads = std::count(textIds.begin(), textIds.end(), runtime->imagePad);
        }
        auto ids = llm.tokenizer_encode(prompt);
        if (image && static_cast<size_t>(std::count(ids.begin(), ids.end(), runtime->imagePad)) <= textImagePads)
            throw std::runtime_error("Vision encoder produced no image tokens: no text-only fallback");
        if (!vision_budget::tokens(ids.size(), tokenLimit, runtime->contextLimit)) throw std::runtime_error("Prompt token budget exceeded or tokenization failed");
        // Stage 2: actual multimodal tokens, including visual placeholders, admitted
        // before the ONE decoder prefill. Atomic cancellation cannot interrupt the encoder.
        if (runtime->cancellation.cancelled.load()) throw std::runtime_error("Generation cancelled after encoder/tokenizer");
        llm.response(ids, &stream, "", 0);
        for (int i = 0; i < tokenLimit && !runtime->cancellation.cancelled.load() && !llm.stoped(); ++i) llm.generate(1);
        stream.flush();
        if (env->ExceptionCheck()) return nullptr;
        if (runtime->cancellation.cancelled.load()) throw std::runtime_error("Generation cancelled or output byte budget exceeded");
        auto state = llm.getContext()->status;
        if (state != MNN::Transformer::LlmStatus::NORMAL_FINISHED)
            throw std::runtime_error("MNN output incomplete, truncated or failed: no action");
        if (output.result.empty() || !validUtf8(output.result))
            throw std::runtime_error("Empty or malformed UTF-8 output: no action");
        return array(env, output.result);
    } catch (const std::exception& e) { fail(env, e.what()); } catch (...) { fail(env, "Unknown MNN generation failure"); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeCancel)(JNIEnv* env, jobject, jlong handle) {
    try { lookup(handle)->cancellation.cancel(); } catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeClose)(JNIEnv* env, jobject, jlong handle) {
    try {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto it = registry.find(handle);
        if (it == registry.end()) return JNI_TRUE;
        auto runtime = it->second;
        std::unique_lock<std::mutex> op(runtime->operation, std::try_to_lock);
        if (!op.owns_lock()) { runtime->cancellation.cancel(); return JNI_FALSE; }
        runtime->llm.reset(); // Destruction is completed BEFORE the acknowledgement.
        registry.erase(it); return JNI_TRUE;
    } catch (const std::exception& e) { fail(env, e.what()); } catch (...) { fail(env, "Unknown MNN close failure"); }
    return JNI_FALSE;
}
