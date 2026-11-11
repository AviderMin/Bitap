package com.bitap.keyboard.qa

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Debug-only editor host for device QA. It is never part of the release app:
 * app/src/debug is only merged into the debug variant.
 * Runs in :qa so synchronous IME context reads cannot block the editor's UI
 * thread in the same process. All edits still pass through the real IME.
 *
 * Launch examples:
 *   adb shell am start -n com.bitap.keyboard/.qa.QaEditorActivity --es field native --es preset emoji
 *   adb shell am start -n com.bitap.keyboard/.qa.QaEditorActivity --es field compose --es preset hel
 *   adb shell am start -n com.bitap.keyboard/.qa.QaEditorActivity --es field selectall --es preset hello
 *   adb shell am start -n com.bitap.keyboard/.qa.QaEditorActivity --es field web --es preset selection
 */
class QaEditorActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private var nativeEditor: EditText? = null
    private var composeValue by mutableStateOf(TextFieldValue("", TextRange(0)))
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = intent.getStringExtra("field") ?: "native"
        val preset = intent.getStringExtra("preset") ?: "emoji"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            text = "loading"
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        when (field) {
            "compose" -> addComposeEditor(root, preset)
            "web" -> addWebEditor(root, preset)
            "selectall" -> addSelectAllEditor(root, preset)
            else -> addNativeEditor(root, preset)
        }
        setContentView(root)
        handler.post(object : Runnable {
            override fun run() {
                if (field != "web") statusView.text = currentStatus(field)
                handler.postDelayed(this, 250)
            }
        })
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        super.onDestroy()
    }

    private fun addNativeEditor(root: LinearLayout, preset: String) {
        val initial = initialText(preset)
        nativeEditor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setSingleLine(false)
            setText(initial)
            setSelection(initialSelection(initial).start, initialSelection(initial).end)
            textSize = 24f
            requestFocus()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(nativeEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        // Debug-only trigger for the host ending its composing region. It must not
        // take focus: a focus change would detach the IME and invalidate the
        // keyboard coordinates the device scripts inject afterwards.
        root.addView(Button(this).apply {
            text = "结束组合"
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { endNativeComposing() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Simulates the host editor ending composing without moving the caret: the
     * Editable's composing spans are cleared and updateSelection(-1, -1) tells the
     * IME that no composing region remains. Text and caret stay untouched.
     */
    private fun endNativeComposing() {
        val edit = nativeEditor ?: return
        val editable = edit.text ?: return
        val selectionStart = edit.selectionStart
        val selectionEnd = edit.selectionEnd
        BaseInputConnection.removeComposingSpans(editable)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.updateSelection(edit, selectionStart, selectionEnd, -1, -1)
        statusView.text = currentStatus("native")
    }

    private fun addSelectAllEditor(root: LinearLayout, preset: String) {
        val initial = initialText(preset)
        val edit = SelectAllFallbackEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setSingleLine(false)
            setText(initial)
            setSelection(initialSelection(initial).start, initialSelection(initial).end)
            textSize = 24f
            requestFocus()
        }
        nativeEditor = edit
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
    }

    /**
     * Host editor that rejects the selectAll context-menu action and exposes only
     * a bogus local ExtractedText window ("middle" at absolute offset 3). A correct
     * IME must refuse to select that partial window instead of trusting it.
     */
    private class SelectAllFallbackEditText(context: Context) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val real = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(real, true) {
                override fun performContextMenuAction(id: Int): Boolean {
                    if (id == android.R.id.selectAll) return false
                    return super.performContextMenuAction(id)
                }

                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
                    return ExtractedText().apply {
                        text = "middle"
                        startOffset = 3
                        partialStartOffset = -1
                        partialEndOffset = -1
                        selectionStart = 0
                        selectionEnd = 0
                    }
                }
            }
        }
    }

    private fun addComposeEditor(root: LinearLayout, preset: String) {
        val initial = initialText(preset)
        composeValue = TextFieldValue(initial, initialSelection(initial))
        val composeView = ComposeView(this)
        root.addView(composeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        composeView.setContent {
            val focusRequester = remember { FocusRequester() }
            BasicTextField(
                value = composeValue,
                onValueChange = { composeValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(color = ComposeColor.Black, fontSize = 24.sp),
                singleLine = false,
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }

    private fun addWebEditor(root: LinearLayout, preset: String) {
        val rawInitial = initialText(preset)
        val initial = rawInitial.replace("&", "&amp;").replace("<", "&lt;")
        val selectionStart = initialSelection(rawInitial).start
        val selectionEnd = initialSelection(rawInitial).end
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;margin:12px}textarea{width:100%;height:180px;font-size:24px}
            #status{font-family:monospace;font-size:14px;margin-top:8px;word-break:break-all}</style></head>
            <body><textarea id="main">$initial</textarea><div id="status">loading</div>
            <script>
            var t=document.getElementById('main'), s=document.getElementById('status');
            function upd(){s.textContent='len='+t.value.length+' sel='+t.selectionStart+'-'+t.selectionEnd+' value='+JSON.stringify(t.value); if(window.BitapQa&&window.BitapQa.updateStatus) BitapQa.updateStatus(s.textContent);}
            ['input','select','keyup','click','selectionchange'].forEach(function(e){t.addEventListener(e,upd);});
            window.addEventListener('load',function(){t.focus();t.setSelectionRange($selectionStart,$selectionEnd);upd();});
            </script></body></html>
        """.trimIndent()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.WHITE)
            addJavascriptInterface(object {
                @JavascriptInterface
                fun updateStatus(value: String) {
                    runOnUiThread { statusView.text = value }
                }
            }, "BitapQa")
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            requestFocus()
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
    }

    private fun currentStatus(field: String): String {
        if (field == "web") {
            // The WebView's visible #status div is read through uiautomator; this
            // outer status is just a hint for manual inspection.
            return "web: use page status"
        }
        return if (field == "compose") {
            val value = composeValue
            "len=" + value.text.length + " sel=" + value.selection.start + "-" + value.selection.end + " value=" + value.text
        } else {
            val edit = nativeEditor ?: return "native missing"
            "len=" + edit.text.length + " sel=" + edit.selectionStart + "-" + edit.selectionEnd + " value=" + edit.text
        }
    }

    private fun initialText(preset: String): String = when (preset) {
        "empty" -> ""
        "hel" -> "hel"
        "hello" -> "hello world"
        "selection" -> "abcd"
        else -> "A😀B"
    }

    private fun initialSelection(text: String): TextRange {
        val fallbackStart = if (intent.getStringExtra("preset") == "selection") 1 else text.length
        val fallbackEnd = if (intent.getStringExtra("preset") == "selection") 3 else text.length
        return TextRange(
            intent.getIntExtra("selectionStart", fallbackStart).coerceIn(0, text.length),
            intent.getIntExtra("selectionEnd", fallbackEnd).coerceIn(0, text.length),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
