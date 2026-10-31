package com.github.kr328.clash.service.traffic
import org.junit.Test
import org.junit.Assert.*
class TrafficLedgerTest {
    private fun counter()=LedgerCounter("id","run","scope","encrypted-config")
    @Test fun backgroundHundredsOfMegabytesAreNotLostAfterFirstRequest() {
        val c=counter();c.observe(ByteTotals(51896,70885));val first=c.freeze()!!
        c.observe(ByteTotals(155_570_000,181_140_000))
        assertEquals(first,c.freeze()) // exact retry until ack, even after screen closes
        c.accept(first,1)
        val second=c.freeze()!!;assertEquals(ByteTotals(155_570_000,181_140_000),second)
        c.accept(second,2);assertNull(c.freeze())
    }
    @Test fun uncertainResponseRetainsSameSequenceAndCounter() {
        val c=counter();c.observe(ByteTotals(1,200));val pending=c.freeze()
        assertEquals(pending,c.freeze());assertEquals(0L,c.sequence);assertEquals("id",c.id)
    }
    @Test fun overFiveGiBIsChunkedAndNeverDiscarded() {
        val c=counter();val size=12L*1024*1024*1024;c.observe(ByteTotals(size,size));var seq=0L
        while(c.freeze()!=null) {val p=c.pending!!;val delta=p-c.acknowledged;assertTrue(delta.upload+delta.download<=5L*1024*1024*1024);c.accept(p,++seq)}
        assertEquals(ByteTotals(size,size),c.acknowledged)
    }
    @Test fun coreEpochKeepsUnacceptedOldCounterSeparate() {
        val old=counter();old.observe(ByteTotals(90,120));val pending=old.freeze()
        val new=LedgerCounter("new","second-run","scope","config");new.observe(ByteTotals(5,7))
        assertEquals(ByteTotals(90,120),pending);assertEquals(ByteTotals(5,7),new.freeze());assertEquals("id",old.id)
    }
    @Test fun serverRebasePreservesOnlyUnacceptedBytes() {
        val c=counter();c.observe(ByteTotals(10,20));c.accept(c.freeze()!!,1);c.observe(ByteTotals(40,80));c.freeze();c.rebase("new")
        assertEquals(ByteTotals(30,60),c.freeze());assertEquals(0L,c.sequence)
    }
}
