package eu.depau.bosectl.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.data.DeviceRepository

private const val TAG = "WidgetActions"

/** Widget taps work even with the app process cold: connect on demand, act, refresh cache. */
private inline fun run(context: Context, what: String, crossinline block: suspend () -> Unit) {
    DeviceRepository.init(context)
    DeviceRepository.runAsync {
        runCatching { block() }.onFailure { Log.w(TAG, "$what from widget failed", it) }
    }
}

class SetModeAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("mode_idx")
        fun params(idx: Int) = actionParametersOf(KEY to idx)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val idx = parameters[KEY] ?: return
        run(context, "setMode") { DeviceRepository.setMode(idx) }
    }
}

class SetSpatialAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("spatial")
        fun params(value: Int) = actionParametersOf(KEY to value)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val value = parameters[KEY] ?: return
        run(context, "setSpatial") { DeviceRepository.setSpatial(Spatial.fromValue(value)) }
    }
}

class ToggleAncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val enabled = parameters[ToggleableStateKey] ?: return
        run(context, "setAnc") { DeviceRepository.setAnc(enabled) }
    }
}

class ToggleTouchControlsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val enabled = parameters[ToggleableStateKey] ?: return
        run(context, "setTouchControls") { DeviceRepository.setTouchControls(enabled) }
    }
}

/** Glance has no slider (RemoteViews has no SeekBar), so the level steps. */
class AdjustCncAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("delta")
        fun params(delta: Int) = actionParametersOf(KEY to delta)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[KEY] ?: return
        run(context, "adjustCnc") { DeviceRepository.adjustCnc(delta) }
    }
}
