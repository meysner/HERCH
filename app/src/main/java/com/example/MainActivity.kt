package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.di.SessionStreamRepository
import com.example.network.PREF_COOKIES
import com.example.notifications.NotificationHelper
import com.example.ui.screens.AppMenuScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HermesLoginScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.StatsScreen
import com.example.ui.screens.TasksScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.work.StreamGuardService
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
    object Tasks : Screen()
}

// Куда должен перейти UI по tap на уведомление (см. NotificationHelper).
sealed class DeepLink {
    data class OpenSession(val sessionId: String) : DeepLink()
    object OpenTasks : DeepLink()
}

private fun deepLinkFromIntent(intent: Intent?): DeepLink? {
    val sessionId = intent?.getStringExtra(NotificationHelper.EXTRA_SESSION_ID)
    if (!sessionId.isNullOrBlank()) return DeepLink.OpenSession(sessionId)
    if (intent?.getStringExtra(NotificationHelper.EXTRA_SCREEN) == NotificationHelper.SCREEN_TASKS) {
        return DeepLink.OpenTasks
    }
    return null
}

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val httpClient by lazy { com.example.di.AppModule.provideOkHttpClient(prefs) }

    // ViewModel сохраняется как поле, чтобы onStart()/onStop() могли сообщать
    // ей о переходах приложения в фон/на передний план -- см. isForeground /
    // setForeground() в SessionViewModel и цикл поллинга в startPolling(),
    // который спит в фоне вместо того чтобы бесполезно опрашивать сервер
    // каждые 3 секунды и жечь батарею.
    private val viewModel: SessionViewModel by viewModels {
        SessionViewModel.Factory(prefs, httpClient)
    }

    // Deep-link из уведомления (открыть конкретную сессию/экран задач).
    // mutableStateOf, а не просто поле — HerchApp() читает это через
    // LaunchedEffect и должен перерисоваться, когда значение меняется, в том
    // числе повторно при onNewIntent() на уже запущенной Activity
    // (android:launchMode="singleTop").
    private val pendingDeepLink = mutableStateOf<DeepLink?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* игнорируем ответ: без разрешения просто не будет уведомлений, остальной функционал не блокируем */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pendingDeepLink.value = deepLinkFromIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            MyApplicationTheme {
                HerchApp(
                    viewModel = viewModel,
                    prefs = prefs,
                    httpClient = httpClient,
                    pendingDeepLink = pendingDeepLink,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = deepLinkFromIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // onStart/onStop -- это переходы всего приложения между передним планом
    // и фоном для single-Activity-приложения (единственная Activity в
    // манифесте), поэтому ProcessLifecycleOwner тут не нужен: тот же сигнал,
    // без дополнительной gradle-зависимости.
    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        // Приложение снова на переднем плане -- живой UI (ChatScreen/3с
        // поллинг) сам отражает состояние, sticky-уведомление больше не
        // нужно. Если пользователь снова свернёт приложение во время
        // активного стрима, onStop() ниже поднимет сервис заново.
        StreamGuardService.stop(this)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        if (SessionStreamRepository.hasActiveWork) {
            StreamGuardService.start(this)
        }
        super.onStop()
    }
}

@Composable
private fun HerchApp(
    viewModel: SessionViewModel,
    prefs: android.content.SharedPreferences,
    httpClient: okhttp3.OkHttpClient,
    pendingDeepLink: MutableState<DeepLink?>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authClient = remember { com.example.network.HermesAuthClient(httpClient) }

    // Начальный экран определяем один раз
    var currentScreen by remember {
        val savedUrl = prefs.getString("webui_url", "").orEmpty()
        mutableStateOf<Screen>(if (savedUrl.isNotBlank()) Screen.Menu else Screen.Login)
    }

    // Реакция на tap по уведомлению (первый запуск через onCreate() и любой
    // повторный через onNewIntent() на уже живой Activity). Не открываем
    // deep-link поверх экрана логина -- сперва обычный вход, ссылка потеряется,
    // это ожидаемо и безопаснее, чем тащить состояние через логин-флоу.
    LaunchedEffect(pendingDeepLink.value) {
        val link = pendingDeepLink.value ?: return@LaunchedEffect
        if (currentScreen is Screen.Login) return@LaunchedEffect
        when (link) {
            is DeepLink.OpenSession -> {
                viewModel.openSessionById(link.sessionId)
                currentScreen = Screen.Chat
            }
            is DeepLink.OpenTasks -> currentScreen = Screen.Tasks
        }
        pendingDeepLink.value = null
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
                    onNavigateToTasks = { currentScreen = Screen.Tasks },
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
            is Screen.Tasks -> {
                TasksScreen(
                    apiClient = remember { com.example.network.HermesApiClient(prefs, httpClient) },
                    onBack = { currentScreen = Screen.Menu }
                )
            }
        }
    }
}