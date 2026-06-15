package com.axon.agent.rpc

import com.axon.agent.core.Agent
import com.axon.agent.server.ConnectionState

/**
 * Everything a handler may touch while serving one request: the originating
 * [connection] (for the per-connection event-stream toggle and its serialized
 * writer) and the [agent] (scope, tree, root, gestures, screenshots, ...).
 * Rebuilt per message; holds no cross-request state.
 */
class RpcContext(
    val connection: ConnectionState,
    val agent: Agent,
) {
    /**
     * Optional trailing binary payload (screenshots). When a handler sets this,
     * the dispatcher emits the JSON metadata and this binary frame atomically as
     * one back-to-back pair via the connection's FrameWriter.
     */
    var binary: ByteArray? = null
}
