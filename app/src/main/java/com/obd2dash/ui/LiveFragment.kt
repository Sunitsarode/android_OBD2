package com.obd2dash.ui

import androidx.fragment.app.Fragment

/**
 * Base for the tab screens. Hidden tabs stay started, so without this check
 * every tab would redraw on every poll cycle, which a slow head unit feels.
 */
abstract class LiveFragment : Fragment() {

    protected val isShowing: Boolean get() = view != null && !isHidden

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) onShown()
    }

    /** Called when the tab becomes visible again; redraw from the latest state. */
    protected open fun onShown() {}
}
