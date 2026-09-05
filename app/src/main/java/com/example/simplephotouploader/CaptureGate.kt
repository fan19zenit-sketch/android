package com.example.simplephotouploader

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class CaptureGate {
    private val owner = AtomicReference<String?>(null)
    val busy: Boolean get() = owner.get() != null
    fun begin(): String? {
        val id = UUID.randomUUID().toString()
        return id.takeIf { owner.compareAndSet(null, id) }
    }
    fun finish(id: String): Boolean = owner.compareAndSet(id, null)
}
