package dev.asenascale

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.asenascale.ui.HomeScreen
import dev.asenascale.ui.HostEditScreen
import dev.asenascale.ui.AsenaScaleTheme
import dev.asenascale.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Status/navigation bar icons follow the phone's light/dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        val app = App.instance
        setContent {
            AsenaScaleTheme {
                // "home" | "edit:<id or new>" | "edit:new:<address>" | "term:<hostId>/<toolId>"
                var route by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = route != "home") { route = "home" }

                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "route",
                ) { r ->
                    when {
                        r.startsWith("term:") -> {
                            val (hostId, toolId) = r.removePrefix("term:").split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                            TerminalScreen(
                                hostId = hostId,
                                toolId = toolId,
                                onSwitch = { route = "term:$hostId/$it" },
                                onBack = { route = "home" },
                            )
                        }
                        r.startsWith("edit:") -> {
                            val arg = r.removePrefix("edit:")
                            HostEditScreen(
                                host = app.hosts.get(arg),
                                suggestedAddress = arg.removePrefix("new:").takeIf { arg.startsWith("new:") },
                                onDone = { route = "home" },
                            )
                        }
                        else -> HomeScreen(
                            onOpenTerminal = { host, tool -> route = "term:${host.id}/${tool.id}" },
                            onEditHost = { route = "edit:${it.id}" },
                            onNewHost = { address -> route = "edit:new:${address.orEmpty()}" },
                        )
                    }
                }
            }
        }
    }
}
