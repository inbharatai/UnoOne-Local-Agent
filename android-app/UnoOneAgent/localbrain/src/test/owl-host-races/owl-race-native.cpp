#include <jni.h>
#include <atomic>
static std::atomic<int> creates{0},loads{0},epoch{0},mode{0}; static bool acknowledge=true;
#define R(n) Java_com_unoone_agent_localbrain_owl_OwlLlamaRuntime_##n
#define T(n) Java_com_unoone_agent_localbrain_owl_RaceNative_##n
extern "C" {
JNIEXPORT void JNICALL T(reset)(JNIEnv*,jobject){creates=0;loads=0;epoch=0;acknowledge=true;mode=0;}
JNIEXPORT jint JNICALL T(count)(JNIEnv*,jobject,jint i){return i==0?creates.load():loads.load();}
JNIEXPORT void JNICALL T(ack)(JNIEnv*,jobject,jboolean v){acknowledge=v;}
JNIEXPORT void JNICALL T(output)(JNIEnv*,jobject,jint v){mode=v;}
JNIEXPORT jlong JNICALL R(nativeCreate)(JNIEnv*,jobject,jint,jint,jint){creates++;epoch=0;return 1;}
JNIEXPORT void JNICALL R(nativeLoad)(JNIEnv*,jobject,jlong,jlong,jbyteArray,jbyteArray){loads++;}
JNIEXPORT jlong JNICALL R(nativeEpoch)(JNIEnv*,jobject,jlong){return epoch;}
JNIEXPORT void JNICALL R(nativeCancel)(JNIEnv*,jobject,jlong){epoch++;}
JNIEXPORT jboolean JNICALL R(nativeClose)(JNIEnv*,jobject,jlong){return acknowledge;}
JNIEXPORT jbyteArray JNICALL R(nativeGenerate)(JNIEnv*e,jobject,jlong,jlong,jbyteArray,jbyteArray,jbyteArray,jint,jlong){unsigned char wide[]={0xf0,0x9f,0x98,0x80,0xf4,0x8f,0xbf,0xbf},bad[]={0xc3,0x28},shorty[]={0xf0,0x9f};auto p=mode==0?wide:mode==1?bad:shorty;int n=mode==0?8:2;auto a=e->NewByteArray(n);e->SetByteArrayRegion(a,0,n,(jbyte*)p);return a;}
}
