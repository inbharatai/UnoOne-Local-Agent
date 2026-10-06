package com.unoone.agent.localbrain.owl
import com.unoone.agent.core.model.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
object RaceNative { external fun reset(); external fun count(which: Int): Int; external fun ack(value: Boolean); external fun output(mode: Int) }
private class Barrier { val entered=CountDownLatch(1); val release=CountDownLatch(1); fun pause(){ entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }; fun await(){check(entered.await(10,TimeUnit.SECONDS))} }
fun main()=runBlocking {
 System.loadLibrary("unoone_owl")
 val root=System.getProperty("race.root")
 val selected=BrainModelRegistry.GUI_OWL_1_5_4B_INSTRUCT
 for(stage in listOf("beforeRuntime","published","loaded","postRuntime")) {
  RaceNative.reset(); val p=OwlLlamaPlanner(); val b=Barrier()
  if(stage=="published" || stage=="loaded") p.runtime.loadStage={if(it==stage)b.pause()} else p.loadStage={if(it==stage)b.pause()}
  val job=async(Dispatchers.Default){runCatching {p.load(root,selected)}}
  withContext(Dispatchers.IO){b.await()}; p.requestCancel(); b.release.countDown();job.await()
  check(!p.isLoaded()); check(E4bRuntimeCoordinator.snapshot().state!=E4bRuntimeState.PHONE_READY)
  check(RaceNative.count(0)==if(stage=="beforeRuntime")0 else 1)
  check(RaceNative.count(1)==if(stage=="postRuntime" || stage=="loaded")1 else 0)
  check(p.close()); println("PASS barrier $stage")
 }
 RaceNative.reset(); val p=OwlLlamaPlanner(); E4bRuntimeCoordinator.operationMutex.lock()
 val queued=async(start=CoroutineStart.UNDISPATCHED){runCatching{p.load(root,selected)}}
 p.requestCancel();E4bRuntimeCoordinator.operationMutex.unlock(); queued.await();check(RaceNative.count(0)==0);check(!p.isLoaded());println("PASS queued original permit")
 RaceNative.reset();val q=OwlLlamaPlanner();val b=Barrier();q.runtime.loadStage={if(it=="published")b.pause()}
 val pending=async(Dispatchers.Default){runCatching{q.load(root,selected)}}
 withContext(Dispatchers.IO){b.await()};RaceNative.ack(false);q.requestCancel();b.release.countDown();pending.await()
 E4bRuntimeCoordinator.operationMutex.lock();check(!E4bRuntimeCoordinator.canReplaceAllocation(Any(),E4bRuntimeCoordinator.PHONE_OWNER));E4bRuntimeCoordinator.operationMutex.unlock()
 check(!q.close());RaceNative.ack(true);check(q.close());println("PASS ownership retained until close ACK")
 E4bRuntimeCoordinator.operationMutex.lock();check(E4bRuntimeCoordinator.canReplaceAllocation(Any(),E4bRuntimeCoordinator.PHONE_OWNER));E4bRuntimeCoordinator.operationMutex.unlock()
 RaceNative.reset();val normal=OwlLlamaPlanner();check(normal.load(root,selected) is Result.Success);check(normal.load(root,selected) is Result.Success);check(normal.isLoaded());check(normal.close());println("PASS uncancelled original permit survives maintenance close/reload")
 RaceNative.reset();val rt=OwlLlamaRuntime();rt.load(root)
 RaceNative.output(0);check(rt.generate("ok","ok")=="\uD83D\uDE00\uDBFF\uDFFF")
 for(mode in 1..2){RaceNative.output(mode);check(runCatching{rt.generate("ok","ok")}.isFailure)}
 check(rt.close());println("PASS public strict output decoder supplementary/max scalar/malformed/truncated; JNI fixture only")
}
