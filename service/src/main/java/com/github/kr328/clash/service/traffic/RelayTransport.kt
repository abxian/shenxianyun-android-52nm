package com.github.kr328.clash.service.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Fixed-origin API fallback; subscription responses never stored/cached here. */
object RelayTransport {
    private const val BASE="https://ll.sxnn.de"
    private const val TENANT="52nm"
    private const val GRANTS="relay-grants-v1.enc"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val guard=Any()
    data class Reply(val status: Int,val json: JSONObject,val retryAfter: Long=0)
    private fun key(token: String)=MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun cached(context: Context,token: String): JSONObject?=synchronized(guard) {
        val all=TrafficSecureStore.read(context,GRANTS)?.let {JSONObject(it)} ?: return@synchronized null
        all.optJSONObject(key(token))?.takeIf {it.optString("base")==BASE && it.optString("tenant")==TENANT && it.optLong("expires_at")>System.currentTimeMillis()/1000+60}
    }
    fun prime(context: Context,apiBase: String,token: String) {scope.launch {runCatching {grant(context.applicationContext,apiBase,token)}}}
    fun request(context: Context,url: String,method: String,token: String,body: JSONObject?=null): Reply {
        require(url.startsWith("https://"))
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val underlying=manager.allNetworks.filter {manager.getNetworkCapabilities(it)?.let {c -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}.take(1)
        var failure: Exception?=null
        for(network in underlying+listOf(null)) {
            var connection: HttpURLConnection?=null
            try {
                val target=URL(url)
                connection=((network?.openConnection(target)?:target.openConnection()) as HttpURLConnection).apply {
                    connectTimeout=2000;readTimeout=3000;requestMethod=method;instanceFollowRedirects=false;useCaches=false
                    setRequestProperty("Authorization","Bearer $token")
                    setRequestProperty("Content-Type","application/json")
                    setRequestProperty("User-Agent","Official-Android-Management/1.1")
                    setRequestProperty("Cache-Control","no-cache")
                }
                if(body!=null) {
                    val bytes=body.toString().toByteArray(Charsets.UTF_8);connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use {it.write(bytes)}
                }
                val status=connection.responseCode
                val stream=if(status in 200..299)connection.inputStream else connection.errorStream
                val text=stream?.bufferedReader()?.use {it.readText().also {value ->require(value.length<=2*1024*1024)}}.orEmpty()
                val json=runCatching {JSONObject(text)}.getOrElse {JSONObject()}
                return Reply(status,json,(connection.getHeaderField("Retry-After")?.toLongOrNull()?:0).coerceIn(0,3600)*1000)
            }catch(error: Exception){failure=error}finally {connection?.disconnect()}
        }
        throw failure?:java.io.IOException("management network unavailable")
    }
    private fun grant(context: Context,apiBase: String,token: String): JSONObject {
        cached(context,token)?.let {return it}
        val primary=runCatching {request(context,apiBase.trimEnd('/')+"/api/v2/client/relay","POST",token,JSONObject())}.getOrNull()
        val response=if(primary!=null && primary.status<500)primary else request(context,"$BASE/v1/client/$TENANT/authorize","POST",token,JSONObject())
        require(response.status==200 && response.json.optBoolean("ok"))
        val value=response.json.getJSONObject("relay")
        require(value.getString("base")==BASE && value.getString("tenant")==TENANT && value.getLong("expires_at")>System.currentTimeMillis()/1000+60)
        synchronized(guard) {
            val all=TrafficSecureStore.read(context,GRANTS)?.let {JSONObject(it)}?:JSONObject()
            // Bounded credentials cache; old grants are removable, pending usage is elsewhere.
            val iterator=all.keys();val expired=mutableListOf<String>()
            while(iterator.hasNext()){val k=iterator.next();if(all.getJSONObject(k).optLong("expires_at")<=System.currentTimeMillis()/1000)expired.add(k)}
            expired.forEach {all.remove(it)}
            if(all.length()>=32)all.keys().asSequence().firstOrNull()?.let {all.remove(it)}
            all.put(key(token),value);TrafficSecureStore.write(context,GRANTS,all.toString())
        }
        return value
    }
    fun enqueue(context: Context,apiBase: String,token: String,payload: JSONObject): Reply {
        val auth=grant(context,apiBase,token)
        return request(context,"$BASE/v1/traffic/$TENANT","POST",auth.getString("grant"),payload)
    }
    fun subscription(context: Context,token: String): String {
        val reply=request(context,"$BASE/v1/client/$TENANT/subscription","POST",token,JSONObject())
        require(reply.status==200 && reply.json.optBoolean("ok"))
        return reply.json.getString("content").also {require(it.isNotBlank())}
    }
    fun presence(context: Context,token: String,path: String,body: JSONObject): Reply {
        require(path=="heartbeat" || path=="offline")
        return request(context,"$BASE/v1/client/$TENANT/$path","POST",token,body)
    }
}
