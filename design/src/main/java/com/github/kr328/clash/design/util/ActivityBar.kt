package com.github.kr328.clash.design.util

import android.app.Activity
import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedDispatcherOwner
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.view.ActivityBarLayout

fun ActivityBarLayout.applyFrom(context: Context) {
    if (context is Activity) {
        val dispatcher = (context as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher

        findViewById<ImageView>(R.id.activity_bar_close_view)?.apply {
            setOnClickListener {
                dispatcher?.onBackPressed() ?: @Suppress("DEPRECATION") context.onBackPressed()
            }
        }
        findViewById<TextView>(R.id.activity_bar_title_view)?.apply {
            text = context.title
        }
    }
}