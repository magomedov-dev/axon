package com.axon.agent.screenshot

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed/validated `screenshot` params. Pure (no Android) — testable.
 *
 * { "format": "jpeg"|"png" (default jpeg), "quality": 0..100 (jpeg, default 80) }
 */
data class ScreenshotRequest(val format: String, val quality: Int) {
    companion object {
        const val JPEG = "jpeg"
        const val PNG = "png"
        const val DEFAULT_QUALITY = 80

        fun parse(params: JsonObject?): ScreenshotRequest {
            val format = params?.get("format")?.jsonPrimitive?.contentOrNull ?: JPEG
            if (format != JPEG && format != PNG) {
                throw RpcException(ErrorCodes.INVALID_PARAMS, "invalid 'format': '$format' (expected jpeg or png)")
            }
            val quality = params?.get("quality")?.jsonPrimitive?.intOrNull ?: DEFAULT_QUALITY
            if (quality !in 0..100) {
                throw RpcException(ErrorCodes.INVALID_PARAMS, "'quality' must be 0..100")
            }
            return ScreenshotRequest(format, quality)
        }
    }
}
