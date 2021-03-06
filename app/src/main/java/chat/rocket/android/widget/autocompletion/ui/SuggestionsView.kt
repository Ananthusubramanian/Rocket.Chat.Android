package chat.rocket.android.widget.autocompletion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.support.annotation.DrawableRes
import android.support.transition.Slide
import android.support.transition.TransitionManager
import android.support.v4.content.ContextCompat
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import chat.rocket.android.R
import chat.rocket.android.widget.autocompletion.model.SuggestionModel
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * This is a special index that means we're not at an autocompleting state.
 */
private const val NO_STATE_INDEX = 0

class SuggestionsView : FrameLayout, TextWatcher {
    private val recyclerView: RecyclerView
    // Maps tokens to their respective adapters.
    private val adaptersByToken = hashMapOf<String, SuggestionsAdapter<out BaseSuggestionViewHolder>>()
    private val externalProvidersByToken = hashMapOf<String, ((query: String) -> Unit)>()
    private val localProvidersByToken = hashMapOf<String, HashMap<String, List<SuggestionModel>>>()
    private var editor: WeakReference<EditText>? = null
    private var completionStartIndex = AtomicInteger(NO_STATE_INDEX)

    companion object {
        private val SLIDE_TRANSITION = Slide(Gravity.BOTTOM).setDuration(200)
    }

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr, 0) {
        recyclerView = RecyclerView(context)
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL,
                false)
        recyclerView.itemAnimator = DefaultItemAnimator()
        recyclerView.addItemDecoration(TopItemDecoration(context, R.drawable.suggestions_menu_decorator))
        recyclerView.layoutManager = layoutManager
        recyclerView.visibility = View.GONE
        addView(recyclerView)
    }

    override fun afterTextChanged(s: Editable) {
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        // If we have a deletion.
        if (after == 0) {
            val deleted = s.subSequence(start, start + count).toString()
            if (adaptersByToken.containsKey(deleted) && completionStartIndex.get() > NO_STATE_INDEX) {
                // We have removed the '@', '#' or any other action token so halt completion.
                cancelSuggestions(true)
            }
        }
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        // If we don't have any adapter bound to any token bail out.
        if (adaptersByToken.isEmpty()) return

        val new = s.subSequence(start, start + count).toString()
        if (adaptersByToken.containsKey(new)) {
            swapAdapter(getAdapterForToken(new)!!)
            completionStartIndex.compareAndSet(NO_STATE_INDEX, start + 1)
            editor?.let {
                // Disable keyboard suggestions when autocompleting.
                val editText = it.get()
                if (editText != null) {
                    editText.inputType = editText.inputType or InputType.TYPE_TEXT_VARIATION_FILTER
                    expand()
                }
            }
        }

        if (new.startsWith(" ")) {
            // just halts the completion execution
            cancelSuggestions(false)
            return
        }

        val prefixEndIndex = editor?.get()?.selectionStart ?: NO_STATE_INDEX
        if (prefixEndIndex == NO_STATE_INDEX || prefixEndIndex < completionStartIndex.get()) return
        val prefix = s.subSequence(completionStartIndex.get(), editor?.get()?.selectionStart ?: completionStartIndex.get()).toString()
        recyclerView.adapter?.let {
            it as SuggestionsAdapter
            // we need to look up only after the '@'
            it.autocomplete(prefix)
            val cacheMap = localProvidersByToken[it.token]
            if (cacheMap != null && cacheMap[prefix] != null) {
                it.addItems(cacheMap[prefix]!!)
            } else {
                // fetch more suggestions from an external source if any
                externalProvidersByToken[it.token]?.invoke(prefix)
            }
        }
    }

    private fun swapAdapter(adapter: SuggestionsAdapter<*>): SuggestionsView {
        recyclerView.adapter = adapter
        // Don't override if user set an item click listener already/
        if (!adapter.hasItemClickListener()) {
            setOnItemClickListener(adapter) {
                // set default item click behavior
            }
        }
        return this
    }

    fun getAdapterForToken(token: String): SuggestionsAdapter<*>? = adaptersByToken.get(token)

    fun anchor(editText: EditText): SuggestionsView {
        editText.removeTextChangedListener(this)
        editText.addTextChangedListener(this)
        editor = WeakReference(editText)
        return this
    }

    fun bindTokenAdapter(adapter: SuggestionsAdapter<*>): SuggestionsView {
        adaptersByToken.getOrPut(adapter.token, { adapter })
        return this
    }

    fun addItems(token: String, list: List<SuggestionModel>): SuggestionsView {
        if (list.isNotEmpty()) {
            val adapter = adapter(token)
            localProvidersByToken.getOrPut(token, { hashMapOf() })
                    .put(adapter.prefix(), list)
            if (completionStartIndex.get() > NO_STATE_INDEX && adapter.itemCount == 0) expand()
            adapter.addItems(list)
        }
        return this
    }

    fun setOnItemClickListener(tokenAdapter: SuggestionsAdapter<*>,
                               clickListener: (item: SuggestionModel) -> Unit): SuggestionsView {
        tokenAdapter.setOnClickListener(object : SuggestionsAdapter.ItemClickListener {
            override fun onClick(item: SuggestionModel) {
                insertSuggestionOnEditor(item)
                clickListener.invoke(item)
                cancelSuggestions(true)
                collapse()
            }
        })
        return this
    }

    fun addSuggestionProviderAction(token: String, provider: (query: String) -> Unit): SuggestionsView {
        externalProvidersByToken.getOrPut(token, { provider })
        return this
    }

    private fun adapter(token: String): SuggestionsAdapter<*> {
        return adaptersByToken[token] ?: throw IllegalStateException("no adapter binds to token \"$token\"")
    }

    private fun cancelSuggestions(haltCompletion: Boolean) {
        // Reset completion start index only if we've deleted the token that triggered completion or
        // we finished the completion process.
        if (haltCompletion) {
            completionStartIndex.set(NO_STATE_INDEX)
        }
        collapse()
        // Re-enable keyboard suggestions.
        val editText = editor?.get()
        if (editText != null) {
            editText.inputType = editText.inputType and InputType.TYPE_TEXT_VARIATION_FILTER.inv()
        }
    }

    private fun insertSuggestionOnEditor(item: SuggestionModel) {
        editor?.get()?.let {
            val suggestionText = item.text
            it.text.replace(completionStartIndex.get(), it.selectionStart, "$suggestionText ")
        }
    }

    private fun collapse() {
        TransitionManager.beginDelayedTransition(this, SLIDE_TRANSITION)
        recyclerView.visibility = View.GONE
    }

    private fun expand() {
        TransitionManager.beginDelayedTransition(this, SLIDE_TRANSITION)
        recyclerView.visibility = View.VISIBLE
    }

    private class TopItemDecoration() : RecyclerView.ItemDecoration() {
        private lateinit var divider: Drawable
        private val padding = Rect()

        // Custom divider will be used.
        constructor(context: Context, @DrawableRes drawableResId: Int) : this() {
            val customDrawable = ContextCompat.getDrawable(context, drawableResId)
            if (customDrawable != null) {
                divider = customDrawable
            }
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val left = parent.paddingLeft
            val right = (parent.width - parent.paddingRight)

            val parentParams = parent.layoutParams as FrameLayout.LayoutParams
            val top = parent.top - parentParams.topMargin - parent.paddingTop
            val bottom = top + divider.intrinsicHeight

            divider.setBounds(left, top, right, bottom)
            divider.draw(c)
        }
    }
}