package com.github.kr328.clash.service.traffic

import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Owned solely by the background VPN/core process. UI only writes encrypted config. */
object ServiceTrafficAccounting {
    private const val CONFIG="traffic-service-config-v1.enc"
    private const val STATE="traffic-service-ledger-v1.enc"
    private val lock=Mutex()
    private val sender=Mutex()
    private var initialized=false
    private val counters=mutableListOf<LedgerCounter>()
    private var run=""
    private var profile: String?=null
    private var current: LedgerCounter?=null
    private var lastRaw=ByteTotals()
    private var nextAttempt=0L
    private var failures=0
    private var reportCursor=0

    fun configure(context: Context,json: String) {
        val obj=JSONObject(json)
        require(obj.getString("api_base").startsWith("https://"))
        require(obj.getString("device_token").isNotBlank() && obj.getString("profile").isNotBlank())
        TrafficSecureStore.write(context,CONFIG,json)
    }
    private fun totals(json: JSONObject,key: String)=json.optJSONObject(key)?.let {ByteTotals(it.getLong("up"),it.getLong("down"))} ?: ByteTotals()
    private fun encode(value: ByteTotals)=JSONObject().put("up",value.upload).put("down",value.download)
    private fun initialize(context: Context) {
        if(initialized)return
        // A corrupt encrypted ledger must fail closed, never silently erase pending usage.
        val saved=TrafficSecureStore.read(context,STATE)
        if(saved!=null) {
            val array=JSONObject(saved).getJSONArray("counters")
            for(i in 0 until array.length()) {
                val j=array.getJSONObject(i)
                counters.add(LedgerCounter(j.getString("id"),j.getString("run"),j.getString("scope"),j.getString("config"),totals(j,"raw"),totals(j,"total"),totals(j,"ack"),j.getLong("sequence"),if(j.has("pending"))totals(j,"pending")else null))
            }
        }
        initialized=true
    }
    private fun persist(context: Context) {
        val array=JSONArray()
        counters.forEach { c ->
            val j=JSONObject().put("id",c.id).put("run",c.run).put("scope",c.scope).put("config",c.configuration).put("raw",encode(c.raw)).put("total",encode(c.total)).put("ack",encode(c.acknowledged)).put("sequence",c.sequence)
            c.pending?.let {j.put("pending",encode(it))};array.put(j)
        }
        TrafficSecureStore.write(context,STATE,JSONObject().put("counters",array).toString())
    }
    suspend fun begin(context: Context)=lock.withLock {
        initialize(context);run=UUID.randomUUID().toString();profile=null;current=null;lastRaw=ByteTotals();nextAttempt=0L;failures=0
    }
    suspend fun profileLoaded(context: Context,uuid: String)=lock.withLock {
        initialize(context);profile=uuid
        // Baseline at the exact configuration transition, not the first UI tick.
        sampleLocked(context)
    }
    suspend fun sample(context: Context)=lock.withLock {initialize(context);sampleLocked(context)}
    private fun sampleLocked(context: Context) {
        val numbers=Clash.queryTrafficTotalBytes()
        if(numbers.size!=2 || numbers.any {it<0})return
        val raw=ByteTotals(numbers[0],numbers[1])
        val config=TrafficSecureStore.read(context,CONFIG)?.let {JSONObject(it)}
        val scope=config?.let {it.getString("code")+":"+it.getString("client_id")+":"+it.getString("profile")}
        val billable=config!=null && config.getString("profile")==profile
        val prior=current
        val priorOnProfile=prior!=null && JSONObject(prior.configuration).getString("profile")==profile
        val regressed=prior!=null && raw.regressedFrom(prior.raw)
        // Charge the previous identity through the exact profile transition even
        // when the UI has already written the next identity's configuration.
        if(prior!=null && prior.run==run && priorOnProfile && !regressed)prior.observe(raw)
        if(prior!=null && prior.run==run && billable && prior.scope==scope && !regressed) {
            counters.filter {it.scope==scope}.forEach {it.configuration=config!!.toString()}
        } else if(billable) {
            val initial=if(raw.regressedFrom(lastRaw))raw else raw-lastRaw
            current=LedgerCounter(UUID.randomUUID().toString(),run,scope!!,config.toString(),raw,initial).also {counters.add(it)}
        } else if(!priorOnProfile)current=null
        lastRaw=raw
        counters.removeAll {it!==current && it.pending==null && (it.total-it.acknowledged).isEmpty()}
        persist(context)
    }
    suspend fun report(service: Service,force: Boolean=false) {
        if(!sender.tryLock())return
        try {
            val packet=lock.withLock {
                initialize(service)
                if(!force && android.os.SystemClock.elapsedRealtime()<nextAttempt)return@withLock null
                val ready=counters.filter {it.pending!=null || !(it.total-it.acknowledged).isEmpty()}
                if(ready.isEmpty())return@withLock null
                val c=ready[(reportCursor++ and Int.MAX_VALUE)%ready.size]
                val frozen=c.freeze() ?: return@withLock null
                persist(service)
                Triple(c, frozen, c.sequence+1)
            } ?: return
            val (counter,frozen,sequence)=packet
            val result=withContext(Dispatchers.IO) {send(service,counter, frozen, sequence)}
            lock.withLock {
                when(result.kind) {
                    "accepted","accepted_limit" -> counter.accept(frozen,sequence)
                    "reset" -> counter.rebase(UUID.randomUUID().toString())
                }
                val success=result.kind in listOf("accepted","accepted_limit","reset")
                failures=if(success)0 else (failures+1).coerceAtMost(7)
                val delay=if(success)(30_000L+(Math.random()*5_000L).toLong()) else (15_000L shl (failures-1).coerceAtLeast(0)).coerceAtMost(1_800_000L)
                nextAttempt=android.os.SystemClock.elapsedRealtime()+maxOf(delay,result.retryAfter)
                persist(service)
            }
            if(result.kind in listOf("limit","accepted_limit"))service.stopSelf()
        } finally {sender.unlock()}
    }
    private data class Result(val kind: String,val retryAfter: Long=0)
    private fun send(context: Context,c: LedgerCounter,total: ByteTotals,sequence: Long): Result {
        val config=JSONObject(c.configuration)
        val url=URL(config.getString("api_base").trimEnd('/')+"/api/v2/client/traffic")
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network=manager.allNetworks.firstOrNull {manager.getNetworkCapabilities(it)?.let {cap ->cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
        val connection=((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout=5000;readTimeout=5000;requestMethod="POST";doOutput=true;instanceFollowRedirects=false
            setRequestProperty("Authorization","Bearer "+config.getString("device_token"))
            setRequestProperty("X-Client-Id",config.getString("client_id"))
            setRequestProperty("Content-Type","application/json")
            setRequestProperty("User-Agent","Shenxianyun-Android/"+config.getString("version"))
        }
        return try {
            val body=JSONObject().put("counter_id",c.id).put("sequence",sequence).put("upload_total",total.upload).put("download_total",total.download).put("platform","android").put("app_name",config.optString("app_name", "吾爱云安卓端")).put("app_version",config.getString("version")).put("device_name",config.getString("device"))
            connection.outputStream.use {it.write(body.toString().toByteArray(Charsets.UTF_8))}
            val status=connection.responseCode
            val text=(if(status in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader()?.use {it.readText()}.orEmpty()
            val json=runCatching {JSONObject(text)}.getOrNull()
            when {
                status in 200..299 && json?.optBoolean("ok")==true -> Result("accepted")
                status==409 && json?.optString("code")=="counter_reset" -> Result("reset")
                status==403 && json?.optString("code")=="traffic_limit" -> Result(if(json.has("accepted_upload")&&json.has("accepted_download"))"accepted_limit" else "limit")
                else -> Result("failed",(connection.getHeaderField("Retry-After")?.toLongOrNull()?:0L).coerceIn(0L,3600L)*1000L)
            }
        } catch(_: Exception) {Result("failed")} finally {connection.disconnect()}
    }
    suspend fun finish(service: Service) {
        // Persist before the core reset even if the network is unavailable. A
        // frozen uncertain HTTP request is retried with the same id/sequence.
        Clash.suspendCore(true)
        Clash.stopAccountingTraffic()
        sample(service)
        withTimeoutOrNull(11_000L) {report(service,true)}
    }
}
