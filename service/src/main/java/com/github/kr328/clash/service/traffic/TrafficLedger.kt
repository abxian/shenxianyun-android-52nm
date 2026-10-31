package com.github.kr328.clash.service.traffic

/** Monotonic, durable proxy payload ledger. Pending is frozen across retries. */
data class ByteTotals(val upload: Long=0, val download: Long=0) {
    init { require(upload>=0 && download>=0) }
    operator fun plus(other: ByteTotals)=ByteTotals(upload+other.upload,download+other.download)
    operator fun minus(other: ByteTotals)=ByteTotals((upload-other.upload).coerceAtLeast(0),(download-other.download).coerceAtLeast(0))
    fun regressedFrom(other: ByteTotals)=upload<other.upload || download<other.download
    fun isEmpty()=upload==0L && download==0L
}
data class LedgerCounter(
    var id: String,
    val run: String,
    val scope: String,
    var configuration: String,
    var raw: ByteTotals=ByteTotals(),
    var total: ByteTotals=ByteTotals(),
    var acknowledged: ByteTotals=ByteTotals(),
    var sequence: Long=0,
    var pending: ByteTotals?=null,
) {
    fun observe(next: ByteTotals) {
        require(!next.regressedFrom(raw))
        total += next-raw
        raw=next
    }
    fun freeze(maxBytes: Long=5L*1024*1024*1024): ByteTotals? {
        pending?.let { return it }
        val delta=total-acknowledged
        if(delta.isEmpty()) return null
        val up=delta.upload.coerceAtMost(maxBytes)
        val down=delta.download.coerceAtMost(maxBytes-up)
        return (acknowledged+ByteTotals(up,down)).also { pending=it }
    }
    fun accept(sent: ByteTotals, sentSequence: Long) {
        require(sent==pending && sentSequence==sequence+1)
        acknowledged=sent;sequence=sentSequence;pending=null
    }
    fun rebase(newId: String) {
        total=total-acknowledged;acknowledged=ByteTotals();pending=null;sequence=0;id=newId
    }
}
