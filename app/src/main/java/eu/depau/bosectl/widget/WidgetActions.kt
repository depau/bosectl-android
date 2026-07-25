package eu.depau.bosectl.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.data.DeviceRepository

private const val TAG = "WidgetActions"

/** Widget taps work even with the app process cold: connect on demand, act, refresh cache. */
class SetModeAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("mode_idx")
        fun params(idx: Int) = actionParametersOf(KEY to idx)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val idx = parameters[KEY] ?: return
        DeviceRepository.init(context)
        runCatching { DeviceRepository.setMode(idx) }
            .onFailure { Log.w(TAG, "setMode from widget failed", it) }
    }
}

class SetSpatialAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("spatial")
        fun params(value: Int) = actionParametersOf(KEY to value)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val value = parameters[KEY] ?: return
        DeviceRepository.init(context)
        runCatching { DeviceRepository.setSpatial(Spatial.fromValue(value)) }
            .onFailure { Log.w(TAG, "setSpatial from widget failed", it) }
    }
}
