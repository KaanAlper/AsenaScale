package dev.mobileclaude

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
import dev.mobileclaude.ui.HomeScreen
import dev.mobileclaude.ui.HostEditScreen
import dev.mobileclaude.ui.MobileClaudeTheme
import dev.mobileclaude.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        val app = App.instance
        setContent {
            MobileClaudeTheme {
                // "home" | "edit:<id or new>" | "edit:new:<address>" | "term:<id>"
                var route by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = route != "home") { route = "home" }

                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "route",
                ) { r ->
                    when {
                        r.startsWith("term:") -> TerminalScreen(
                            hostId = r.removePrefix("term:"),
                            onBack = { route = "home" },
                        )
                        r.startsWith("edit:") -> {
                            val arg = r.removePrefix("edit:")
                            HostEditScreen(
                                host = app.hosts.get(arg),
                                suggestedAddress = arg.removePrefix("new:").takeIf { arg.startsWith("new:") },
                                onDone = { route = "home" },
                            )
                        }
                        else -> HomeScreen(
                            onOpenHost = { route = "term:${it.id}" },
                            onEditHost = { route = "edit:${it.id}" },
                            onNewHost = { address -> route = "edit:new:${address.orEmpty()}" },
                        )
                    }
                }
            }
        }
    }
}
