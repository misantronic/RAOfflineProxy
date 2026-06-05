package com.raofflineproxy.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.view.isGone

class WrappingLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val maxLineWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        var lineWidth = 0
        var lineHeight = 0
        var contentWidth = 0
        var contentHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin

            if (lineWidth > 0 && lineWidth + childWidth > maxLineWidth) {
                contentWidth = maxOf(contentWidth, lineWidth)
                contentHeight += lineHeight
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }

        contentWidth = maxOf(contentWidth, lineWidth)
        contentHeight += lineHeight

        val measuredWidth = resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val measuredHeight = resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue

            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val totalChildWidth = childWidth + params.leftMargin + params.rightMargin
            val totalChildHeight = childHeight + params.topMargin + params.bottomMargin

            if (x > paddingLeft && x + totalChildWidth - paddingLeft > availableWidth) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }

            val childLeft = x + params.leftMargin
            val childTop = y + params.topMargin
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)

            x += totalChildWidth
            lineHeight = maxOf(lineHeight, totalChildHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean =
        params is MarginLayoutParams
}
