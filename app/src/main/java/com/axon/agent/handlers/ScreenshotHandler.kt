package com.axon.agent.handlers

import android.graphics.Bitmap
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcHandler
import com.axon.agent.screenshot.ScreenshotRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * screenshot — capture via AccessibilityService.takeScreenshot(), compress, and
 * reply with JSON metadata immediately followed by the image as a binary frame.
 *
 * The handler returns the metadata as its result and stashes the encoded bytes in
 * [RpcContext.binary]; the dispatcher then emits metadata + `[4-byte id BE][image]`
 * atomically. JPEG quality defaults to ~80; PNG is lossless (quality ignored).
 */
object ScreenshotHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val req = ScreenshotRequest.parse(params)
        val bitmap = ctx.agent.captureScreenshot()
        try {
            val format = if (req.format == ScreenshotRequest.PNG) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
                bitmap.compress(format, req.quality, out)
                out.toByteArray()
            }
            ctx.binary = bytes
            return buildJsonObject {
                put("screen", ctx.agent.screen.value)
                put("format", req.format)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put("bytes", bytes.size)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
