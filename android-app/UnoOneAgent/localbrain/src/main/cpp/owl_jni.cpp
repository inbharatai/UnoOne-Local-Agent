// Pinned llama.cpp/mtmd bridge. No synthetic image embeddings or coordinate execution.
#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "owl_chat_template.h"
#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <unordered_map>
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#include "stb_image.h"
namespace {
using Clock=std::chrono::steady_clock;
template<class T, void(*F)(T*)> using Owned=std::unique_ptr<T, decltype(F)>;
struct Handle {
 std::mutex operation; std::atomic<long long> epoch{0}; long long admitted=0;
 Clock::time_point deadline=Clock::time_point::max();
 int context=2048, output=256, image=512;
 Owned<llama_model,llama_model_free> model{nullptr,llama_model_free};
 Owned<llama_context,llama_free> ctx{nullptr,llama_free};
 Owned<mtmd_context,mtmd_free> vision{nullptr,mtmd_free};
};
std::mutex registryMutex; std::unordered_map<jlong,std::shared_ptr<Handle>> registry;
std::atomic<jlong> serial{1}; std::once_flag backend;
std::shared_ptr<Handle> get(jlong id) { std::lock_guard<std::mutex> l(registryMutex); auto it=registry.find(id); if(it==registry.end()) throw std::runtime_error("Unknown Owl handle"); return it->second; }
bool abortWork(void* p) { auto*h=static_cast<Handle*>(p); return h->epoch.load()!=h->admitted || Clock::now()>=h->deadline; }
bool progress(float,void*p) { return !abortWork(p); }
void check(Handle&h) { if(abortWork(&h)) throw std::runtime_error("Owl cancelled/deadline; no proposal"); }
void fail(JNIEnv*e,const char*m) { if(!e->ExceptionCheck()) e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),m); }
std::string bytes(JNIEnv*e,jbyteArray a,size_t limit) { if(!a) throw std::runtime_error("Null input"); auto n=e->GetArrayLength(a); if(size_t(n)>limit) throw std::runtime_error("Input exceeds budget"); std::string s(n,'\0'); e->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte*>(s.data())); if(e->ExceptionCheck()) throw std::runtime_error("JNI input failed"); return s; }
jbyteArray out(JNIEnv*e,const std::string&s) { auto a=e->NewByteArray(s.size()); if(a)e->SetByteArrayRegion(a,0,s.size(),reinterpret_cast<const jbyte*>(s.data())); return a; }
// Validate raw RFC 3629 UTF-8 before template checks, tokenization or image decoding.
void strictUtf8(const std::string&s) {
 for(size_t i=0;i<s.size();) {
  unsigned char lead=static_cast<unsigned char>(s[i++]);
  if(lead<0x80)continue;
  unsigned n=0,cp=0,min=0;
  if(lead>=0xC2&&lead<=0xDF){n=1;cp=lead&0x1F;min=0x80;}
  else if(lead>=0xE0&&lead<=0xEF){n=2;cp=lead&0x0F;min=0x800;}
  else if(lead>=0xF0&&lead<=0xF4){n=3;cp=lead&0x07;min=0x10000;}
  else throw std::runtime_error("Malformed UTF-8 input");
  if(s.size()-i<n)throw std::runtime_error("Malformed UTF-8 input");
  for(unsigned j=0;j<n;j++){unsigned char c=static_cast<unsigned char>(s[i++]);if((c&0xC0)!=0x80)throw std::runtime_error("Malformed UTF-8 input");cp=(cp<<6)|(c&0x3F);}
  if(cp<min||cp>0x10FFFF||(cp>=0xD800&&cp<=0xDFFF))throw std::runtime_error("Malformed UTF-8 input");
 }
}
void plain(const std::string&s) { if(s.find('\0')!=s.npos || s.find("<|")!=s.npos || s.find(mtmd_default_marker())!=s.npos) throw std::runtime_error("Reserved template marker in input"); }
void tokenCheck(const llama_vocab*v,const char*s) { llama_token t[4]; int n=llama_tokenize(v,s,strlen(s),t,4,false,true); if(n!=1 || !(llama_vocab_get_attr(v,t[0]) & LLAMA_TOKEN_ATTR_CONTROL)) throw std::runtime_error("Required Qwen3VL special token invalid"); }
}
#define JNI(name) Java_com_unoone_agent_localbrain_owl_OwlLlamaRuntime_##name
extern "C" JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv*e,jobject,jint context,jint output,jint image) {
 try { if(!((context==2048&&output==256&&image==512)||(context==1024&&output==128&&image==256))) throw std::runtime_error("Unsupported Owl budget"); auto h=std::make_shared<Handle>();h->context=context;h->output=output;h->image=image;auto id=serial++;std::lock_guard<std::mutex> l(registryMutex);registry[id]=h;return id; } catch(const std::exception&x){fail(e,x.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI(nativeLoad)(JNIEnv*e,jobject,jlong id,jlong epoch,jbyteArray model,jbyteArray projector) {
 try {auto h=get(id);std::lock_guard<std::mutex> l(h->operation);h->admitted=epoch;h->deadline=Clock::now()+std::chrono::minutes(5);check(*h);auto m=bytes(e,model,4096),p=bytes(e,projector,4096);if(m.empty()||p.empty()||m[0]!='/'||p[0]!='/'||m.find('\0')!=m.npos||p.find('\0')!=p.npos)throw std::runtime_error("Invalid path");
 std::call_once(backend,[]{llama_backend_init();}); auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;mp.use_extra_bufts=false;mp.progress_callback=progress;mp.progress_callback_user_data=h.get();h->model.reset(llama_model_load_from_file(m.c_str(),mp));if(!h->model)throw std::runtime_error("Decoder load failed");check(*h);
 char arch[128];if(llama_model_meta_val_str(h->model.get(),"general.architecture",arch,sizeof arch)<0||std::string(arch)!="qwen3vl")throw std::runtime_error("Expected qwen3vl");const char* templ=llama_model_chat_template(h->model.get(),nullptr);if(!templ||std::string(templ)!=OWL_CHAT_TEMPLATE)throw std::runtime_error("Pinned chat template mismatch");auto v=llama_model_get_vocab(h->model.get());for(auto s:{"<|im_start|>","<|im_end|>","<|vision_start|>","<|vision_end|>","<|image_pad|>"})tokenCheck(v,s);
 auto cp=llama_context_default_params();cp.n_ctx=h->context;cp.n_batch=64;cp.n_ubatch=64;cp.n_threads=2;cp.n_threads_batch=2;cp.abort_callback=abortWork;cp.abort_callback_data=h.get();h->ctx.reset(llama_init_from_model(h->model.get(),cp));if(!h->ctx)throw std::runtime_error("Context load failed");check(*h);
 auto vp=mtmd_context_params_default();vp.use_gpu=false;vp.n_threads=2;vp.warmup=false;vp.image_min_tokens=1;vp.image_max_tokens=(h->image/32)*(h->image/32);vp.batch_max_tokens=64;vp.progress_callback=progress;vp.progress_callback_user_data=h.get();h->vision.reset(mtmd_init_from_file(p.c_str(),h->model.get(),vp));if(!h->vision||!mtmd_support_vision(h->vision.get())||!mtmd_decode_use_mrope(h->vision.get()))throw std::runtime_error("Qwen3VL vision/MRoPE unavailable");check(*h);
 }catch(const std::exception&x){fail(e,x.what());}
}
extern "C" JNIEXPORT jlong JNICALL JNI(nativeEpoch)(JNIEnv*e,jobject,jlong id){try{return get(id)->epoch.load();}catch(const std::exception&x){fail(e,x.what());return -1;}}
extern "C" JNIEXPORT void JNICALL JNI(nativeCancel)(JNIEnv*e,jobject,jlong id){try{get(id)->epoch++;}catch(const std::exception&x){fail(e,x.what());}}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeClose)(JNIEnv*e,jobject,jlong id){try{auto h=get(id);h->epoch++;std::unique_lock<std::mutex> l(h->operation,std::try_to_lock);if(!l.owns_lock())return false;h->vision.reset();h->ctx.reset();h->model.reset();std::lock_guard<std::mutex> r(registryMutex);registry.erase(id);return true;}catch(const std::exception&x){fail(e,x.what());return false;}}
extern "C" JNIEXPORT jbyteArray JNICALL JNI(nativeGenerate)(JNIEnv*e,jobject,jlong id,jlong epoch,jbyteArray system,jbyteArray text,jbyteArray image,jint maxTokens,jlong timeoutMs){
 try{auto h=get(id);std::lock_guard<std::mutex> l(h->operation);h->admitted=epoch;h->deadline=Clock::now()+std::chrono::milliseconds(timeoutMs);check(*h);if(!h->ctx||!h->vision||maxTokens<1||maxTokens>h->output||timeoutMs<1||timeoutMs>180000)throw std::runtime_error("Invalid runtime/request budget");auto s=bytes(e,system,48000),t=bytes(e,text,48000);strictUtf8(s);strictUtf8(t);plain(s);plain(t);
 // Exact no-tools, system + one user + generation prefix subset of pinned Qwen3VL template.
 // mtmd inserts vision_start/end itself around its media marker for Qwen3VL.
 std::string prompt="<|im_start|>system\n"+s+"<|im_end|>\n<|im_start|>user\n"+(image?std::string(mtmd_default_marker()):"")+t+"<|im_end|>\n<|im_start|>assistant\n";
 Owned<mtmd_bitmap,mtmd_bitmap_free> bitmap(nullptr,mtmd_bitmap_free);
 if(image){auto data=bytes(e,image,4*1024*1024);bool png=data.size()>=8&&memcmp(data.data(),"\x89PNG\r\n\x1a\n",8)==0;bool jpg=data.size()>2&&(unsigned char)data[0]==255&&(unsigned char)data[1]==216;if(!png&&!jpg)throw std::runtime_error("Only PNG/JPEG admitted");int w=0,hh=0,c=0;if(!stbi_info_from_memory((const unsigned char*)data.data(),data.size(),&w,&hh,&c)||w<1||hh<1||w>h->image||hh>h->image)throw std::runtime_error("Image dimension budget exceeded; caller must preserve transform");auto b=mtmd_helper_bitmap_init_from_buf(h->vision.get(),(const unsigned char*)data.data(),data.size(),false,{});bitmap.reset(b.bitmap);if(!bitmap)throw std::runtime_error("Image decode failed");}
 check(*h);Owned<mtmd_input_chunks,mtmd_input_chunks_free> chunks(mtmd_input_chunks_init(),mtmd_input_chunks_free);const mtmd_bitmap*b=bitmap.get();mtmd_input_text input{prompt.c_str(),prompt.size(),false,true};if(mtmd_tokenize(h->vision.get(),chunks.get(),&input,b?&b:nullptr,b?1:0))throw std::runtime_error("Multimodal tokenize failed");size_t vision=0;for(size_t i=0;i<mtmd_input_chunks_size(chunks.get());i++){auto c=mtmd_input_chunks_get(chunks.get(),i);if(mtmd_input_chunk_get_type(c)==MTMD_INPUT_CHUNK_TYPE_IMAGE)vision+=mtmd_input_chunk_get_n_tokens(c);}auto total=mtmd_helper_get_n_tokens(chunks.get());
 // Counts only: never print system/user prompt contents here, including on rejection.
 std::fprintf(stderr,"Owl admission: text_tokens=%zu text_plus_vision=%zu image_tokens=%zu reserved_output=%d context=%d image_edge=%d\n",total-vision,total,vision,maxTokens,h->context,h->image);
 if(vision>size_t((h->image/32)*(h->image/32))||(image&&vision==0)||total+maxTokens>size_t(h->context))throw std::runtime_error("Full text+vision+reserved output exceeds context; no truncation");
 llama_memory_clear(llama_get_memory(h->ctx.get()),true);check(*h);llama_pos next=0;if(mtmd_helper_eval_chunks(h->vision.get(),h->ctx.get(),chunks.get(),0,0,64,true,&next))throw std::runtime_error("Vision/prefill failed");check(*h);
 Owned<llama_sampler,llama_sampler_free> sampler(llama_sampler_init_greedy(),llama_sampler_free);auto v=llama_model_get_vocab(h->model.get());std::string result;for(int i=0;i<maxTokens;i++){check(*h);auto tok=llama_sampler_sample(sampler.get(),h->ctx.get(),-1);if(llama_vocab_is_eog(v,tok)){check(*h);return out(e,result);}char piece[256];int n=llama_token_to_piece(v,tok,piece,sizeof piece,0,false);if(n<0)throw std::runtime_error("Oversize token piece");result.append(piece,n);if(result.size()>32768)throw std::runtime_error("Output bytes exceeded");
 // mtmd helper owns the 4-axis M-RoPE position layout, including text continuation.
 
 // Decode continuation with explicit 4D M-RoPE positions (t,h,w,0), same as upstream helper.
 llama_token token[1]={tok}; llama_pos pos[4]={next,next,next,next};
 int32_t nseq[1]={1}; llama_seq_id seq[1]={0}; llama_seq_id* seqs[1]={seq}; int8_t logits[1]={1};
 llama_batch batch{};batch.n_tokens=1;batch.token=token;batch.embd=nullptr;batch.pos=pos;
 batch.n_seq_id=nseq;batch.seq_id=seqs;batch.logits=logits;
 int rc=llama_decode(h->ctx.get(),batch);if(rc)throw std::runtime_error("Decode aborted/failed");next++;check(*h);
 }throw std::runtime_error("Output token limit reached without EOS; partial proposal rejected");
 }catch(const std::exception&x){fail(e,x.what());return nullptr;}
}
