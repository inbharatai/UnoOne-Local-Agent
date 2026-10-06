import com.unoone.agent.localbrain.owl.OwlLlamaRuntime
import com.unoone.agent.core.guiowl.OwlPromptBuilder
import com.unoone.agent.core.guiowl.OwlOutputCodec
import java.io.File
import java.lang.reflect.InvocationTargetException
val dir=File("/agent/workspace/owl-native-prep/real-probe-default")
fun event(s:String) {println("PROBE ${System.currentTimeMillis()} $s");System.out.flush()}
fun test(name:String, block:()->String):String? {
 event("BEGIN $name");val start=System.nanoTime()
 return try {val s=block();File(dir,"$name.output.txt").writeText(s);event("SUCCESS $name elapsedMs=${(System.nanoTime()-start)/1000000} output="+s);s}
 catch(t:Throwable){val x=if(t is InvocationTargetException)t.targetException else t;File(dir,"$name.error.txt").writeText(x.stackTraceToString());event("ERROR $name elapsedMs=${(System.nanoTime()-start)/1000000} ${x.javaClass.name}: ${x.message}");null}
}
fun main(){
 val r=OwlLlamaRuntime();val red=File(dir,"synthetic-red-256.png").readBytes()
 val prompt=OwlPromptBuilder.build("Click the Search button.","Synthetic test fixture only; no real device or execution.")
 File(dir,"official-system.txt").writeText(prompt.system);File(dir,"official-user.txt").writeText(prompt.user)
 try {
 event("LOAD default");event("CONFIG "+r.load("/agent/workspace/owl-model"))
 val f=r.javaClass.getDeclaredField("handle");f.isAccessible=true;val h=f.getLong(r)
 val ep=r.javaClass.getDeclaredMethod("nativeEpoch",java.lang.Long.TYPE);ep.isAccessible=true
 val method=r.javaClass.getDeclaredMethod("nativeGenerate",java.lang.Long.TYPE,java.lang.Long.TYPE,ByteArray::class.java,ByteArray::class.java,ByteArray::class.java,Integer.TYPE,java.lang.Long.TYPE);method.isAccessible=true
 // Original malformed C328 is tested BEFORE any inference; both fields, with and without image.
 for(field in listOf("text","system"))for(image in listOf(false,true))test("invalid-C328-$field-image-$image"){
  val bad=byteArrayOf(0xc3.toByte(),0x28);val good="Answer briefly.".toByteArray()
  val b=method.invoke(r,h,ep.invoke(r,h),if(field=="system")bad else good,if(field=="text")bad else good,if(image)red else null,1,180000L) as ByteArray
  "UNSAFE_ACCEPTED "+b.toString(Charsets.UTF_8)
 }
 for(field in listOf("text","system"))test("unpaired-surrogate-$field") {r.generate(if(field=="system")"\uD800" else "",if(field=="text")"\uD800" else "hello",null,16)}
 val stale=r.admitRequest();r.cancel();test("stale-queued-permit"){r.generate("","2+2",null,16,stale)}
 for(id in listOf("A","B")){
  val raw=test("official-gui-$id"){r.generate(prompt.system,prompt.user,File(dir,"synthetic-gui-$id-256.png").readBytes(),256)}
  if(raw!=null)test("production-codec-$id"){val p=OwlOutputCodec.decode(raw);"action=${p.action};x=${p.coordinate?.x};y=${p.coordinate?.y};proposal=$p"}
 }
 test("text-sanity"){r.generate("Answer briefly.","What is 2+2?",null,16)}
 test("red-image-sanity"){r.generate("Answer briefly.","What is the dominant color of the image?",red,16)}
 test("unicode-positive"){r.generate("Answer briefly. हिन्दी 😀","Reply with the number 4 only. नमस्ते 🌍",null,16)}
 test("output-truncation"){r.generate("","Count from 1 to 20.",null,1)}
 val queueStart=System.nanoTime();val permit=r.admitRequest();Thread.sleep(120)
 val stderr=File(dir,"stderr.log");val before=stderr.length()
 val worker=Thread {event("ACTIVE_NATIVE_CALL_START queueMs=${(System.nanoTime()-queueStart)/1000000}");test("active-cancel"){r.generate("","Count from 1 to 1000, writing every number.",null,256,permit)}}
 worker.start();val waitStart=System.nanoTime()
 while(worker.isAlive && stderr.length()==before && System.nanoTime()-waitStart<5_000_000_000L)Thread.sleep(10)
 Thread.sleep(500);event("ACTIVE_CANCEL_REQUEST status=${r.status} stderrAdvanced=${stderr.length()>before}");val cancelStart=System.nanoTime();r.cancel();worker.join(15000)
 event("ACTIVE_CANCEL_RETURN workerAlive=${worker.isAlive} cancelToJoinMs=${(System.nanoTime()-cancelStart)/1000000}")
 if(worker.isAlive){event("ACTIVE_CANCEL_NOT_BOUNDED external monitor remains authority");worker.join()}
 }catch(t:Throwable){File(dir,"fatal.error.txt").writeText(t.stackTraceToString());event("FATAL ${t.message}")}
 finally{event("FINAL_NATIVE_CLOSE_ACK=${r.close()} status=${r.status}");event("SECOND_CLOSE_ACK=${r.close()}")}
}
