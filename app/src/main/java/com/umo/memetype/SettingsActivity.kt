package com.umo.memetype

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.umo.memetype.share.MemeSaver
import com.umo.memetype.store.AppPrefs
import com.umo.memetype.store.HistoryStore
import com.umo.memetype.ui.Rows

/**
 * The app's main screen: keyboard onboarding (enable + select), output settings (save sent
 * memes, save folder, watermark) and About. Also referenced from method.xml as the IME's
 * settings screen. Everything that needs an Activity (folder picker, permission prompt) lives
 * here, never in the keyboard.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: AppPrefs

    private lateinit var enabledRow: Rows.TextRow
    private lateinit var selectedRow: Rows.TextRow
    private lateinit var saveRow: Rows.SwitchRow
    private lateinit var folderRow: Rows.TextRow
    private lateinit var useDefaultRow: Rows.TextRow
    private lateinit var grantRow: Rows.TextRow
    private lateinit var watermarkRow: Rows.SwitchRow
    private lateinit var historyRow: Rows.SwitchRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val d = resources.displayMetrics.density
        list.setPadding(0, (8 * d).toInt(), 0, (24 * d).toInt())

        // ---- Keyboard ----
        list.addView(Rows.header(this, getString(R.string.settings_section_keyboard)))
        enabledRow = Rows.textRow(this, getString(R.string.setup_step1), "")
        list.addView(enabledRow.root)
        list.addView(Rows.buttonRow(this, getString(R.string.btn_enable)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.root)
        selectedRow = Rows.textRow(this, getString(R.string.setup_step2), "")
        list.addView(selectedRow.root)
        list.addView(Rows.buttonRow(this, getString(R.string.btn_select)) { imm().showInputMethodPicker() }.root)
        list.addView(Rows.divider(this))

        // ---- Output ----
        list.addView(Rows.header(this, getString(R.string.settings_section_output)))
        saveRow = Rows.switchRow(this, getString(R.string.pref_save_sent), null, prefs.saveSentMemes) {
            prefs.saveSentMemes = it
        }
        list.addView(saveRow.root)
        folderRow = Rows.textRow(this, getString(R.string.pref_save_folder), "")
        list.addView(folderRow.root)
        list.addView(Rows.buttonRow(this, getString(R.string.btn_choose_folder), getString(R.string.btn_choose_folder_sub)) {
            chooseFolder()
        }.root)
        useDefaultRow = Rows.buttonRow(this, getString(R.string.btn_use_default_folder), MemeSaver.DEFAULT_FOLDER) {
            useDefaultFolder()
        }
        list.addView(useDefaultRow.root)
        grantRow = Rows.buttonRow(this, getString(R.string.btn_grant_storage), getString(R.string.grant_storage_sub)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
        list.addView(grantRow.root)
        watermarkRow = Rows.switchRow(
            this, getString(R.string.pref_watermark), getString(R.string.pref_watermark_sub), prefs.watermarkEnabled
        ) { prefs.watermarkEnabled = it }
        list.addView(watermarkRow.root)
        list.addView(Rows.divider(this))

        // ---- History ----
        list.addView(Rows.header(this, getString(R.string.settings_section_history)))
        historyRow = Rows.switchRow(
            this, getString(R.string.pref_history), getString(R.string.pref_history_sub), prefs.historyEnabled
        ) { prefs.historyEnabled = it }
        list.addView(historyRow.root)
        list.addView(Rows.buttonRow(this, getString(R.string.btn_clear_history), getString(R.string.btn_clear_history_sub)) {
            clearHistory()
        }.root)
        list.addView(Rows.divider(this))

        // ---- Sources ----
        list.addView(Rows.header(this, getString(R.string.settings_section_sources)))
        list.addView(Rows.buttonRow(this, getString(R.string.manage_sources), getString(R.string.manage_sources_sub)) {
            startActivity(Intent(this, SourcesActivity::class.java))
        }.root)
        list.addView(Rows.divider(this))

        // ---- About ----
        list.addView(Rows.header(this, getString(R.string.settings_section_about)))
        list.addView(Rows.textRow(this, getString(R.string.app_name), getString(R.string.about_version, versionName())).root)
        list.addView(Rows.buttonRow(this, getString(R.string.sidebar_github)) { openUrl(getString(R.string.github_url)) }.root)
        list.addView(Rows.buttonRow(this, getString(R.string.privacy_policy)) { openUrl(getString(R.string.privacy_url)) }.root)
        // The Play flavour has no donation link (Google Play Payments policy).
        if (BuildConfig.DONATE_ENABLED) {
            list.addView(Rows.buttonRow(this, getString(R.string.sidebar_donate)) {
                startActivity(Intent(this, DonateActivity::class.java))
            }.root)
        }

        // Edge-to-edge on targetSdk 35: keep the content out of the status and navigation bars.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            clipToPadding = false
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    private fun refresh() {
        enabledRow.subtitle.text = getString(
            if (isImeEnabled(this)) R.string.status_enabled else R.string.status_not_enabled
        )
        selectedRow.subtitle.text = getString(
            if (isImeSelected(this)) R.string.status_selected else R.string.status_not_selected
        )
        saveRow.switch.isChecked = prefs.saveSentMemes
        watermarkRow.switch.isChecked = prefs.watermarkEnabled
        historyRow.switch.isChecked = prefs.historyEnabled

        val tree = prefs.saveTreeUri?.let { Uri.parse(it) }
        folderRow.subtitle.text = when {
            tree != null && !MemeSaver.folderAvailable(this, tree) -> getString(R.string.save_folder_lost)
            tree != null -> MemeSaver.folderLabel(tree)
            MemeSaver.target(this, prefs) == MemeSaver.Target.NONE -> getString(R.string.save_folder_none)
            else -> getString(R.string.save_folder_default)
        }
        folderRow.subtitle.visibility = View.VISIBLE
        saveRow.subtitle.text = MemeSaver.label(this, prefs)?.let { getString(R.string.pref_save_sent_sub, it) } ?: ""
        saveRow.subtitle.visibility = if (saveRow.subtitle.text.isEmpty()) View.GONE else View.VISIBLE
        useDefaultRow.root.visibility = if (tree != null) View.VISIBLE else View.GONE
        val needsGrant = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !MemeSaver.hasLegacyPermission(this)
        grantRow.root.visibility = if (needsGrant) View.VISIBLE else View.GONE
    }

    // ---- save folder -------------------------------------------------------------

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(intent, REQ_FOLDER)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        releaseFolderGrant()
        prefs.saveTreeUri = uri.toString()
        refresh()
    }

    private fun useDefaultFolder() {
        releaseFolderGrant()
        prefs.saveTreeUri = null
        refresh()
    }

    private fun releaseFolderGrant() {
        val previous = prefs.saveTreeUri?.let { Uri.parse(it) } ?: return
        try {
            contentResolver.releasePersistableUriPermission(
                previous, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Already gone; nothing to release.
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) refresh()
    }

    private fun clearHistory() {
        Thread {
            HistoryStore(this).clearAll()
            runOnUiThread {
                if (!isFinishing) Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---- misc --------------------------------------------------------------------

    private fun openUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.link_not_set, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    companion object {
        private const val REQ_FOLDER = 1
        private const val REQ_STORAGE = 2

        fun isImeEnabled(ctx: Context): Boolean {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
        }

        fun isImeSelected(ctx: Context): Boolean {
            val current = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return false
            return current.startsWith(ctx.packageName + "/")
        }
    }
}
