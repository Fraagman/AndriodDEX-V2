package com.androiddex.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import java.util.Random

/**
 * Phase 2A - Synthetic Frame Generator
 * Generates deterministic frames independently of the Android UI to stress codecs.
 */
class SyntheticFrameGenerator(private val surface: Surface) {
    private val paint = Paint()
    private val random = Random()
    private var frameCounter = 0L

    fun generateStaticFrame(width: Int, height: Int, color: Int = Color.BLUE) {
        drawOnSurface(width, height) { canvas ->
            canvas.drawColor(color)
            drawOverlays(canvas, width, height)
        }
    }

    fun generateCheckerboard(width: Int, height: Int, squareSize: Int = 50) {
        drawOnSurface(width, height) { canvas ->
            for (x in 0 until width step squareSize) {
                for (y in 0 until height step squareSize) {
                    val isBlack = ((x / squareSize) + (y / squareSize)) % 2 == 0
                    paint.color = if (isBlack) Color.BLACK else Color.WHITE
                    canvas.drawRect(
                        x.toFloat(), y.toFloat(),
                        (x + squareSize).toFloat(), (y + squareSize).toFloat(),
                        paint
                    )
                }
            }
            drawOverlays(canvas, width, height)
        }
    }

    fun generateNoise(width: Int, height: Int) {
        drawOnSurface(width, height) { canvas ->
            // Simulating high-frequency noise which stresses bitrate
            for (x in 0 until width step 10) {
                for (y in 0 until height step 10) {
                    paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    canvas.drawRect(
                        x.toFloat(), y.toFloat(),
                        (x + 10).toFloat(), (y + 10).toFloat(),
                        paint
                    )
                }
            }
            drawOverlays(canvas, width, height)
        }
    }

    fun generateMovingGradient(width: Int, height: Int) {
        drawOnSurface(width, height) { canvas ->
            val offset = (frameCounter * 5) % width
            paint.color = Color.HSVToColor(floatArrayOf((offset.toFloat() / width) * 360f, 1f, 1f))
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            drawOverlays(canvas, width, height)
        }
    }

    private fun drawOverlays(canvas: Canvas, width: Int, height: Int) {
        paint.color = Color.RED
        paint.textSize = 60f
        paint.isAntiAlias = true
        
        // Render Frame Counter
        canvas.drawText("Frame: $frameCounter", 50f, 100f, paint)
        
        // Render Timestamp Overlay (critical for measuring pipeline latency)
        val timestampMs = System.currentTimeMillis()
        canvas.drawText("Timestamp: $timestampMs", 50f, 180f, paint)
    }

    private fun drawOnSurface(width: Int, height: Int, drawBlock: (Canvas) -> Unit) {
        val canvas = surface.lockCanvas(null) ?: return
        try {
            drawBlock(canvas)
            frameCounter++
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
