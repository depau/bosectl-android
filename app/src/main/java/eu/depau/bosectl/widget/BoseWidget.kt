package eu.depau.bosectl.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import eu.depau.bosectl.data.Prefs
import eu.depau.bosectl.data.dataStore
import eu.depau.bosectl.data.decodeCachedModes
import kotlinx.coroutines.flow.first
import eu.depau.bosectl.ui.MainActivity

class BoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BoseWidget()
}

class BoseWidget : GlanceAppWidget() {

    // Exact sizing so the layout can drop rows instead of stretching them.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = runCatching { context.dataStore.data.first() }.getOrNull()
        provideContent {
            val prefs by context.dataStore.data.collectAsState(initial)
            GlanceTheme {
                WidgetContent(prefs)
            }
        }
    }

    @Composable
    private fun WidgetContent(prefs: Preferences?) {
        val connected = prefs?.get(Prefs.CACHE_CONNECTED) ?: false
        val deviceName = prefs?.get(Prefs.CACHE_DEVICE_NAME) ?: "Bose Control"
        val battery = prefs?.get(Prefs.CACHE_BATTERY)
        val currentMode = prefs?.get(Prefs.CACHE_CURRENT_MODE)
        val spatial = prefs?.get(Prefs.CACHE_SPATIAL) ?: 0
        val starred = decodeCachedModes(prefs?.get(Prefs.CACHE_STARRED))

        // Drop rows as the widget shrinks rather than stretching the content.
        val height = LocalSize.current.height
        val showHeader = height >= 92.dp
        val showSpatial = height >= 132.dp

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showHeader) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(12.dp)
                        .clickable(actionStartActivity<MainActivity>())
                        // Generous vertical padding: this row is the "open app" target.
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        deviceName,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        if (connected) battery ?: "" else "Disconnected",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(8.dp))
            }

            if (starred.isEmpty()) {
                Text(
                    "Open the app to load your modes",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    starred.take(4).forEachIndexed { i, mode ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        WidgetChip(
                            text = mode.name,
                            selected = connected && mode.idx == currentMode,
                            action = actionRunCallback<SetModeAction>(
                                SetModeAction.params(mode.idx)
                            ),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
            }
            if (!showSpatial) return@Column
            Spacer(GlanceModifier.height(8.dp))

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                listOf(0 to "Off", 1 to "Still", 2 to "Motion").forEachIndexed { i, (value, label) ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    WidgetChip(
                        text = label,
                        selected = connected && spatial == value,
                        action = actionRunCallback<SetSpatialAction>(
                            SetSpatialAction.params(value)
                        ),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
        }
    }

    @Composable
    private fun WidgetChip(
        text: String,
        selected: Boolean,
        action: androidx.glance.action.Action,
        modifier: GlanceModifier,
    ) {
        Column(
            modifier = modifier
                .background(
                    if (selected) GlanceTheme.colors.primary
                    else GlanceTheme.colors.secondaryContainer
                )
                .cornerRadius(16.dp)
                .clickable(action)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text,
                style = TextStyle(
                    color = if (selected) GlanceTheme.colors.onPrimary
                    else GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
            )
        }
    }
}
