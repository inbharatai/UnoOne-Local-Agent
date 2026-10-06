import com.unoone.agent.core.task.*
import com.unoone.agent.core.model.*
import com.unoone.agent.localbrain.QwenMnnPlanner
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

fun main(args:Array<String>) = runBlocking {
 val dir=File(args[0]); val planner=QwenMnnPlanner(); val started=System.nanoTime()
 check(planner.load("/agent/workspace/qwen-validation-model/config.json",BrainModelRegistry.QWEN3_5_2B) is com.unoone.agent.core.model.Result.Success)
 File(dir,"receipt.json").writeText(planner.configReceipt()!!)
 println("LOAD_MS="+(System.nanoTime()-started)/1_000_000)
 val active=AtomicInteger(); val peak=AtomicInteger(); val calls=AtomicInteger(); val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
 val executions=java.util.concurrent.ConcurrentHashMap<String,DraftQualityGate.Execution>()
 val counts=java.util.concurrent.ConcurrentHashMap<String,Int>()
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val kind=WorkerKind("draft")
 val coordinator=TaskCoordinator(listOf(WorkerRegistration(kind,WorkerLane.BACKGROUND,NativeTaskWorker { ctx ->
   val request=DraftRequest.decode(ctx.instruction)
   val execution=DraftQualityGate.execute(request) { prompt, phrases ->
     ProcessTaskResources.model.withLease(ctx.taskId,ctx::checkActive) {
       if(request.requiredPhrases.contains("ORCHID731") && !entered.isCompleted) { entered.complete(Unit); release.await() }
       ctx.beforeModelCall(); ctx.emit(0,TaskCapability.MODEL,ReceiptStage.DISPATCH_INTENT)
       val attempt=counts.merge(ctx.taskId.value,1,Int::plus)!!; val prefix="${ctx.taskId.value}-$attempt"
       val n=active.incrementAndGet(); peak.updateAndGet { maxOf(it,n) }; calls.incrementAndGet()
       val start=System.nanoTime(); println("CALL_START $prefix NS=$start ACTIVE=$n")
       try {
         val bridge=LocalBrainDraftBridge(planner) { system,user -> File(dir,"$prefix-system.txt").writeText(system); File(dir,"$prefix-prompt.txt").writeText(user) }
         val out=bridge.draftText(prompt,phrases)
         if(out is com.unoone.agent.core.model.Result.Success) File(dir,"$prefix-raw-output.txt").writeText(out.data)
         else File(dir,"$prefix-error.txt").writeText(out.toString())
         println("CALL_END $prefix MS="+(System.nanoTime()-start)/1_000_000)
         ctx.emit(1,TaskCapability.MODEL,ReceiptStage.UNVERIFIED)
         out
       } finally { active.decrementAndGet() }
     }
   }
   executions[ctx.taskId.value]=execution
   File(dir,"${ctx.taskId.value}-execution.json").writeText(buildJsonObject {
     put("taskId",ctx.taskId.value); put("request",Json.parseToJsonElement(request.encode())); put("outcome",execution.outcome.name)
     put("error",execution.error?.toString()?.let(::JsonPrimitive) ?: JsonNull)
     put("attempts",JsonArray(execution.attempts.map { a -> buildJsonObject {
       put("text",a.text); put("passed",a.checks.passed); put("nonblank",a.checks.nonblank)
       put("failedPhraseIndexes",JsonArray(a.checks.failedPhraseIndexes.map(::JsonPrimitive))); put("semanticFactsVerified",a.checks.semanticFactsVerified)
       put("oppositeMarkerObservationOnly",a.text.contains(if(request.requiredPhrases.contains("ORCHID731")) "COBALT942" else "ORCHID731"))
     } }))
   }.toString())
   WorkerResult.Finished(TaskResult(execution.outcome))
 })),scope)
 fun submit(key:String,text:String,phrases:List<String>)=(coordinator.submit(TaskRequest(RequestId(key),kind,DraftRequest(text,phrases).encode(),TaskScope(setOf(TaskCapability.MODEL)),coordinator.captureGeneration(),budget=TaskBudget(wallMillis=175000,actions=0,modelCalls=2))) as Admission.Accepted).taskId
 try {
   val a=submit("draft-a","Draft a short invitation to a garden club meeting. Include the exact project marker ORCHID731. Use placeholders for the date and recipient. Maximum 35 words.",listOf("ORCHID731"))
   entered.await()
   val b=submit("draft-b","Draft a short reminder to return a library book. Include the exact project marker COBALT942. Use placeholders for the date and recipient. Maximum 35 words.",listOf("COBALT942"))
   val c=submit("cancelled","Draft a notice with marker CANCELLED555.",listOf("CANCELLED555"))
   check(coordinator.tasks.value.single { it.id==c }.state==TaskState.QUEUED)
   println("QUEUED_CANCEL_ID=${c.value} RECEIPT=${coordinator.cancel(c)}")
   release.complete(Unit)
   val ar=coordinator.await(a); val br=coordinator.await(b); val cr=coordinator.await(c)
   println("RESULT_A=$ar\nRESULT_B=$br\nRESULT_CANCEL=$cr")
   File(dir,"journal.txt").writeText(coordinator.journalSnapshot().joinToString("\n"))
   val correct=listOf(a to ar,b to br).all { (id,result) -> result.outcome==executions[id.value]?.outcome && result.outcome!=TaskOutcome.VERIFIED }
   val serialized=peak.get()==1; val cancelled=cr.outcome==TaskOutcome.CANCELLED && !counts.containsKey(c.value)
   File(dir,"assessment.json").writeText(buildJsonObject {
     put("policyOutcomeCorrect",correct); put("modelCallsSerialized",serialized); put("maxOverlappingCalls",peak.get()); put("totalModelCalls",calls.get()); put("queuedThirdCancelledNoNativeCall",cancelled)
     put("taskA",a.value); put("taskB",b.value); put("taskCancelled",c.value)
     put("allDraftConstraintsMet",executions.values.all { it.last?.checks?.passed==true })
     put("repeatFailure",executions.values.any { it.attempts.size==2 && it.last?.checks?.passed==false })
     put("oppositeMarkerIsObservationOnly",true); put("semanticFactsVerified",false)
   }.toString())
   check(correct && serialized && cancelled && counts.values.all { it in 1..2 })
 } finally { coordinator.close(); scope.cancel(); val ack=planner.close(); println("NATIVE_CLOSE_ACK=$ack"); File(dir,"close-ack.txt").writeText(ack.toString()) }
}
