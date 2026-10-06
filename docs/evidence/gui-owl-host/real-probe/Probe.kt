import com.unoone.agent.localbrain.owl.OwlLlamaRuntime
import com.unoone.agent.core.guiowl.OwlPromptBuilder
import java.io.File
import java.lang.reflect.InvocationTargetException
val dir=File("/agent/workspace/owl-native-prep/real-probe")
fun event(s:String) { println("PROBE ${System.currentTimeMillis()} $s"); System.out.flush() }
fun test(name:String, block:()->String):String {
 event("BEGIN $name")
 val start=System.nanoTime()
 return try { val s=block();File(dir,"$name.output.txt").writeText(s);event("SUCCESS $name elapsedMs=${(System.nanoTime()-start)/1000000} output="+s); "SUCCESS" }
 catch(t:Throwable) {val x=if(t is InvocationTargetException) t.targetException else t; File(dir,"$name.error.txt").writeText(x.stackTraceToString());event("ERROR $name elapsedMs=${(System.nanoTime()-start)/1000000} ${x.javaClass.name}: ${x.message}");x.message?:"ERROR"}
}
fun main() {
 val r=OwlLlamaRuntime()
 val gui=File(dir,"synthetic-gui-256.png").readBytes()
 val red=File(dir,"synthetic-red-256.png").readBytes()
 val prompt=OwlPromptBuilder.build("Click the Search button.", "Synthetic test fixture only; no real device or execution.")
 File(dir,"official-system.txt").writeText(prompt.system);File(dir,"official-user.txt").writeText(prompt.user)
 event("OFFICIAL_PROMPT chars=${prompt.system.length+prompt.user.length}; exact native token admission occurs before prefill; no truncation")
 try {
 event("LOAD reduced")
 event("CONFIG "+r.load("/agent/workspace/owl-model",1024,128,256))
 val stale=r.admitRequest();r.cancel()
 test("stale-queued-permit") {r.generate("","2+2",null,16,stale)}
 val official=test("official-gui-1024") {r.generate(prompt.system,prompt.user,gui,128,r.admitRequest())}
 test("text-sanity") {r.generate("Answer briefly.","What is 2+2?",null,16,r.admitRequest())}
 test("red-image-sanity") {r.generate("Answer briefly.","What is the dominant color of the image?",red,16,r.admitRequest())}
 test("output-truncation") {r.generate("","Count from 1 to 20.",null,1,r.admitRequest())}
 test("invalid-utf8-native-input") {
 val f=r.javaClass.getDeclaredField("handle");f.isAccessible=true;val h=f.getLong(r)
 val ep=r.javaClass.getDeclaredMethod("nativeEpoch",java.lang.Long.TYPE);ep.isAccessible=true
 val method=r.javaClass.getDeclaredMethod("nativeGenerate",java.lang.Long.TYPE,java.lang.Long.TYPE,ByteArray::class.java,ByteArray::class.java,ByteArray::class.java,Integer.TYPE,java.lang.Long.TYPE);method.isAccessible=true
 val bytes=method.invoke(r,h,ep.invoke(r,h),byteArrayOf(),byteArrayOf(0xc3.toByte(),0x28),null,1,180000L) as ByteArray
 "UNSAFE_ACCEPTED invalid UTF8; output="+bytes.toString(Charsets.UTF_8)
 }
 if(official.contains("exceeds context")) {
 val headroom=File(dir,"headroom.allowed").exists()
 event("DEFAULT2048_HEADROOM_ALLOWED=$headroom")
 if(headroom) {
 event("REDUCED_NATIVE_CLOSE_ACK=${r.close()}")
 event("CONFIG "+r.load("/agent/workspace/owl-model",2048,256,512))
 test("official-gui-2048") {r.generate(prompt.system,prompt.user,gui,128,r.admitRequest())}
 } else event("DEFAULT2048_SKIPPED conservative observed-memory margin insufficient")
 }
 } catch(t:Throwable){File(dir,"fatal.error.txt").writeText(t.stackTraceToString());event("FATAL ${t.javaClass.name}: ${t.message}")}
 finally {event("FINAL_NATIVE_CLOSE_ACK=${r.close()} status=${r.status}")}
}
