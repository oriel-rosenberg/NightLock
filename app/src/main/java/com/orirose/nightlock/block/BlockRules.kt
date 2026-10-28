package com.orirose.nightlock.block

import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.Essentials
import java.time.LocalDateTime

/** Which packages are blocked at one moment. Plain logic, so it can be unit tested. */
class BlockRules(val active: Boolean, val blocked: Set<String>) {

    fun blocks(packageName: String): Boolean = active && packageName in blocked

    companion object {
        val OFF = BlockRules(active = false, blocked = emptySet())

        /** Allowlisted and essential apps are taken out of the block list, whatever the config says. */
        fun from(config: Config, essentials: Essentials, now: LocalDateTime) = BlockRules(
            active = config.isBlocking(now),
            blocked = config.blockedPackagesAt(now) - config.allowlist(essentials),
        )
    }
}
