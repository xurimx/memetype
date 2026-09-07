package com.umo.memetype.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView whose height always equals its width (grid thumbnails). */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}
