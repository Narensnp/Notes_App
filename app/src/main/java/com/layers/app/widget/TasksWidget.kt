package com.layers.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.layers.app.LayersApplication
import com.layers.app.MainActivity
import com.layers.app.data.db.TaskEntity
import com.layers.app.navigation.IntentActions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home screen widget: today's tasks with tappable checkboxes, plus quick-capture buttons. */
class TasksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as LayersApplication).container
        val initial = container.tasks.widgetTasks()
        provideContent {
            val flow = remember { container.tasks.observeWidgetTasks() }
            val tasks by flow.collectAsState(initial = initial)
            GlanceTheme {
                WidgetContent(tasks)
            }
        }
    }
}

@Composable
private fun WidgetContent(tasks: List<TaskEntity>) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(14.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Today",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = LocalDate.now(zone).format(DateTimeFormatter.ofPattern("EEE d MMM")),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (tasks.isEmpty()) {
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No tasks today",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = "Enjoy the quiet.",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(tasks, itemId = { it.blockId.hashCode().toLong() }) { task ->
                    val due = task.dueAt
                    val overdue = !task.done && due != null && due < todayStart
                    val time = if (due != null && task.hasTime) {
                        Instant.ofEpochMilli(due).atZone(zone).toLocalTime().format(timeFormat) + " · "
                    } else ""
                    val label = (if (overdue) "Overdue · " else "") + time + task.title
                    CheckBox(
                        checked = task.done,
                        onCheckedChange = actionRunCallback<ToggleTaskAction>(
                            actionParametersOf(
                                ToggleTaskAction.BlockKey to task.blockId,
                                ToggleTaskAction.DoneKey to !task.done,
                            )
                        ),
                        text = label,
                        style = TextStyle(
                            color = if (overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        maxLines = 2,
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Button(
                text = "New note",
                onClick = actionStartActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(IntentActions.NEW_NOTE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Button(
                text = "Screenshot",
                onClick = actionStartActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(IntentActions.SCREENSHOT_NOTE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val blockId = parameters[BlockKey] ?: return
        val done = parameters[DoneKey] ?: true
        val container = (context.applicationContext as LayersApplication).container
        container.tasks.setDone(blockId, done)
        TasksWidget().update(context, glanceId)
    }

    companion object {
        val BlockKey = ActionParameters.Key<String>("blockId")
        val DoneKey = ActionParameters.Key<Boolean>("done")
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}
