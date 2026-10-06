import com.unoone.agent.localbrain.owl.OwlLlamaRuntime
import com.unoone.agent.core.guiowl.*
import java.io.File
val dir=File("/agent/workspace/owl-native-prep/voice-prompt-ab")
fun event(s:String){println("PROBE ${System.currentTimeMillis()} $s");System.out.flush()}
fun main(){
 val candidate=OwlPromptBuilder.buildScoped(ScopedOwlOperation.CLICK,exactLabel="Search",scopePackage="example.app")
 // Identical goal metadata, no extra baseline-only or candidate-only task facts.
 val baseline=OwlPromptBuilder.build(candidate.user,"")
 File(dir,"goal-metadata.json").writeText(candidate.user)
 File(dir,"candidate-version.txt").writeText(candidate.versionId.name)
 for((name,p) in listOf("baseline" to OwlPrompt(baseline.system,baseline.user),"candidate" to OwlPrompt(candidate.system,candidate.user))){File(dir,"$name-system.txt").writeText(p.system);File(dir,"$name-user.txt").writeText(p.user)}
 val r=OwlLlamaRuntime()
 try{
  val start=System.nanoTime();event("CONFIG "+r.load("/agent/workspace/owl-model"));event("LOAD_MS ${(System.nanoTime()-start)/1000000}")
  File(dir,"loaded-process-maps.txt").writeText(File("/proc/self/maps").readText())
  for((variant,id) in listOf("baseline" to "A","candidate" to "B","candidate" to "A","baseline" to "B")){
   val name="$variant-$id";val p=if(variant=="baseline")baseline else OwlPrompt(candidate.system,candidate.user)
   val image=File(dir,"synthetic-gui-$id-256.png").readBytes()
   event("BEGIN $name");val t=System.nanoTime()
   try{
    val raw=r.generate(p.system,p.user,image,256);val ms=(System.nanoTime()-t)/1000000
    File(dir,"$name.output.txt").writeText(raw);event("GENERATE_OK $name elapsedMs=$ms")
    try{val decoded=OwlOutputCodec.decode(raw);File(dir,"$name.codec.txt").writeText("action=${decoded.action};x=${decoded.coordinate?.x};y=${decoded.coordinate?.y};proposal=$decoded");event("CODEC_OK $name")}
    catch(e:Throwable){File(dir,"$name.codec-error.txt").writeText(e.stackTraceToString());event("CODEC_ERROR $name ${e.message}")}
   }catch(e:Throwable){File(dir,"$name.error.txt").writeText(e.stackTraceToString());event("GENERATE_ERROR $name elapsedMs=${(System.nanoTime()-t)/1000000} ${e.message}")}
  }
 }catch(e:Throwable){File(dir,"fatal.error.txt").writeText(e.stackTraceToString());event("FATAL ${e.message}")}
 finally{event("FINAL_NATIVE_CLOSE_ACK=${r.close()} status=${r.status}")}
}
