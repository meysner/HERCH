package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.example.network.PREF_COOKIES
import com.example.ui.screens.AppMenuScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HermesLoginScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.StatsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

private const val PREFS_NAME = "herch_hermes"
private const val PREF_WEBUI_URL = "webui_url"

// Экраны приложения
sealed class Screen {
    object Login : Screen()
    object Menu : Screen()
    object Chat : Screen()
    object Memory : Screen()
    object Stats : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Единственное место создания OkHttpClient — далее передаётся инжекцией
        val httpClient = com.example.di.AppModule.provideOkHttpClient(prefs)

        setContent {
            MyApplicationTheme {
                val viewModel: SessionViewModel by viewModels {
                    SessionViewModel.Factory(prefs, httpClient)
                }
                HerchApp(viewModel = viewModel, prefs = prefs, httpClient = httpClient)
            }
        }
    }
}

@Composable
private fun HerchApp(
    viewModel: SessionViewModel,
    prefs: android.content.SharedPreferences,
    httpClient: okhttp3.OkHttpClient,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authClient = remember { com.example.network.HermesAuthClient(httpClient) }

    // Начальный экран определяем один раз
    var currentScreen by remember {
        val savedUrl = prefs.getString("webui_url", "").orEmpty()
        mutableStateOf<Screen>(if (savedUrl.isNotBlank()) Screen.Menu else Screen.Login)
    }

    // ChatScreen живёт постоянно (не пересоздаётся при переключении сессий)
    // Мы показываем/скрываем его через AnimatedVisibility
    val showChat = currentScreen == Screen.Chat

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            val isForward = targetState != Screen.Menu && targetState != Screen.Login
            if (isForward) {
                (slideInHorizontally(tween(350, easing = LinearOutSlowInEasing)) { it / 4 } + fadeIn(tween(350)))
                    .togetherWith(slideOutHorizontally(tween(300, easing = FastOutLinearInEasing)) { -it / 4 } + fadeOut(tween(200)))
            } else {
                (slideInHorizontally(tween(350, easing = LinearOutSlowInEasing)) { -it / 4 } + fadeIn(tween(350)))
                    .togetherWith(slideOutHorizontally(tween(300, easing = FastOutLinearInEasing)) { it / 4 } + fadeOut(tween(200)))
            }
        },
        label = "screen_transition",
    ) { screen ->
        when (screen) {
            is Screen.Login -> {
                HermesLoginScreen(
                    httpClient = httpClient,
                    onLoginSuccess = { currentScreen = Screen.Menu }
                )
            }
            is Screen.Menu -> {
                AppMenuScreen(
                    viewModel = viewModel,
                    onNavigateToChat = { session ->
                        viewModel.openSession(session)
                        currentScreen = Screen.Chat
                    },
                    onNavigateToMemory = { currentScreen = Screen.Memory },
                    onNavigateToStats = { currentScreen = Screen.Stats },
                    onLogout = {
                        scope.launch {
                            val savedUrl = prefs.getString("webui_url", "").orEmpty()
                            if (savedUrl.isNotBlank()) authClient.logout(savedUrl)
                            prefs.edit()
                                .remove("webui_url")
                                .remove(PREF_COOKIES)
                                .apply()
                            currentScreen = Screen.Login
                        }
                    }
                )
            }
            is Screen.Chat -> {
                // ChatScreen — постоянный, получает данные из ViewModel
                ChatScreen(
                    viewModel = viewModel,
                    onOpenDrawer = { currentScreen = Screen.Menu }
                )
            }
            is Screen.Memory -> {
                MemoryScreen(
                    apiClient = remember { com.example.network.HermesApiClient(prefs, httpClient) },
                    onBack = { currentScreen = Screen.Menu }
                )
            }
            is Screen.Stats -> {
                StatsScreen(
                    apiClient = remember { com.example.network.HermesApiClient(prefs, httpClient) },
                    onBack = { currentScreen = Screen.Menu }
                )
            }
        }
    }
}