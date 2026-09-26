package com.aploworks.aplomaster.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val content = TextView(this).apply {
            text = "AploMaster"
            gravity = Gravity.CENTER
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground))
            setTextColor(resolveThemeColor(android.R.attr.textColorPrimary))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setContentView(content)
    }

    private fun resolveThemeColor(attribute: Int): Int = TypedValue().let { value ->
        theme.resolveAttribute(attribute, value, true)
        value.data
    }
}

