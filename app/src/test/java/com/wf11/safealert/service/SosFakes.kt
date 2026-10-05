package com.wf11.safealert.service

/** One transport call held open: the test decides when, and how, the server answers by invoking [cb]. */
internal class SosCall<T>(val path: String, val key: String, val cb: (T) -> Unit)

/** SosTransport that records every call and never answers on its own. Keys are handed out as k1, k2, ... */
internal class FakeSosTransport(
    var uid: String? = "u1",
    var site: String? = "root/site"
) : SosTransport {
    var keyN = 0
    val creates = ArrayList<SosCall<Boolean>>()
    val recs = ArrayList<SosLedger.Record>()
    val resolves = ArrayList<SosCall<Boolean>>()
    val autos = ArrayList<Boolean>()
    val reads = ArrayList<SosCall<SosLedger.Remote>>()
    override fun uid() = uid
    override fun sitePath() = site
    override fun newKey(path: String) = "k" + (++keyN)
    override fun create(path: String, key: String, rec: SosLedger.Record, uid: String, done: (Boolean) -> Unit) {
        recs.add(rec)
        creates.add(SosCall(path, key, done))
    }
    override fun resolve(path: String, key: String, auto: Boolean, done: (Boolean) -> Unit) {
        resolves.add(SosCall(path, key, done))
        autos.add(auto)
    }
    override fun read(path: String, key: String, done: (SosLedger.Remote) -> Unit) {
        reads.add(SosCall(path, key, done))
    }
}
