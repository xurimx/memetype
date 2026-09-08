package com.umo.memetype.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umo.memetype.R
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.TemplateRepository
import com.umo.memetype.meme.TemplateRepository.Category
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Screen 1: searchable grid of templates + category chips.
 * The search field types via the shared MiniKeyboardView (an IME can't host another IME).
 */
class TemplateGridView(
    context: Context,
    private val repository: TemplateRepository,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        fun onTemplatePicked(template: MemeTemplate)
        fun onSwitchKeyboard()
        fun onOpenSettings()
        fun onOpenSources()
        fun onOpenGithub()
        fun onOpenDonate()
    }

    private val search: TextView
    private val actions: View
    private val grid: RecyclerView
    private val empty: TextView
    private val chips: LinearLayout
    private val chipsScroll: View
    private val searchKeyboard: MiniKeyboardView

    private val adapter = TemplateAdapter()
    private var query = ""
    private var category = Category.ALL
    private var refreshSerial = 0
    private val chipViews = mutableMapOf<Category, TextView>()

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_template_grid, this, true)
        search = findViewById(R.id.search)
        actions = findViewById(R.id.actions)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        chips = findViewById(R.id.chips)
        chipsScroll = findViewById(R.id.chips_scroll)
        searchKeyboard = findViewById(R.id.search_keyboard)

        grid.layoutManager = GridLayoutManager(context, SPAN_COUNT)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        buildChips()

        search.setOnClickListener { setSearchActive(true) }
        findViewById<View>(R.id.btn_switch).setOnClickListener { callbacks.onSwitchKeyboard() }
        findViewById<View>(R.id.btn_settings).setOnClickListener { callbacks.onOpenSettings() }
        findViewById<View>(R.id.btn_sources).setOnClickListener { callbacks.onOpenSources() }
        findViewById<View>(R.id.btn_github).setOnClickListener { callbacks.onOpenGithub() }
        findViewById<View>(R.id.btn_donate).setOnClickListener { callbacks.onOpenDonate() }
        searchKeyboard.listener = object : MiniKeyboardView.Listener {
            override fun onText(text: String) = setQuery(query + text)
            override fun onBackspace() { if (query.isNotEmpty()) setQuery(query.dropLast(1)) }
            override fun onEnter() = setSearchActive(false)
        }
        // Remote sources report in later; show them as soon as they arrive.
        repository.onChanged = { main.post { refresh() } }

        refresh()
    }

    /** Re-query off the main thread (sources may need loading), then show the result. */
    fun refresh() {
        val serial = ++refreshSerial
        val q = query
        val cat = category
        if (io.isShutdown) return
        io.execute {
            val items = try { repository.search(q, cat) } catch (e: Exception) { emptyList() }
            main.post {
                if (serial != refreshSerial) return@post
                adapter.submit(items)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Leave search mode (keyboard away, chips and action buttons back). Called when the panel is (re)shown or hidden. */
    fun closeSearch() {
        if (searchKeyboard.visibility == View.VISIBLE) setSearchActive(false)
    }

    private val debouncedRefresh = Runnable { refresh() }

    private fun setQuery(q: String) {
        query = q
        search.text = q
        // A non-blank query may hit provider-side search: wait for the typing to pause.
        main.removeCallbacks(debouncedRefresh)
        if (q.isBlank()) refresh() else main.postDelayed(debouncedRefresh, SEARCH_DEBOUNCE_MS)
    }

    private fun setSearchActive(active: Boolean) {
        searchKeyboard.visibility = if (active) View.VISIBLE else View.GONE
        chipsScroll.visibility = if (active) View.GONE else View.VISIBLE
        // Give the query the whole row while typing.
        actions.visibility = if (active) View.GONE else View.VISIBLE
        search.isSelected = active
        if (!active && query.isEmpty()) search.text = ""
    }

    private fun buildChips() {
        val labels = linkedMapOf(
            Category.ALL to R.string.chip_all,
            Category.RECENT to R.string.chip_recent,
            Category.MINE to R.string.chip_mine,
            Category.REACTION to R.string.chip_reaction,
            Category.ANIMALS to R.string.chip_animals
        )
        val d = resources.displayMetrics.density
        labels.forEach { (cat, label) ->
            val chip = TextView(context).apply {
                text = context.getString(label)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 12f
                setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                isSelected = cat == category
                setOnClickListener { selectCategory(cat) }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * d).toInt()
            chips.addView(chip, lp)
            chipViews[cat] = chip
        }
    }

    private fun selectCategory(cat: Category) {
        category = cat
        chipViews.forEach { (c, v) -> v.isSelected = c == cat }
        refresh()
        grid.scrollToPosition(0)
    }

    fun destroy() {
        main.removeCallbacks(debouncedRefresh)
        repository.onChanged = null
        io.shutdownNow()
    }

    // ---- adapter ----------------------------------------------------------------

    private inner class TemplateAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<MemeTemplate> = emptyList()

        fun submit(list: List<MemeTemplate>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_template, parent, false)
            return Holder(v as ImageView)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val t = items[position]
            holder.bind(t)
        }
    }

    private inner class Holder(private val image: ImageView) : RecyclerView.ViewHolder(image) {
        private var bound: MemeTemplate? = null

        init {
            image.setOnClickListener { bound?.let { callbacks.onTemplatePicked(it) } }
        }

        fun bind(t: MemeTemplate) {
            bound = t
            image.setImageDrawable(null)
            image.contentDescription = t.name
            val target = if (width > 0) width / SPAN_COUNT else THUMB_FALLBACK_PX
            if (io.isShutdown) return
            io.execute {
                val bmp: Bitmap? = repository.loadThumbnail(t, target)
                main.post { if (bound === t && bmp != null) image.setImageBitmap(bmp) }
            }
        }
    }

    private companion object {
        const val SPAN_COUNT = 4
        const val THUMB_FALLBACK_PX = 256
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
