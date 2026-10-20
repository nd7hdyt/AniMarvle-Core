package eu.kanade.tachiyomi.util

import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle as coreAwaitSingle

/**
 * Bridges RxJava `Observable` into the coroutine world.
 *
 * In Aniyomi v0.18.2.1 this file is an `expect`/`actual` pair whose Android `actual` delegates
 * to `tachiyomi.core.common.util.lang.awaitSingle`. We flattened the KMP module into a plain
 * Android library, so that delegation is inlined here against our own shim of that helper.
 */
suspend fun <T : Any> Observable<T>.awaitSingle(): T = coreAwaitSingle()