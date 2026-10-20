package com.karewinkcloud.agentweb.client.ui

/**
 * What an owner reads from and writes to the view model's one state: the current [ClientState], and
 * an update applied to it. An owner only touches the slice it is responsible for.
 */
internal interface StateHost {
    /** The state as it is now. */
    fun state(): ClientState
    /** Applies [change] to the current state. */
    fun update(change: (ClientState) -> ClientState)
}
