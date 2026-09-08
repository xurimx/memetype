package com.umo.memetype

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.umo.memetype.source.AuthScheme
import com.umo.memetype.source.Credentials
import com.umo.memetype.source.MemeSource
import com.umo.memetype.source.SourceRegistry
import com.umo.memetype.store.AppPrefs
import com.umo.memetype.ui.Rows
import java.util.concurrent.Executors

/**
 * Credentials for one source: username + password or an API key, depending on the source's
 * [AuthScheme]. Save stores them in the private `source_auth` preferences, Test asks the
 * provider (the error text it returns is shown verbatim), Clear forgets them.
 * The EditTexts work with Memetype selected as the keyboard: the IME switches to its plain
 * text mode for fields of its own app.
 */
class SourceLoginActivity : Activity() {

    private lateinit var registry: SourceRegistry
    private lateinit var source: MemeSource
    private val io = Executors.newSingleThreadExecutor()

    private var usernameField: EditText? = null
    private var passwordField: EditText? = null
    private var apiKeyField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = SourceRegistry(this, AppPrefs(this))
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        val found = sourceId?.let { registry.byId(it) }
        if (found == null || found.auth == AuthScheme.None) { finish(); return }
        source = found
        val scheme = source.auth
        val current = registry.credentials.get(source.id)

        val d = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (24 * d).toInt())
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.auth_title, source.displayName)
            textSize = 22f
        })
        val explanation = when (scheme) {
            is AuthScheme.UsernamePassword -> scheme.unlocks + if (scheme.optional) "\n" + getString(R.string.auth_optional_hint) else ""
            is AuthScheme.ApiKey -> getString(R.string.auth_api_key_hint, scheme.label)
            AuthScheme.None -> ""
        }
        list.addView(TextView(this).apply {
            text = explanation
            textSize = 14f
            setPadding(0, (8 * d).toInt(), 0, (16 * d).toInt())
        })

        when (scheme) {
            is AuthScheme.UsernamePassword -> {
                usernameField = field(getString(R.string.auth_username), current.username, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                passwordField = field(getString(R.string.auth_password), current.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                list.addView(usernameField)
                list.addView(passwordField)
            }
            is AuthScheme.ApiKey -> {
                apiKeyField = field(scheme.label, current.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                list.addView(apiKeyField)
            }
            AuthScheme.None -> Unit
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        buttons.addView(Button(this).apply { text = getString(R.string.auth_test); setOnClickListener { test() } })
        buttons.addView(Button(this).apply { text = getString(R.string.auth_clear); setOnClickListener { clear() } })
        buttons.addView(Button(this).apply { text = getString(R.string.auth_save); setOnClickListener { save() } })
        list.addView(buttons)

        val helpUrl = when (scheme) {
            is AuthScheme.UsernamePassword -> scheme.helpUrl
            is AuthScheme.ApiKey -> scheme.helpUrl
            AuthScheme.None -> ""
        }
        if (helpUrl.isNotBlank()) {
            list.addView(Rows.buttonRow(this, getString(R.string.auth_help), helpUrl) { openUrl(helpUrl) }.root)
        }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            clipToPadding = false
            addView(list)
        })
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun field(hint: String, value: String?, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        // Order matters: setSingleLine() after setInputType() would replace the password
        // transformation and show the password in clear text.
        isSingleLine = true
        inputType = type
        if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        setText(value ?: "")
        maxLines = 1
    }

    private fun entered(): Credentials = Credentials(
        username = usernameField?.text?.toString()?.trim()?.ifBlank { null },
        password = passwordField?.text?.toString()?.ifBlank { null },
        apiKey = apiKeyField?.text?.toString()?.trim()?.ifBlank { null }
    )

    private fun save() {
        val creds = entered()
        if (creds.isEmpty) {
            registry.credentials.clear(source.id)
        } else {
            registry.credentials.set(source.id, creds)
        }
        Toast.makeText(this, R.string.auth_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun clear() {
        registry.credentials.clear(source.id)
        usernameField?.setText("")
        passwordField?.setText("")
        apiKeyField?.setText("")
        Toast.makeText(this, R.string.auth_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun test() {
        val creds = entered()
        if (io.isShutdown) return
        io.execute {
            val error = try { source.testCredentials(creds) } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, error ?: getString(R.string.auth_ok), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_SOURCE_ID = "source_id"

        fun intent(context: Context, sourceId: String): Intent =
            Intent(context, SourceLoginActivity::class.java).putExtra(EXTRA_SOURCE_ID, sourceId)
    }
}
