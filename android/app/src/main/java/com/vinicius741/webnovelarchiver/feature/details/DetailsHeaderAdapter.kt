package com.vinicius741.webnovelarchiver.feature.details

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/** A single existing View exposed as a RecyclerView item so compact Details can keep one scrolling
 * surface without nesting the chapter list inside a ScrollView. */
class DetailsHeaderAdapter(
    private val header: android.view.View,
) : RecyclerView.Adapter<DetailsHeaderAdapter.HeaderHolder>() {
    class HeaderHolder(
        val container: FrameLayout,
    ) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): HeaderHolder =
        HeaderHolder(
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )

    override fun onBindViewHolder(
        holder: HeaderHolder,
        position: Int,
    ) {
        (header.parent as? ViewGroup)?.removeView(header)
        holder.container.removeAllViews()
        holder.container.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun getItemCount(): Int = 1
}
