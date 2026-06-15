package com.axon.agent.rpc

/**
 * Thrown by handlers to signal a well-formed RPC error. The dispatcher turns it
 * into `{ "id": ..., "error": { "code", "message" } }`. Use an [ErrorCodes]
 * constant for [code]. Handlers must NOT retry silently — retries are the PC's job.
 */
class RpcException(
    val code: String,
    message: String,
) : Exception(message)
