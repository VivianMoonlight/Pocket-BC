package dev.vivian.mobilebc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import dev.vivian.mobilebc.service.BcAppearanceStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vivian.mobilebc.core.ui.MobileBcTheme
import dev.vivian.mobilebc.core.model.AppLanguage
import dev.vivian.mobilebc.i18n.AppLanguageManager
import dev.vivian.mobilebc.service.BcSessionForegroundService
import dev.vivian.mobilebc.service.BcSessionServiceBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var serviceConnection: ServiceConnection? = null
    private val messageNotificationOpenToken = MutableStateFlow(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recordMessageNotificationOpen(intent)
        setContent {
            val appearanceStore = remember { BcAppearanceStore(this@MainActivity) }
            var appearance by remember { mutableStateOf(appearanceStore.read()) }
            val dark = appearance.mode.isDark(isSystemInDarkTheme())
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            MobileBcTheme(appearance = appearance) {
                var sessionBinding by remember { mutableStateOf<BcSessionServiceBinding?>(null) }
                val mobileBcApplication = application as MobileBcApplication
                val appViewModel: MobileBcAppViewModel = viewModel(
                    factory = MobileBcAppViewModel.Factory(
                        appComposition = mobileBcApplication.appComposition,
                    ),
                )
                val notificationOpenToken by messageNotificationOpenToken.collectAsStateWithLifecycle()

                LaunchedEffect(notificationOpenToken) {
                    if (notificationOpenToken > 0) {
                        appViewModel.openNotificationHome()
                    }
                }

                LaunchedEffect(Unit) {
                    BcSessionForegroundService.start(this@MainActivity)
                }

                DisposableEffect(Unit) {
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (serviceConnection !== this) return
                            val binder = service as? BcSessionForegroundService.LocalBinder ?: return
                            val binding = binder.binding
                            sessionBinding = binding
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (serviceConnection !== this) return
                            appViewModel.detachSession()
                            sessionBinding = null
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            if (serviceConnection !== this) return
                            appViewModel.detachSession()
                            sessionBinding = null
                            unbindService(this)
                            bindService(
                                Intent(this@MainActivity, BcSessionForegroundService::class.java),
                                this,
                                Context.BIND_AUTO_CREATE,
                            )
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            if (serviceConnection !== this) return
                            appViewModel.detachSession()
                            sessionBinding = null
                        }
                    }
                    serviceConnection = connection
                    bindService(
                        Intent(this@MainActivity, BcSessionForegroundService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                    onDispose {
                        runCatching { unbindService(connection) }
                        if (serviceConnection == connection) {
                            serviceConnection = null
                        }
                        sessionBinding = null
                    }
                }

                DisposableEffect(sessionBinding) {
                    val binding = sessionBinding
                    if (binding == null) {
                        appViewModel.awaitSessionBinding()
                        onDispose { }
                    } else {
                        val job = lifecycleScope.launch {
                            binding.activeSession.collectLatest { session ->
                                if (session == null) {
                                    appViewModel.detachSession()
                                } else {
                                    appViewModel.attachSession(
                                        session = session,
                                        packetLog = binding.currentPacketLog(),
                                        preferences = binding.sessionPreferences,
                                        accountSwitchState = binding.accountSwitchState,
                                        switchAccount = binding::switchAccount,
                                        notificationSettings = binding.notificationSettings,
                                        onNotificationSettingsChanged = binding::saveNotificationSettings,
                                        readNotificationDiagnostics = binding::readNotificationDiagnostics,
                                        deobfuscationEnabled = binding.deobfuscationEnabled,
                                        onDeobfuscationEnabledChanged = binding::saveDeobfuscationEnabled,
                                    )
                                }
                            }
                        }
                        onDispose {
                            job.cancel()
                            appViewModel.awaitSessionBinding()
                        }
                    }
                }
                dev.vivian.mobilebc.portrait.PortraitVisibilityProvider(this@MainActivity) {
                    MobileBcApp(
                        viewModel = appViewModel,
                        appearance = appearance,
                        onAppearanceChange = { appearance = it; appearanceStore.save(it) },
                        appLanguage = mobileBcApplication.appLanguageManager.current,
                        onAppLanguageChange = { language ->
                            mobileBcApplication.appLanguageManager.set(language)
                            appViewModel.refreshLocalizedResources()
                            BcSessionForegroundService.refreshLanguage(this@MainActivity)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                recreate()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordMessageNotificationOpen(intent)
    }

    override fun onDestroy() {
        serviceConnection = null
        super.onDestroy()
    }

    private fun recordMessageNotificationOpen(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_FROM_MESSAGE_NOTIFICATION) {
            messageNotificationOpenToken.value = messageNotificationOpenToken.value + 1
        }
    }

    companion object {
        const val ACTION_OPEN_FROM_MESSAGE_NOTIFICATION = "dev.vivian.mobilebc.action.OPEN_FROM_MESSAGE_NOTIFICATION"
    }
}
