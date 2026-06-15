package com.axon.agent.rpc

/** Maps a method name to its handler. One table, no scattered lookups. */
class MethodRouter(private val handlers: Map<String, RpcHandler>) {
    fun handler(method: String): RpcHandler? = handlers[method]
    fun methods(): Set<String> = handlers.keys
}
