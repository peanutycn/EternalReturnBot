package cn.luorenmu.alert

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Adapter-specific messenger that can deliver messages to configured super admins.
 *
 * Implementations live in adapter modules (e.g. onebot).
 */
fun interface SuperAdminMessenger {
    suspend fun sendToSuperAdmins(text: String)
}

class SuperAdminMessengerContext(
    val messenger: SuperAdminMessenger,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SuperAdminMessengerContext>
}

