package com.acgcompass.domain.repository

/** Controls the lifecycle of the shared public-cache fill operation. */
interface CacheFillController {
    /** Cancels the active fill, if any. Rows committed before cancellation remain valid. */
    fun cancelPublicDiscoveryFill()
}
