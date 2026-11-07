package com.example.simplephotouploader

/** Do not reuse a loaded binding or shift after device, backend, or city changes. */
object CookScopeGuard {
    fun matches(loadedScope: String?, currentScope: String): Boolean =
        loadedScope != null && loadedScope == currentScope

    fun binding(binding: CookBinding?, loadedScope: String?, currentScope: String): CookBinding? =
        binding?.takeIf { matches(loadedScope, currentScope) }
}
