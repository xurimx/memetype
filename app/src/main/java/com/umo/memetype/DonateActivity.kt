package com.umo.memetype

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Small dialog-themed screen behind the Donate button: a line of thanks and a button that
 * opens the donation page in the browser. The URL lives in the donate_url string resource.
 */
class DonateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.donate_title)
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.donate_body)
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this).apply {
            text = getString(R.string.close)
            setOnClickListener { finish() }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.donate_open)
            setOnClickListener { openDonateLink() }
        })
        root.addView(buttons)
        setContentView(root)
    }

    private fun openDonateLink() {
        val url = getString(R.string.donate_url)
        if (url.isBlank()) {
            Toast.makeText(this, R.string.link_not_set, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
