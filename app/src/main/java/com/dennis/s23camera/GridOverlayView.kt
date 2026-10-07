package com.dennis.s23camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70FFFFFF
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val thirdW = width / 3f
        val thirdH = height / 3f

        canvas.drawLine(thirdW, 0f, thirdW, height.toFloat(), paint)
        canvas.drawLine(thirdW * 2f, 0f, thirdW * 2f, height.toFloat(), paint)

        canvas.drawLine(0f, thirdH, width.toFloat(), thirdH, paint)
        canvas.drawLine(0f, thirdH * 2f, width.toFloat(), thirdH * 2f, paint)
    }
}
