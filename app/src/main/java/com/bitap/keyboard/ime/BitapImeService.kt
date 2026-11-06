package com.bitap.keyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.bitap.keyboard.BitapApplication
import com.bitap.keyboard.core.AndroidTextSession
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.settings.DarkMode
import com.bitap.keyboard.ui.theme.BitapTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Bitap keyboard service. Owns its own lifecycle/saved-state/view-model stores so a
 * ComposeView can be hosted inside an InputMethodService without an Activity.
 */
class BitapImeService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val pinyinEngine = PinyinEngine.empty()

    private lateinit var app: BitapApplication
    private lateinit var controller: KeyboardController
    private var inputView: ComposeView? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        app = application as BitapApplication
        savedStateController.performAttach()
        savedStateController.performRestore(null as Bundle?)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        controller = KeyboardController(
            scope = serviceScope,
            textSession = AndroidTextSession(this),
            pinyinEngine = pinyinEngine,
            aiService = app.aiService,
        )

        serviceScope.launch {
            app.settingsRepository.settings.collect { settings -> controller.onSettingsChanged(settings) }
        }
        serviceScope.launch {
            app.aiService.runtimeState.collect { runtime -> controller.onAiRuntimeChanged(runtime) }
        }
        serviceScope.launch {
            runCatching { pinyinEngine.loadFromAssets(this@BitapImeService) }
                .onSuccess { controller.onPinyinReady() }
        }
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            ViewTreeOwners.attach(this, this@BitapImeService, this@BitapImeService, this@BitapImeService)
            setContent {
                val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()
                val darkTheme = when (settings.darkMode) {
                    DarkMode.System -> isSystemInDarkTheme()
                    DarkMode.Light -> false
                    DarkMode.Dark -> true
                }
                BitapTheme(themeFamily = settings.themeFamily, darkTheme = darkTheme) {
                    val state by controller.state.collectAsStateWithLifecycle()
                    KeyboardScreen(
                        state = state,
                        onAction = controller::dispatch,
                    )
                }
            }
        }
        inputView = view
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        controller.onStartInput()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onFinishInput() {
        controller.onFinishInput()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        inputView = null
        if (this::controller.isInitialized) controller.dispose()
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }
}
