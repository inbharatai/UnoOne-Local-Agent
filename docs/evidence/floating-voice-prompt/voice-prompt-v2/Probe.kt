import com.unoone.agent.localbrain.owl.OwlLlamaRuntime
import com.unoone.agent.core.guiowl.*
import com.unoone.agent.core.device.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
val dir=File("/agent/workspace/owl-native-prep/voice-prompt-v2")
fun event(s:String){println("PROBE ${System.currentTimeMillis()} $s");System.out.flush()}
data class Case(val id:String,val op:ScopedOwlOperation,val label:String,val box:RectData,val value:String?=null)
fun main(){
 val cases=listOf(Case("N1",ScopedOwlOperation.CLICK,"Library",RectData(16,90,126,132)),Case("N2",ScopedOwlOperation.CLICK,"Settings",RectData(128,182,240,226)),Case("F1",ScopedOwlOperation.FOCUS,"Search query",RectData(24,152,230,193)),Case("W1",ScopedOwlOperation.WRITE,"Draft title",RectData(24,72,230,115),"AbC 42"))
 val prompts=cases.associate { c ->
  val candidate=OwlPromptBuilder.buildScopedV2(c.op,c.label,c.value,"example.app")
  val baseline=OwlPromptBuilder.build(candidate.user,"")
  File(dir,"${c.id}-goal.json").writeText(candidate.user)
  for((v,p) in listOf("baseline" to baseline,"candidate" to OwlPrompt(candidate.system,candidate.user))){File(dir,"$v-${c.id}-system.txt").writeText(p.system);File(dir,"$v-${c.id}-user.txt").writeText(p.user)}
  c.id to mapOf("baseline" to baseline,"candidate" to OwlPrompt(candidate.system,candidate.user))
 }
 File(dir,"candidate-version.txt").writeText("COMPACT_CANDIDATE_V2; never enabled")
 val r=OwlLlamaRuntime()
 try{
  val start=System.nanoTime();event("CONFIG "+r.load("/agent/workspace/owl-model"));event("LOAD_MS ${(System.nanoTime()-start)/1000000}")
  File(dir,"loaded-process-maps.txt").writeText(File("/proc/self/maps").readText())
  for((i,c) in cases.withIndex())for(variant in if(i%2==0)listOf("baseline","candidate") else listOf("candidate","baseline")){
   val name="$variant-${c.id}";val p=prompts.getValue(c.id).getValue(variant);val image=File(dir,"${c.id}.png").readBytes()
   event("BEGIN $name");val t=System.nanoTime()
   try{
    val raw=r.generate(p.system,p.user,image,256);val ms=(System.nanoTime()-t)/1000000
    File(dir,"$name.output.txt").writeText(raw);event("GENERATE_OK $name elapsedMs=$ms")
    try{
     val d=OwlOutputCodec.decode(raw)
     File(dir,"$name.codec.json").writeText(buildJsonObject{put("action",d.action);put("x",d.coordinate?.x?.let(::JsonPrimitive)?:JsonNull);put("y",d.coordinate?.y?.let(::JsonPrimitive)?:JsonNull);put("text",d.text?.let(::JsonPrimitive)?:JsonNull)}.toString());event("CODEC_OK $name")
     // Synthetic native truth only, not real Android authorization. Freeze geometry before inference; bind with simulated fresh equivalent snapshot.
     val display=RectData(0,0,256,256);val field=c.op!=ScopedOwlOperation.CLICK
     val node=UiNode("target",1,"0/1","example.app",if(field)"EditText" else "Button",bounds=c.box,clickable=!field,focusable=field,editable=field,focused=c.op==ScopedOwlOperation.WRITE,semantic=if(field)TargetSemantic.FORM_FIELD else TargetSemantic.NAVIGATION)
     val other=if(c.id=="N1")listOf(UiNode("other",1,"0/2","example.app","Button",bounds=RectData(142,168,240,209),clickable=true,semantic=TargetSemantic.NAVIGATION)) else if(c.id=="N2")listOf(UiNode("other",1,"0/2","example.app","Button",bounds=RectData(18,82,124,124),clickable=true,semantic=TargetSemantic.NAVIGATION)) else emptyList()
     val s=UiSnapshot(c.id,100,7,display,listOf(UiWindow(1,"example.app",display,listOf(node)+other)))
     val scope=OwlScope("synthetic-task",1,2,"example.app",1,mapOf(node.id to node.signature()),clickNodeId=if(!field)node.id else null,focusNodeId=if(c.op==ScopedOwlOperation.FOCUS)node.id else null,writeNodeId=if(c.op==ScopedOwlOperation.WRITE)node.id else null,exactWriteValue=c.value)
     val hash=MessageDigest.getInstance("SHA-256").digest(image).joinToString(""){"%02x".format(it)}
     val receipt=OwlCaptureReceipt("synthetic-task",1,2,s.id,UiStateHasher.hash(s),7,100,hash,256,256,display,0,0,true,true,true,"example.app",1)
     val bound=OwlNativeBinding.translate(raw,s,receipt,scope,101)
     val expected=when(c.op){ScopedOwlOperation.CLICK->DeviceAction.ClickNode(s.id,node.id);ScopedOwlOperation.FOCUS->DeviceAction.FocusNode(s.id,node.id);else->DeviceAction.SetText(s.id,node.id,c.value!!)}
     File(dir,"$name.native.txt").writeText("expected=$expected\nactual=$bound\nexact_native_action_match=${bound is OwlBindingResult.Bound && bound.action==expected}\nSYNTHETIC_RECEIPT_AND_CLOCK_ONLY; no dispatch or phone authorization evidence")
    }catch(e:Throwable){File(dir,"$name.codec-error.txt").writeText(e.stackTraceToString());event("VALIDATION_ERROR $name ${e.message}")}
   }catch(e:Throwable){File(dir,"$name.error.txt").writeText(e.stackTraceToString());event("GENERATE_ERROR $name elapsedMs=${(System.nanoTime()-t)/1000000} ${e.message}")}
  }
 }catch(e:Throwable){File(dir,"fatal.error.txt").writeText(e.stackTraceToString());event("FATAL ${e.message}")}
 finally{event("FINAL_NATIVE_CLOSE_ACK=${r.close()} status=${r.status}")}
}
