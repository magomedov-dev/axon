package com.axon.agent.handlers

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.axon.agent.gesture.Gesture
import com.axon.agent.gesture.GestureSpec
import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcException
import com.axon.agent.rpc.RpcHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * gesture — the single coordinate primitive. tap / long-press / double-tap /
 * swipe / drag / multi-touch are all just different point counts, durations and
 * parallel stroke counts fed to dispatchGesture.
 *
 * The reply is sent ONLY after the gesture actually completes (see
 * [com.axon.agent.AutomationAccessibilityService.performGesture], which suspends
 * on the onCompleted callback). onCancelled / dispatch-refused -> GESTURE_FAILED.
 */
object GestureHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val gesture = GestureSpec.parse(
            params,
            maxStrokes = GestureDescription.getMaxStrokeCount(),
            maxDurationMs = GestureDescription.getMaxGestureDuration(),
        )
        val completed = ctx.agent.performGesture(build(gesture))
        if (!completed) {
            throw RpcException(
                ErrorCodes.GESTURE_FAILED,
                "gesture was cancelled or could not be dispatched"
            )
        }
        return buildJsonObject { put("success", true) }
    }

    private fun build(gesture: Gesture): GestureDescription {
        val builder = GestureDescription.Builder()
        for (stroke in gesture.strokes) {
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.x.toFloat(), first.y.toFloat())
            if (stroke.points.size == 1) {
                // A zero-length contour: a tap/long-press at one point.
                path.lineTo(first.x.toFloat(), first.y.toFloat())
            } else {
                for (p in stroke.points.drop(1)) {
                    path.lineTo(p.x.toFloat(), p.y.toFloat())
                }
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.startTime, stroke.duration))
        }
        return builder.build()
    }
}
