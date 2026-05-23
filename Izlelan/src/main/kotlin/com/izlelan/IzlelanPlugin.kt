package com.izlelan

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IzlelanPlugin: Plugin() {
    private var activity: AppCompatActivity? = null

    companion object {
        var context: Context? = null
    }

    override fun load(context: Context) {
        Companion.context = context
        activity = context as? AppCompatActivity

        // All providers should be added in this manner
        registerMainAPI(IzlelanProvider())

        openSettings = {
            val frag = BlankFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "Frag")
            }
        }
    }
}