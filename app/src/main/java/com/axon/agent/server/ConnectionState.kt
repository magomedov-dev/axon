package com.axon.agent.server

/**
 * Per-connection state. Deliberately tiny: the project is stateless, so the only
 * permitted state on a connection is the event-stream toggle (Stage 7) plus its
 * serialized writer. No node handles, no pending waits, nothing tied to UI.
 */
class ConnectionState(
    val id: Long,
    sender: Sender,
) {
    val writer = FrameWriter(sender)

    /** Whether server-push events are delivered to this connection. */
    @Volatile
    var eventStream: Boolean = false
}
