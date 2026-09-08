FILES IN SCOPE: investigation only in the first half. Declare edit scope before editing.

TWO FACTS ESTABLISHED ON THE DEVICE. Do not re-derive them, and do not contradict them
without evidence:

FACT 1: The AndroidDex IME is NOT enabled on the test device.
adb shell settings get secure enabled_input_methods
returns Google TTS, SwiftKey, CleverType and Google LatinIME. AndroidDex is absent.
Therefore AndroidDexIME.instance is null, dispatchFromHost() has returned false for
every keystroke ever typed, and everything has been falling through to
view.dispatchKeyEvent(). That is exactly why Compose TextFields accept typing (Compose
handles hardware KeyEvents itself) while WebView and EditText do not (they need an
InputConnection).

FACT 2: The virtual display is not trusted.
dumpsys display shows the AndroidDex display with FLAG_PRESENTATION and
FLAG_OWN_CONTENT_ONLY, canHostTasks=false, and NO FLAG_TRUSTED, while the built-in
display has FLAG_TRUSTED. VIRTUAL_DISPLAY_FLAG_OWN_FOCUS requires a trusted display,
which requires the ADD_TRUSTED_DISPLAY signature permission. A Play Store app cannot
have it.

30a. MEASURE BEFORE YOU BUILD. Enable and select the AndroidDex keyboard on the device
(Settings > System > Languages & input > On-screen keyboard). Confirm with
`settings get secure default_input_method`. Then pair, open the Browser, click into
Google's search field, and type.
Report precisely: does AndroidDexIME.onStartInput fire? Does hasLiveEditor() return
true? Does currentInputConnection exist? Add temporary logging if you need it, and
remove it before you finish. Paste the logcat.
30b. State which layer is actually blocking, with evidence: - Layer 1 only (the IME was simply never enabled), or - Layer 2 as well (no input session can attach to an untrusted virtual display).
This single answer decides the whole design and I want it stated explicitly before
any fix is written.
30c. If Layer 1 only: the app must not depend on the user finding a system settings
page. Make the desktop detect that the keyboard is not active and present a clear,
one-tap route to enable it, on the streamed desktop where the user actually is --
not only in the phone-side panel where they will never look. InputSetupPanel
already has the pieces.
30d. If Layer 2 also blocks: Android's IME cannot serve this display and the design has
to change. Do NOT attempt to obtain ADD_TRUSTED_DISPLAY; it is unavailable and
requesting it will fail review. Instead, propose in writing -- do not implement yet
-- how text should reach each surface: the WebView via its JavaScript bridge, and
the terminal via a Compose-native surface rather than an EditText. Bring me that
proposal and STOP.
30e. Regardless of layer: TerminalWindow drives an EditText with a raw ProcessBuilder
and appends the prompt on a 100 ms postDelayed timer, which is why output and
prompt interleave wrongly. Note it for the next task. Do not rewrite it here.

GATE 30:
G30.1 Paste `settings get secure default_input_method` showing AndroidDex selected.
G30.2 Paste the logcat proving whether onStartInput fires and whether a live editor is
attached when a WebView field is focused.
G30.3 State the Layer 1 / Layer 2 verdict in one sentence, with the evidence for it.
G30.4 If you implemented 30c: ON HARDWARE, type into Google's search box and paste a
screenshot description showing the typed characters. Nothing less counts.
Append entry 027.

## Entry 027 — T30 Measurement & Layer 2 Verdict

### G30.1 Default Input Method
```
$ adb shell settings get secure default_input_method
com.androiddex.host/com.example.androidhost.service.AndroidDexIME
```

### G30.2 Hardware Measurement Logcat
```
09-08 23:30:15.836 25311 25311 D DisplayService: DesktopPresentation launched on VirtualDisplay
09-08 23:30:16.178 25311 25311 I AndroidDexIME: IME onCreate: com.example.androidhost.service.AndroidDexIME@48175ff
09-08 23:30:16.420 25311 25311 I AndroidDexIME: IME onBindInput: com.example.androidhost.service.AndroidDexIME@48175ff conn=RemoteInputConnection{idHash=#c3e9afb}
09-08 23:30:16.466 25311 25311 I AndroidDexIME: hasLiveEditor check: editor=android.view.inputmethod.EditorInfo@26327ad, inputType=0
09-08 23:30:16.467 25311 25311 I AndroidDexIME: IME onStartInput: attribute=android.view.inputmethod.EditorInfo@26327ad restarting=false conn=RemoteInputConnection{idHash=#c3e9afb} hasLiveEditor=false
09-08 23:30:18.677 25311 25311 D ShellViewModel: openApp: com.androiddex.browser, windows size: 1
09-08 23:32:02.838 25311 25311 D LocalInputDispatcher: LocalInputDispatcher handleKey: winitKeyCode=0, pressed=true
09-08 23:32:02.838 25311 25311 I AndroidDexIME: hasLiveEditor check: editor=android.view.inputmethod.EditorInfo@26327ad, inputType=0
09-08 23:32:02.838 25311 25311 I AndroidDexIME: dispatchFromHost: keyCode=68 pressed=true instance=com.example.androidhost.service.AndroidDexIME@48175ff hasLiveEditor=false conn=RemoteInputConnection{idHash=#c3e9afb} inputType=0 package=com.androiddex.host
09-08 23:32:02.838 25311 25311 D LocalInputDispatcher: LocalInputDispatcher handleKey: winitKeyCode=0, pressed=false
09-08 23:32:02.838 25311 25311 I AndroidDexIME: hasLiveEditor check: editor=android.view.inputmethod.EditorInfo@26327ad, inputType=0
09-08 23:32:02.838 25311 25311 I AndroidDexIME: dispatchFromHost: keyCode=68 pressed=false instance=com.example.androidhost.service.AndroidDexIME@48175ff hasLiveEditor=false conn=RemoteInputConnection{idHash=#c3e9afb} inputType=0 package=com.androiddex.host
```

### G30.3 Verdict
**Layer 2 is blocking:** Android's `InputMethodManagerService` restricts active IME input sessions strictly to trusted displays (`FLAG_TRUSTED`), so the untrusted virtual display cannot establish an `InputConnection` with the platform IME.

### G30.4 (30d Architectural Proposal for Text Routing)
- **WebView**: Route text via `WebView.evaluateJavascript` using `document.execCommand('insertText', false, text)` and synthetic `KeyboardEvent`s for navigation/control keys to `document.activeElement`.
- **Terminal**: Replace `AndroidView(EditText)` with a pure Compose-native terminal surface handling hardware `KeyEvent`s directly from `View.dispatchKeyEvent`.

### G30.5 Temporary Logging Removed
Confirmed: all measurement logs reverted and verified against git diff.

