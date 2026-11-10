package com.bitap.keyboard.ime

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.bitap.keyboard.MainActivity
import com.bitap.keyboard.core.AndroidTextSession
import com.bitap.keyboard.core.DictionaryLoadState
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.core.EnglishPredictionStore
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.settings.DarkMode
import com.bitap.keyboard.ui.theme.BitapKeyboardTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** P0-B: the single in-flight dictionary load; cancelled on destroy/retry. */
    private var dictionaryLoadJob: Job? = null
    private var inputView: ComposeView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Set when a rotation happened while the IME window was visible. */
    private var restoreInputViewAfterConfigChange = false

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
        attachOwnersToWindowDecor()
        configureWindowTransparency()

        controller = KeyboardController(
            scope = serviceScope,
            textSession = AndroidTextSession(this),
            pinyinEngine = pinyinEngine,
            aiService = app.aiService,
            onDictionaryRetry = ::loadPinyinDictionary,
            englishPredictionStore = EnglishPredictionStore.create(this),
        )

        serviceScope.launch {
            app.settingsRepository.settings.collect { settings -> controller.onSettingsChanged(settings) }
        }
        serviceScope.launch {
            app.aiService.runtimeState.collect { runtime -> controller.onAiRuntimeChanged(runtime) }
        }
        loadPinyinDictionary()
    }

    /**
     * P0-B: explicit, observable dictionary load. Loading is published before
     * the coroutine starts, so an initial load and every retry show progress;
     * success publishes Ready and wakes a pending query through
     * [KeyboardController.onPinyinReady]. Cancellation is rethrown unchanged so
     * service teardown / a superseding retry is never reported as a failure.
     */
    private fun loadPinyinDictionary() {
        dictionaryLoadJob?.cancel()
        controller.onDictionaryStateChanged(DictionaryLoadState.Loading)
        dictionaryLoadJob = serviceScope.launch {
            try {
                pinyinEngine.loadFromAssets(this@BitapImeService)
                controller.onDictionaryStateChanged(DictionaryLoadState.Ready)
                controller.onPinyinReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                controller.onDictionaryStateChanged(
                    DictionaryLoadState.Failed,
                    KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE,
                )
            }
        }
    }

    override fun onCreateInputView(): View {
        // Compose looks for owners on the window DecorView (InputMethodService's
        // SoftInputWindow is a Dialog), not only on the ComposeView itself.
        attachOwnersToWindowDecor()
        configureWindowTransparency()
        val view = ComposeView(this).apply {
            ViewTreeOwners.attach(this, this@BitapImeService, this@BitapImeService, this@BitapImeService)
            setContent {
                val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()
                val darkTheme = when (settings.darkMode) {
                    DarkMode.System -> isSystemInDarkTheme()
                    DarkMode.Light -> false
                    DarkMode.Dark -> true
                }
                BitapKeyboardTheme(theme = settings.keyboardTheme, darkTheme = darkTheme) {
                    val state by controller.state.collectAsStateWithLifecycle()
                    KeyboardScreen(
                        state = state,
                        onAction = controller::dispatch,
                        onOpenSettings = {
                            startActivity(
                                Intent(this@BitapImeService, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                        },
                    )
                }
            }
        }
        inputView = view
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        controller.onStartInput(
            EditorInputInfo.from(
                inputType = attribute?.inputType ?: 0,
                imeOptions = attribute?.imeOptions ?: 0,
            ),
        )
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        // Rotation best-effort: the host activity may be recreated while the IME
        // window stays hidden. If the keyboard was visible before the config
        // change, ask the system to show it again for the new input session.
        if (restoreInputViewAfterConfigChange) {
            restoreInputViewAfterConfigChange = false
            mainHandler.postDelayed({ requestShowSelfBestEffort() }, 120)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (isInputViewShown) restoreInputViewAfterConfigChange = true
        super.onConfigurationChanged(newConfig)
        if (restoreInputViewAfterConfigChange) {
            mainHandler.postDelayed({ requestShowSelfBestEffort() }, 200)
        }
    }

    private fun requestShowSelfBestEffort() {
        if (isInputViewShown || window == null) return
        runCatching { requestShowSelf(0) }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        controller.onSelectionChanged(
            selectionStart = newSelStart,
            selectionEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
            oldSelectionStart = oldSelStart,
            oldSelectionEnd = oldSelEnd,
        )
    }

    override fun onFinishInput() {
        controller.onFinishInput()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onFinishInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        restoreInputViewAfterConfigChange = false
        attachOwnersToWindowDecor()
        configureWindowTransparency()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The keyboard draws its own rounded dock. The IME window itself must stay
     * transparent, otherwise its theme background shows through the clipped top
     * corners as an opaque dark band.
     */
    private fun configureWindowTransparency() {
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)
        window?.window?.setDimAmount(0f)
    }

    private fun attachOwnersToWindowDecor() {
        window?.window?.decorView?.let { decorView ->
            ViewTreeOwners.attach(decorView, this, this, this)
        }
    }

    override fun onDestroy() {
        inputView = null
        dictionaryLoadJob?.cancel()
        dictionaryLoadJob = null
        if (this::controller.isInitialized) controller.dispose()
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }
}
