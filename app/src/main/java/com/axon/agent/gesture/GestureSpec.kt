package com.axon.agent.gesture

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A single touch point in screen pixels. */
data class Point(val x: Int, val y: Int)

/**
 * One continuous contact: a path of [points] starting at [startTime] ms (offset
 * from the start of the whole gesture) and lasting [duration] ms.
 */
data class Stroke(val points: List<Point>, val startTime: Long, val duration: Long)

/** A gesture is one or more parallel strokes (multi-touch = >1 stroke). */
data class Gesture(val strokes: List<Stroke>)

/**
 * Pure parser/validator for the `gesture` params. Android-free so the validation
 * rules are unit-testable; the system limits ([maxStrokes], [maxDurationMs]) are
 * injected by the handler from GestureDescription.
 *
 * Shape: { "strokes": [ { "points": [{"x","y"}, ...], "startTime": ms, "duration": ms } ] }
 */
object GestureSpec {

    fun parse(params: JsonObject?, maxStrokes: Int, maxDurationMs: Long): Gesture {
        val strokesArr = params?.get("strokes") as? JsonArray
            ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "missing 'strokes' array")
        if (strokesArr.isEmpty()) {
            throw RpcException(ErrorCodes.INVALID_PARAMS, "'strokes' must not be empty")
        }
        if (strokesArr.size > maxStrokes) {
            throw RpcException(ErrorCodes.INVALID_PARAMS, "too many strokes (max $maxStrokes)")
        }

        val strokes = strokesArr.mapIndexed { i, el ->
            val obj = el as? JsonObject
                ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i] is not an object")

            val pointsArr = (obj["points"] as? JsonArray)?.takeIf { it.isNotEmpty() }
                ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].points missing or empty")

            val points = pointsArr.mapIndexed { j, p ->
                val po = p as? JsonObject
                    ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].points[$j] is not an object")
                val x = po["x"]?.jsonPrimitive?.intOrNull
                    ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].points[$j].x missing")
                val y = po["y"]?.jsonPrimitive?.intOrNull
                    ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].points[$j].y missing")
                Point(x, y)
            }

            val startTime = obj["startTime"]?.jsonPrimitive?.longOrNull ?: 0L
            val duration = obj["duration"]?.jsonPrimitive?.longOrNull
                ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].duration missing")

            if (startTime < 0) {
                throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].startTime must be >= 0")
            }
            if (duration <= 0) {
                throw RpcException(ErrorCodes.INVALID_PARAMS, "stroke[$i].duration must be > 0")
            }
            if (duration > maxDurationMs) {
                throw RpcException(
                    ErrorCodes.INVALID_PARAMS,
                    "stroke[$i].duration ${duration}ms exceeds system limit ${maxDurationMs}ms"
                )
            }
            Stroke(points, startTime, duration)
        }
        return Gesture(strokes)
    }
}
