package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.traffic.ServiceTrafficAccounting
import kotlinx.coroutines.*

class TrafficReportingModule(service: Service): Module<Unit>(service) {
    override suspend fun run() = coroutineScope {
        launch {
            while(isActive) {
                try {ServiceTrafficAccounting.report(service)}
                catch(error: CancellationException){throw error}
                catch(_: Exception){Log.w("Traffic reporting failed; durable state retained")}
                delay(1000)
            }
        }
        while(isActive) {
            try {ServiceTrafficAccounting.sample(service)}
            catch(error: CancellationException){throw error}
            catch(_: Exception){Log.w("Traffic sampling failed; durable state retained")}
            delay(1000)
        }
    }
}
