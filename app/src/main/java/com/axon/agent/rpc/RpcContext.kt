package com.axon.agent.rpc

import com.axon.agent.core.Agent
import com.axon.agent.server.ConnectionState

/**
 * Everything a handler may touch while serving one request: the originating
 * [connection] (for the per-connection event-stream toggle) and the [agent]
 * (coroutine scope, tree dispatcher, and — in later stages — node/screenshot
 * access). Rebuilt per message; holds no cross-request state.
 */
class RpcContext(
    val connection: ConnectionState,
    val agent: Agent,
)
