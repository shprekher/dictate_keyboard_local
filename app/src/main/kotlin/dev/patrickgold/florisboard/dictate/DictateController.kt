/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.audio.AudioConcat
import dev.patrickgold.florisboard.dictate.audio.AudioConvert
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import dev.patrickgold.florisboard.dictate.audio.AudioLevelSmoother
import dev.patrickgold.florisboard.dictate.audio.AudioEncode
import dev.patrickgold.florisboard.dictate.audio.AudioSpeedUp
import dev.patrickgold.florisboard.dictate.audio.BluetoothMicRouter
import dev.patrickgold.florisboard.dictate.audio.LiveSpeechSplitter
import dev.patrickgold.florisboard.dictate.audio.SmartTurnModel
import dev.patrickgold.florisboard.dictate.audio.Pcm16Resampler
import dev.patrickgold.florisboard.dictate.audio.RecordingController
import dev.patrickgold.florisboard.dictate.audio.SpeechGate
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupProvider
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupPipeline
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupPrompt
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryEntry
import dev.patrickgold.florisboard.dictate.data.history.DictateHistorySource
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryStore
import dev.patrickgold.florisboard.dictate.data.history.HistoryEngine
import dev.patrickgold.florisboard.dictate.data.stats.DictateStats
import dev.patrickgold.florisboard.dictate.field.TranscriptHandover
import dev.patrickgold.florisboard.dictate.provider.ChatRequest
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalRealtimeSession
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.RealtimeCallbacks
import dev.patrickgold.florisboard.dictate.provider.RealtimeSession
import dev.patrickgold.florisboard.dictate.provider.AudioContainer
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderListing
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.TranscriptionFallback
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.dictate.overlay.AccessibilitySink
import dev.patrickgold.florisboard.dictate.overlay.DictateAccessibilityService
import dev.patrickgold.florisboard.dictate.overlay.floatingButtonTapBuzzes
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import dev.patrickgold.florisboard.dictate.provider.chatModelIsPresetDefault
import dev.patrickgold.florisboard.dictate.recognition.RecognitionSink
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import dev.patrickgold.florisboard.lib.util.VersionName
import org.florisboard.lib.kotlin.curlyFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates the dictation flow that fuses the recording, the provider layer and the editor: tap
 * to record, tap again to transcribe the audio and commit the result into the focused text field.
 *
 * Provider, API key and model are read from the unified JetPref store (`prefs.dictate`), which is
 * seeded once from the legacy Dictate settings on first run (see [dev.patrickgold.florisboard.
 * dictate.data.prefs.DictateLegacyMigrator]) and is editable in the in-app Dictate settings screen.
 *
 * Ported comfort features (all toggleable in the Dictate settings): pause/resume, cancel, audio
 * focus (pause other apps while recording), optional Bluetooth-SCO mic and a transcription retry
 * with a visible indicator.
 *
 * Rewording (GPT) is wired in: [applyPrompt] runs a prompt on the selection/cursor, [startLivePrompt]
 * sends a spoken instruction to the model, and every transcription runs through [postProcessTranscript]
 * (auto-formatting + auto-apply prompts). The prompt chips that drive these come later (UI phase).
 *
 * Not yet ported from the legacy service (later refinement): usage tracking.
 */
object DictateController {

    private const val LATENCY_LOG_TAG = "DictateLatency"
    private val latencyFlowIds = AtomicLong()

    /** Correlates privacy-safe phase timings for one batch transcription without logging its contents. */
    private data class BatchLatencyTrace(
        val id: Long = latencyFlowIds.incrementAndGet(),
        val startedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    )

    private fun logLatency(trace: BatchLatencyTrace, phase: String, phaseStartedNanos: Long? = null) {
        val now = SystemClock.elapsedRealtimeNanos()
        val phaseMs = phaseStartedNanos?.let { TimeUnit.NANOSECONDS.toMillis(now - it) }
        Log.i(
            LATENCY_LOG_TAG,
            buildString {
                append("flow=").append(trace.id)
                append(" phase=").append(phase)
                phaseMs?.let { append(" phaseMs=").append(it) }
                append(" totalMs=")
                    .append(TimeUnit.NANOSECONDS.toMillis(now - trace.startedNanos))
            },
        )
    }

    sealed interface UiState {
        data object Idle : UiState
        data class Recording(
            /** [SystemClock.elapsedRealtime] when the current (running) segment started. */
            val startedAtMs: Long,
            /** Elapsed time accumulated across previous, already-finished segments (before pauses). */
            val accumulatedMs: Long = 0L,
            val paused: Boolean = false,
        ) : UiState
        /**
         * [attempt] is 1 for the first try, 2/3/… while retrying after a transient failure.
         * [onDevice] means the on-device engine is doing the work — because it is the chosen provider,
         * because the offline fallback took over (#104), or because the user held the button to send this
         * one dictation locally (#228/#270). The bar says so: which engine is running is the difference
         * between waiting on a network and waiting on this phone.
         *
         * [startedAtMs] is an [SystemClock.elapsedRealtime] stamp driving the elapsed-seconds readout
         * (#355) — the moment the *wait* began, not the moment this particular state object was made, so
         * a retry or a handover to the fallback keeps counting up instead of starting over. It carries no
         * default for that reason: only [setTranscribing] knows whether this continues a wait or starts
         * one, so go through it rather than constructing this directly.
         */
        data class Transcribing(
            val attempt: Int = 1,
            val onDevice: Boolean = false,
            val startedAtMs: Long,
            /**
             * The fallback provider this dictation was handed to because the active one could not be
             * reached (#455), by name; null while the active provider is the one transcribing.
             */
            val via: String? = null,
        ) : UiState
        /** Automatic cleanup is in flight; the historical name is retained for existing UI consumers. */
        data class Rewording(val label: String) : UiState
        /**
         * A failed transcription/rewording (roadmap 1.12). [message] is the short, localized headline
         * (derived from [kind]); [detail] is the raw provider text shown when the user taps the chip;
         * [action] is the contextual button offered (resend the kept audio, open settings, or none).
         */
        data class Error(
            val message: String,
            val kind: DictateApiException.Kind? = null,
            val action: ErrorAction = ErrorAction.NONE,
            val detail: String? = null,
            /** Informational (not a failure), e.g. "no speech detected" — rendered neutral, not red. */
            val neutral: Boolean = false,
        ) : UiState
        /**
         * Offer to send a recording that was interrupted because the keyboard closed mid-recording: the
         * audio was finalized and persisted, so on the next keyboard open this neutral (non-error) chip
         * offers to transcribe it or discard it. [seconds] is the captured length, shown for context.
         */
        data class Interrupted(val seconds: Long) : UiState
    }

    /** Why an audio file is being kept for a one-tap re-send (drives the unified resend chip copy/tint). */
    enum class RetainReason {
        /**
         * A transcription/rewording failed, or the user stopped one that was still waiting (#437); the kept
         * audio can be retried (in-memory, cache file).
         */
        FAILED,

        /** The keyboard closed mid-recording; the finalized audio was persisted to survive process death. */
        INTERRUPTED,
    }

    /** The contextual action a [UiState.Error] offers (see roadmap 1.12 keyboard design). */
    enum class ErrorAction {
        /** No action; the chip auto-clears after a moment. */
        NONE,

        /** Retry the same kept audio (transient failures with retained audio, roadmap 10.3). */
        RESEND,

        /** Open the Dictate provider settings (fixable errors like an invalid/missing API key). */
        OPEN_SETTINGS,

        /**
         * Export the kept recording to Downloads (issue #144): offered when transcription fails for a
         * reason that resending can't fix (too large / unsupported format) so a long recording isn't lost.
         */
        SAVE_AUDIO,
    }

    /**
     * Where the active dictation's output goes: the keyboard editor ([OutputTarget.IME]) or the
     * accessibility-injected field of the floating button ([OutputTarget.OVERLAY], issue #88). Set when a
     * dictation starts (the mic-tap entry points carry their source); the two never drive concurrently.
     */
    enum class OutputTarget { IME, OVERLAY, RECOGNITION_SERVICE }

    /**
     * Temporary debug switch to preview the "Dictate was updated" Smartbar nudge. When true, the nudge
     * is offered on every keyboard open (the real version gate never triggers on debug builds, whose
     * version name carries an unparseable suffix). MUST be false for any committed/shipped build.
     */
    private const val DEBUG_FORCE_CHANGELOG_NUDGE = false

    /** Forces the floating-button spotlight regardless of gates (testing only). MUST be false for shipped builds. */
    private const val DEBUG_FORCE_FB_SPOTLIGHT = false

    /** This build's release, patch level dropped — the bucket the once-per-release nudges are keyed on. */
    private val FEATURE_VERSION_NAME = VersionName.featureVersionOf(BuildConfig.VERSION_NAME)

    private val prefs by FlorisPreferenceStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()



    // --- Real-time streaming transcription (issue #128) -----------------------------------------
    private val _interimText = MutableStateFlow("")
    /**
     * Live transcript while a real-time recording runs: finalized segments plus the current partial. The
     * Smartbar shows this as a live caption; the field only receives the finished (reworded) text on stop.
     * A long-form dictation from the floating button fills it too, with the segments transcribed so far
     * (#460). Empty outside those two.
     */
    val interimText: StateFlow<String> = _interimText.asStateFlow()

    /**
     * Whether the floating button may caption the running stream next to itself (discussion #453), or the
     * long-form segments transcribed so far (#460): only a dictation the button started, never a live
     * prompt — its words are an instruction, the same reason
     * they stay out of the field — and not for someone who keeps the stream out of sight until stop
     * (`prefs.dictate.realtimeHidePreview`, #345). The field never sees the words before stop, whatever this says.
     */
    fun overlayPreviewWanted(): Boolean =
        outputTarget == OutputTarget.OVERLAY && !livePromptArmed && !prefs.dictate.realtimeHidePreview.get()

    private var realtimeSession: RealtimeSession? = null
    private val realtimeFinal = StringBuilder()      // accumulated finalized segments
    @Volatile private var realtimeFailed = false     // stream errored → fall back to batch on stop
    private var realtimeClosed: CompletableDeferred<Unit>? = null
    private var realtimeContext: Context? = null     // app context to edit the field's provisional text
    private val realtimeShown = StringBuilder()       // text currently committed to the field this session
    /** The transcript [realtimeShown] was last written from: what the user sees, and takes over by editing it. */
    private var realtimeShownTranscript = ""

    /**
     * What the user has taken over of this realtime dictation by editing it in the field (issue #421) —
     * empty until they do. It is theirs from then on: the preview never revises it again and writes only
     * what the transcript says after it ([TranscriptHandover.continuation]), and the finished text at the
     * stop replaces only that rest. See [handOverIfEdited].
     */
    private var realtimeHandedOver = ""

    /**
     * The separator decided for the dictation currently in the field — a space or nothing (issue #443),
     * see [leadFor]. Decided once, at the dictation's first write: by its second, the text in front of the
     * cursor is our own. While [realtimeShown] holds a preview it begins with this, so the diff, the
     * finalize and the cancel all carry the separator along without knowing about it.
     */
    private var dictationLead = ""

    /** The separator in front of the last committed dictation, so the bubble's undo takes it back too. */
    private var lastDictationLead = ""

    /**
     * Everything the stream has produced so far, whether or not any of it was put into the field (#345).
     *
     * [realtimeShown] cannot answer that on its own: it means *what is in the field right now*, and the
     * cancel/fallback paths delete exactly that much text — so with the preview hidden it has to stay
     * empty, or stopping would eat the user's own words. This buffer is what gets committed on stop.
     */
    private val realtimeTranscript = StringBuilder()
    /** Hold the streamed words back until stop (`realtimeHidePreview`), instead of typing them live. */
    private var realtimeHidden = false
    /** When the stream last produced text; the tail wait on stop measures the provider's silence from here (#372). */
    @Volatile private var realtimeLastTextAt = 0L
    @Volatile private var realtimeCancelled = false   // block late stream callbacks from re-adding text

    // --- Long-form segmented dictation (issue #170) ---------------------------------------------
    // Segmented mode transcribes cut segments in the background while recording continues, appending raw
    // text to the field as a live preview (reusing [realtimeShown] as the shown-text buffer, since
    // segmented and realtime are mutually exclusive). All formatting/rewording runs ONCE at the end via
    // finalizeAndCommit(finalizeViaComposing=true), which replaces the preview with the finished text.
    private var segmentedActive = false
    private var segmentNextIndex = 0          // next index to assign to a cut segment (cut order)
    private var segmentCommitIndex = 0        // next index expected by the ordered commit drain
    private val segmentResults = HashMap<Int, String>()  // index -> raw text, buffered until in-order
    private val segmentJobs = mutableSetOf<Job>()
    private val segmentMutex = Mutex()        // orders index assignment + rotate + the commit drain
    private var segmentInFlightCount = 0      // segments cut but not yet committed
    private var segmentStopped = false        // stop requested; finish once the queue drains
    // Moves on whenever a session ends or gives up on its pending segments. A segment job carries the
    // value it was cut under, and a result from another one is dropped: it belongs to a dictation that is
    // over, and the maps and the field it would write into now belong to the next one (#172).
    private var segmentGeneration = 0
    private var segmentRecordedSeconds = 0L
    private var segmentVad: LiveSpeechSplitter? = null  // live VAD auto-split, when enabled (Phase 2)
    private val segmentAudioFiles = HashMap<Int, File>()  // kept segment WAVs (index -> file) for history merge
    private var segmentKeepAudio = false                  // whether to keep + merge segment audio (retention on)
    // Which engines answered this dictation's segments, for the history to name (#457): the fallback that
    // took some of them — the on-device engine, or since #455 another provider — and whether any came back
    // from the configured one. A connection that drops halfway through a long dictation leaves both set.
    private var segmentFallbackEngine: HistoryEngine? = null
    private var segmentAnsweredAsConfigured = false
    private val _segmentFlushCount = MutableStateFlow(0)
    /** Monotonic count of segment cuts — the recording bar flashes the Next button on each change (#170). */
    val segmentFlushCount: StateFlow<Int> = _segmentFlushCount.asStateFlow()
    private val _segmentedRecording = MutableStateFlow(false)
    /** True while a long-form segmented recording is active — drives the "Next segment" button (#170). */
    val segmentedRecording: StateFlow<Boolean> = _segmentedRecording.asStateFlow()
    private val _segmentsInFlight = MutableStateFlow(0)
    /** How many cut segments are transcribing in the background — drives the recording-bar badge (#170). */
    val segmentsInFlight: StateFlow<Int> = _segmentsInFlight.asStateFlow()

    private var recorder: RecordingController? = null
    private var startJob: Job? = null

    // --- Push-to-talk (issue #235) ---------------------------------------------------------------

    private fun setPushToTalk(
        phase: PushToTalkPhase = _pushToTalkVisuals.value.phase,
        lockFlash: Boolean = _pushToTalkVisuals.value.lockFlash,
        discarding: Boolean = _pushToTalkVisuals.value.discarding,
    ) {
        _pushToTalkVisuals.value = PushToTalkVisuals(phase, lockFlash, discarding)
        _pushToTalkPhase.value = phase
    }

    private val _pushToTalkPhase = MutableStateFlow(PushToTalkPhase.NONE)
    /**
     * Where a hold-to-record gesture currently stands, so the recording bar can show the matching
     * affordance ("slide to cancel", the armed-cancel state, or the ordinary bar once locked).
     */
    val pushToTalkPhase: StateFlow<PushToTalkPhase> = _pushToTalkPhase.asStateFlow()

    private val _cancelSlideProgress = MutableStateFlow(0f)
    /** How far the finger has slid towards discarding, 0..1 — the bar slides its content with it. */
    val cancelSlideProgress: StateFlow<Float> = _cancelSlideProgress.asStateFlow()

    private val _lockSlideProgress = MutableStateFlow(0f)
    /** How far the finger has slid towards the lock, 0..1 — the lock target fills with it. */
    val lockSlideProgress: StateFlow<Float> = _lockSlideProgress.asStateFlow()


    /**
     * Set when the finger is lifted before [startRecording]'s job has produced a recorder. Starting is
     * asynchronous (audio focus, and Bluetooth SCO can take seconds), so a short press-and-release
     * regularly outruns it; the job honours this once there is actually something to stop.
     */
    @Volatile private var pttStopPending = false

    /** Whether that pending release sends the recording, or drops it as too short to be a dictation (#422). */
    @Volatile private var pttStopSends = true

    private val _audioLevel = MutableStateFlow(0f)
    /**
     * Shared, noise-gated microphone level for lightweight recording visuals. Sampling once here keeps
     * the Smartbar and floating overlay from competing for [RecordingController.maxAmplitude]'s
     * read-and-reset peak. Values are smoothed and normalized to 0..1 at 20 Hz.
     */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _audioPeak = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * The same 20 Hz microphone measurement as [audioLevel], but *without* the attack/release smoothing —
     * each value is the gated, curved peak of its own 50 ms window and nothing else (issue #371).
     *
     * A waveform needs this rather than [audioLevel]: the smoother's release of 0.2 per sample takes about
     * half a second to fall back to zero, so a scrolling trace fed from it would still be drawing bars
     * half a second after the speaker went quiet — and "am I quiet yet" is the entire question the
     * waveform exists to answer. The dot keeps the smoothed value, where the damping is what stops it
     * flickering.
     *
     * A stream of events rather than a [StateFlow] on purpose: a state flow conflates equal values, and
     * silence is a run of exact zeros — the trace would stop scrolling at the very moment the user is
     * watching it to see that it has gone flat.
     */
    val audioPeak: SharedFlow<Float> = _audioPeak.asSharedFlow()
    private var audioLevelJob: Job? = null

    // While a recording is active we listen for the screen turning off (device locked / display timeout):
    // that is the reliable "the user has left" signal that finalizes and keeps the recording and releases
    // the mic, instead of depending on IME teardown callbacks that are not always delivered (issue #147).
    // Long recordings stay possible — this never fires while the screen is on. Held at process scope
    // (alongside the recorder) with the context used to register it, so any stop path can unregister.
    private var screenOffReceiver: BroadcastReceiver? = null
    private var screenOffContext: Context? = null

    /** The in-flight transcription coroutine, cancellable via the stop button (see [cancelTranscription]). */
    private var transcribeJob: Job? = null

    // The in-flight manual rewording coroutine (a prompt chip / "Send"), so the stop button can abort it
    // mid-generation (issue #192). The post-transcription rewording chain instead runs inside
    // Cleanup runs inside [transcribeJob] so cancellation owns the whole dictation.

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var btRouter: BluetoothMicRouter? = null

    // The input-device watch that runs for the length of a recording (#411), and the coroutine that
    // answers a lost microphone. Both are torn down by [cleanupAudioRouting], i.e. by every stop path.
    private var deviceWatch: AudioDeviceCallback? = null
    private var deviceWatchManager: AudioManager? = null
    private var routeChangeJob: Job? = null
    // Handovers already done in this dictation. Bounded, because a route that keeps handing back silence
    // would otherwise be answered with a swap every second and a half for the length of the recording.
    private var routeSwaps = 0

    /** When true, the next finished recording is fed to the rewording model instead of committed. */
    // Retained-audio structures still carry the legacy flag; new recordings are always dictation.
    private val livePromptArmed = false

    /**
     * The first rewording failure this dictation swallowed, reported once the text has landed (#284).
     * Set by [cleanupOrKeep], cleared at the top of [finalizeAndCommit], read after insertion.
     */
    private var cleanupFailure: Throwable? = null

    /**
     * Output destination of the in-flight dictation; see [OutputTarget]. Latched by every start and left
     * alone afterwards — so outside a running dictation it says where the *last* one went, not where the
     * next one will go. Anything reading it to decide ownership therefore has to check for a live
     * dictation as well (see [foreignDictationInFlight]).
     */
    private var outputTarget = OutputTarget.IME

    // Haptic feedback (#166) fires on dictation state transitions. Started lazily on the first dictation
    // (so we have an application context for the vibrator), then it observes for the whole process life.
    private var hapticObserverStarted = false

    /**
     * Whether the tap that drove this transition has already produced a buzz of its own — the floating
     * button's, which follows the keyboard's key feedback, or the keyboard's ordinary key feedback. Read at
     * the moment of the transition, so a user who turns it off starts getting the dictation buzz instead.
     */
    private fun pressAlreadyBuzzed(context: Context): Boolean = when (outputTarget) {
        OutputTarget.OVERLAY -> floatingButtonTapBuzzes(context, prefs)
        OutputTarget.IME -> prefs.inputFeedback.hapticEnabled.get()
        OutputTarget.RECOGNITION_SERVICE -> false
    }

    private fun ensureHapticObserver(context: Context) {
        if (hapticObserverStarted) return
        hapticObserverStarted = true
        val appContext = context.applicationContext
        // Capture the current state synchronously (the caller is about to change it): the launched
        // collector otherwise reads _state.value only after the change and would miss the first transition.
        val initial = _state.value
        scope.launch {
            var prev: UiState = initial
            _state.collect { new ->
                when {
                    // Record started/stopped — but only when the press that did it did not already
                    // buzz. The floating button buzzes on its own tap; on the keyboard the mic is an
                    // ordinary key and the normal key feedback fires for it. Buzzing again a few
                    // milliseconds later is not a second signal, it is a stutter, and it was the one
                    // thing people noticed about this feature. The floating button was already spared;
                    // the keyboard was not, which is why starting a dictation there buzzed twice.
                    //
                    // What is left is what the feature is actually for: the two signals that arrive
                    // while nobody is touching anything (see below). A push-to-talk release loses its
                    // buzz to this rule, which is the accepted cost — the finger was on the button.
                    //
                    // A resend enters at Transcribing, so neither branch matches for it.
                    //
                    // A screen reader's double-tap is no key press and buzzes nothing of ours, and for
                    // its user this buzz is the only sign the recording really started (#159).
                    (new is UiState.Recording && prev !is UiState.Recording) ||
                        (prev is UiState.Recording && new is UiState.Transcribing) -> {
                        if (DictateAccessibility.isScreenReaderOn(appContext) || !pressAlreadyBuzzed(appContext)) {
                            DictateHaptics.short(appContext)
                        }
                    }
                    // Transcription ready (heading to commit/idle or on to a rewording pass) — not on failure.
                    prev is UiState.Transcribing && (new is UiState.Idle || new is UiState.Rewording) ->
                        DictateHaptics.double(appContext)
                    // Rewording / LLM prompt applied.
                    prev is UiState.Rewording && new is UiState.Idle -> DictateHaptics.medium(appContext)
                }
                announceTransition(appContext, prev, new)
                prev = new
            }
        }
    }

    /**
     * The spoken half of the transitions above, for a screen reader (issue #159): the waits and whatever
     * went wrong. Never the way into a recording — see [DictateAccessibility] — and never the finished
     * text, which the screen reader already reads out as it lands in the field.
     */
    private fun announceTransition(context: Context, prev: UiState, new: UiState) {
        val text = when {
            // A retry, and the hand-over to the on-device model (#270), are news of their own.
            new is UiState.Transcribing && (prev !is UiState.Transcribing ||
                prev.attempt != new.attempt || prev.onDevice != new.onDevice) -> when {
                new.attempt > 1 ->
                    context.getString(R.string.dictate__status_retrying).curlyFormat("attempt" to new.attempt)
                new.onDevice -> context.getString(R.string.dictate__status_transcribing_local)
                else -> context.getString(R.string.dictate__status_transcribing)
            }
            new is UiState.Rewording && new != prev ->
                new.label.ifBlank { context.getString(R.string.dictate__local_cleanup_running) }
            new is UiState.Error && new != prev -> new.message
            else -> null
        } ?: return
        DictateAccessibility.announce(context, text)
    }

    /**
     * A single audio file kept for a one-tap re-send, used by both the error-resend chip and the
     * interrupted-recording chip (unified resend path). [reason] distinguishes a failed transcription
     * (kept in cache, in-memory only) from a recording interrupted by the keyboard closing (finalized
     * and persisted to filesDir, mirrored by the `interruptedAudio*` prefs so it survives process death).
     */
    private data class RetainedAudio(
        val file: File,
        val reason: RetainReason,
        val wasLive: Boolean,
        val seconds: Long,
        /**
         * The history row this audio already has (issue #358), so the chip's resend rewrites that entry
         * instead of adding a second one beside it — the same thing the ↻ in the History panel does with
         * the same recording. Null for an interrupted recording, which was never sent and has no row.
         */
        val historyId: Long? = null,
    )

    /** The currently kept audio (failed or interrupted), or null when there is nothing to re-send. */
    private var retained: RetainedAudio? = null

    /**
     * Metadata threaded from a transcription into [finalizeAndCommit] so the finished dictation can be
     * logged to the history store (issue #140). [audioFile] is the (still-present) recorded WAV to retain
     * when audio retention is on. [isReplay] marks a re-transcription of already-counted audio so stats
     * aren't double-counted, and [replayHistoryId] (when set) updates that existing entry's text in place
     * instead of inserting a new row.
     *
     * [pendingHistoryId] is the placeholder row written when this dictation's audio was *sent* (issue
     * #358), waiting to be turned into the finished entry. It is kept apart from [replayHistoryId] even
     * though both name a row to rewrite, because the audio rule differs: a replay is an entry the user
     * already has and whose audio stays whatever it was, while a placeholder force-kept the WAV to
     * survive an answer that might never come, and has to hand it back on success unless retention is on.
     */
    private data class HistoryCapture(
        val audioFile: File?,
        /** The engine whose text this is, which is not always the one the audio was sent to (#457). */
        val engine: HistoryEngine,
        val language: String,
        val source: String,
        val isReplay: Boolean = false,
        val replayHistoryId: Long? = null,
        val pendingHistoryId: Long? = null,
    )

    /**
     * A previously captured audio segment to prepend to the next finished recording, set when the user
     * chooses to *continue* an interrupted recording (see [continueInterruptedRecording]). The new
     * segment is recorded normally and the two are merged ([AudioConcat]) before transcription. Null
     * unless a continuation is in progress.
     */
    private var carryOverAudio: File? = null

    /** Recorded seconds of [carryOverAudio], so the continued recording's total length stays correct. */
    private var carryOverSeconds = 0L

    /**
     * The recording a cloud request is currently carrying, or null when nothing is in flight (or the
     * request is already the on-device one). It is what holding the stop button hands to the local model
     * when a transcription hangs (#270) — see [cancelAndTranscribeLocal].
     */
    private var inFlightAudio: File? = null

    /**
     * What a tap on the stop button keeps of the transcription in flight (#437), shaped as the resend chip
     * wants it: the recording, whether it was a live prompt, its length, and the history row a resend has to
     * finish, i.e. the entry a replay came from or the row this request wrote when it sent the audio (#358).
     * The on-device engine's recording counts too. There is nothing to rescue it to, but a stop keeps it.
     *
     * Null while nothing would be kept. That is the case for a password or incognito field, which never
     * keeps anything (#383), and also for the first moment of a request, until it has asked the field.
     */
    private var inFlightResend: RetainedAudio? = null

    /**
     * A transcription that was cancelled but may still be unwinding. Cancelling interrupts a network wait at
     * once, but not the audio work around it (speed-up, packing, the on-device engine), which runs on to its
     * end and then deletes its scratch files. Those have fixed names, so the next request waits for it rather
     * than lose its own copies to that cleanup. A resend tapped right after a stop (#437) is the case this
     * exists for.
     */
    private var unwindingTranscribeJob: Job? = null

    /** Recorded seconds of [inFlightAudio], so a rescued dictation still counts for the right length. */
    private var inFlightSeconds = 0L

    /**
     * The history row the in-flight request has already written (issue #358), so a rescue can carry it
     * over instead of opening a second one. Without this the escape to the local model would leave the
     * abandoned cloud attempt behind as a permanent red entry beside the dictation that succeeded.
     */
    private var inFlightHistoryId: Long? = null

    /** Cache file name for the merged audio when a continued interrupted recording is stitched together. */
    private const val MERGED_AUDIO_NAME = "dictate_merged.wav"
    // Silence trimming (issue #232): cache file for the trimmed upload, plus the gap thresholds — a silence
    // gap longer than TRIM_MAX_SILENCE_MS is collapsed down to TRIM_KEEP_SILENCE_MS (a short pad on each
    // side of the cut); shorter, natural pauses are left untouched.
    private const val TRIMMED_AUDIO_NAME = "dictate_trimmed.wav"
    private const val TRIM_MAX_SILENCE_MS = 2_000
    private const val TRIM_KEEP_SILENCE_MS = 400
    /** Cache file for the sped-up upload copy (issue #272); the recording itself is never overwritten. */
    private const val SPED_UP_AUDIO_NAME = "dictate_speedup.wav"
    private const val PACKED_AUDIO_NAME = "dictate_upload.m4a"

    /**
     * From which recording size on the upload is packed into AAC (#281). 16 MiB of 16 kHz mono WAV is
     * about 8 minutes 45 seconds of speech.
     *
     * Measured on a Galaxy A55: packing runs at 73–83× realtime and shrinks the file by 3.96×, so at
     * this size it costs ~7 s and removes ~12 MB from the upload. Below it the sums invert — a 2.5 s
     * dictation spent 113 ms encoding to save 58 kB, which on a fast connection is 15 ms of upload —
     * so short dictations are left exactly as they were.
     *
     * Deliberately well under the 25 MiB that OpenAI and Groq allow, because [ProviderRegistry
     * .maxUploadBytes] returns 0 for every other provider and 0 means "unknown", not "unlimited". A
     * threshold sitting on the known limit would never rescue a provider whose real limit is lower.
     */
    private const val PACK_ABOVE_BYTES = 16L * 1024 * 1024

    /**
     * Whether a [wavBytes] upload is worth packing for a provider that allows [limitBytes] (0 =
     * unknown). Above [PACK_ABOVE_BYTES] always; below it only when the provider's own limit is close
     * enough that the WAV would otherwise be refused — three quarters of it, so the decision is made
     * before the edge rather than at it.
     */
    internal fun shouldPack(wavBytes: Long, limitBytes: Long): Boolean =
        wavBytes > PACK_ABOVE_BYTES || (limitBytes > 0L && wavBytes > limitBytes / 4 * 3)
    /** Cache file for a recording taken back from a hanging cloud request to finish on-device (#270). */
    private const val RESCUED_AUDIO_NAME = "dictate_rescued.wav"
    /** The recording handed to the fallback provider (#455), with the original's extension appended. */
    private const val FALLBACK_AUDIO_BASENAME = "dictate_fallback"
    // Realtime (#128, #372): after finish(), how long to wait for the provider to flush the last words
    // before we commit the already-streamed text. Neither bound is normally reached — the session closes
    // itself as soon as its end-of-stream marker arrives, measured at 0.27–0.37 s after `audioStreamEnd`
    // for Gemini (2026-09-14). They bound the unhealthy case, and it takes two of them because one number
    // cannot: a single fixed budget is either too short for a tail that is seconds behind the microphone
    // (a mobile uplink that could not carry the audio in real time) or a delay everyone pays on every stop.
    //
    // [REALTIME_TAIL_IDLE_MS] is how long the provider may stay *silent* before we stop expecting anything
    // more; every piece of text that arrives grants it anew, so a stream still catching up is never cut
    // mid-sentence. [REALTIME_TAIL_MAX_MS] caps the whole wait even while text keeps coming.
    private const val REALTIME_TAIL_IDLE_MS = 1_200L
    private const val REALTIME_TAIL_MAX_MS = 8_000L

    // How long a lost microphone (#411) is left to settle before the route is decided again. A single
    // disconnect arrives as a burst of removals and the audio service needs a moment to agree on what is
    // left, so re-routing on the first event would only mean re-routing again on the third.
    private const val ROUTE_SETTLE_MS = 250L

    // At most this many handovers per dictation. A route that answers every swap with more silence is
    // not going to be fixed by a fourth attempt, and repeating it would cut the recording to pieces.
    private const val MAX_ROUTE_SWAPS = 3

    /**
     * 20 Hz is responsive for a voice indicator while avoiding a display-rate UI loop. Internal because
     * the waveform (#371) draws one bar per sample and has to scroll at exactly this rate; a copy of the
     * number in the renderer would eventually drift from this one.
     */
    internal const val AUDIO_LEVEL_SAMPLE_MS = 50L

    /** Shortest gap between two wake-up pokes at the same sleeping server (#189). */
    private const val WARM_UP_THROTTLE_MS = 60_000L

    /** Cumulative recorded audio (seconds) after which the rate / donate nudges appear (roadmap 9.7/9.8). */

    /** How long the discarded mic takes to reach the bin; the bar outlives the recording by this much. */
    const val PUSH_TO_TALK_FLIGHT_MS = 950L

    /** How long the key shows a lock after latching, before dissolving into its ordinary icon. */
    const val PUSH_TO_TALK_LOCK_FLASH_MS = 600L

    /**
     * Single entry point for the mic button: starts recording, or stops and transcribes. [target]
     * selects where the finished text goes — the keyboard editor for the in-keyboard mic (default), or
     * the accessibility-injected field for the floating button (issue #88). It is latched when a fresh
     * recording starts, so the stop tap from the same source uses the same destination.
     */
    /**
     * Hold-to-record (issue #235), the walkie-talkie alternative to the tap-toggle [onMicClick]. The
     * gesture layer calls [onPushToTalkDown] on press, [onPushToTalkSlide] as the finger moves,
     * [lockPushToTalk] when it is slid far enough up, and [onPushToTalkUp] on release.
     *
     * Works for plain and real-time recordings alike — both are a start/stop pair, and real-time even
     * suits it better because text appears while the finger is still down. Long-form segmented is
     * excluded by the caller: holding a finger down for a ten-minute dictation defeats the point.
     */
    fun onPushToTalkDown(context: Context, target: OutputTarget = OutputTarget.IME) {
        // Idempotent: the gesture layer can be torn down and restarted mid-press (a recomposition with a
        // new pointerInput key), and re-entering here would otherwise restart the clock — making a long
        // hold look like a tap — and start a second recording. The window is widest with real-time on,
        // where opening the socket keeps the state Idle for longer.
        if (_pushToTalkPhase.value != PushToTalkPhase.NONE) return
        if (!canStartRecording()) return
        pttStopPending = false
        // A new hold always starts where the key is. Latching ends the gesture through lockPushToTalk,
        // which never cleared these, so the next mic was drawn at the height the last one was let go at
        // until the first movement corrected it.
        _cancelSlideProgress.value = 0f
        _lockSlideProgress.value = 0f
        setPushToTalk(phase = PushToTalkPhase.HOLDING)
        outputTarget = target
        startRecording(context)
    }

    /**
     * Reports how far the finger has slid towards the cancel target: [progress] 0..1, where 1 means it
     * reached it. Crossing it discards the recording immediately rather than waiting for the release —
     * that is what voice-message UIs do, and waiting would leave the user holding a recording they have
     * already thrown away. Returns true once the gesture is over so the caller can stop tracking.
     */
    fun onPushToTalkSlide(progress: Float): Boolean {
        if (!_pushToTalkPhase.value.isHolding) return _pushToTalkPhase.value != PushToTalkPhase.LOCKED
        _cancelSlideProgress.value = progress.coerceIn(0f, 1f)
        if (progress >= 1f) {
                        setPushToTalk(phase = PushToTalkPhase.CANCEL_ARMED, discarding = true)
            cancelRecording(keepBarForMs = PUSH_TO_TALK_FLIGHT_MS + 120L)
            return true
        }
        return false
    }

    /** Slide-down progress towards the lock, 0..1 — drives how far the lock target fills. */
    fun onPushToTalkLockSlide(progress: Float) {
        if (_pushToTalkPhase.value.isHolding) _lockSlideProgress.value = progress.coerceIn(0f, 1f)
    }

    /** Aborts a held recording outright — the gesture was taken over (bubble drag) or the system cancelled it. */
    fun cancelPushToTalk() {
        if (_pushToTalkPhase.value == PushToTalkPhase.NONE) return
        cancelRecording()
    }

    /** Latches the recording so it keeps running after the finger lifts (slide down into the lock). */
    fun lockPushToTalk() {
        if (_pushToTalkPhase.value == PushToTalkPhase.HOLDING) {
            // Latched *and* flashing in the same emission: as two flows the key was drawn once with
            // its ordinary icon in between, which is exactly the frame this replaces.
            setPushToTalk(phase = PushToTalkPhase.LOCKED, lockFlash = true)
            scope.launch {
                delay(PUSH_TO_TALK_LOCK_FLASH_MS)
                setPushToTalk(lockFlash = false)
            }
        }
    }

    private val _pushToTalkVisuals = MutableStateFlow(PushToTalkVisuals())
    /** Phase, lock confirmation and discard flight as one value — see [PushToTalkVisuals]. */
    val pushToTalkVisuals: StateFlow<PushToTalkVisuals> = _pushToTalkVisuals.asStateFlow()

    /**
     * Finger lifted: send, or — when [send] is false because the hold was too short to be a dictation —
     * silently drop it. The gesture layer decides which, since only it knows when the finger landed.
     */
    fun onPushToTalkUp(context: Context, send: Boolean) {
        val phase = _pushToTalkPhase.value
        // Locked: the recording carries on and is ended by the stop button, exactly like tap-toggle.
        if (phase == PushToTalkPhase.LOCKED || phase == PushToTalkPhase.NONE) return
        // Released on the discard target: go straight there without passing through NONE, which would be
        // one emission in which the mic is neither held nor flying — and therefore not on screen.
        if (phase == PushToTalkPhase.CANCEL_ARMED) {
            setPushToTalk(phase = PushToTalkPhase.CANCEL_ARMED, discarding = true)
            cancelRecording(keepBarForMs = PUSH_TO_TALK_FLIGHT_MS + 120L)
            return
        }
        setPushToTalk(phase = PushToTalkPhase.NONE)
        _cancelSlideProgress.value = 0f
        _lockSlideProgress.value = 0f
        // Still starting up — let the start job end it the moment the recorder exists, whichever way it is
        // to end. Cancelling the job part way instead would be taken by its own catch for a recording that
        // failed, and a release just past the tap window lands in exactly that stretch.
        if (_state.value !is UiState.Recording && startJob?.isActive == true) {
            pttStopSends = send
            pttStopPending = true
            return
        }
        // Let go after the tap window but before a dictation could have happened (#422): a slow tap or a
        // hold given up on. Neither is worth a request, and latching instead would leave the mic open for
        // someone who believes they let go of it — so nothing happens, and the next tap simply works. No
        // flight to the bin either: that is the answer to a discard the user chose, not to a press that
        // came to nothing.
        //
        // This is not the 400 ms latch that was taken out after #235. That one papered over releases
        // nobody made — Compose ended real-time holds about 100 ms in — and it kept the recording; releases
        // come from the window's own touch stream now (see DictateHoldTouch), so a short one is a short one.
        if (send && _state.value is UiState.Recording) stopAndTranscribe(context) else cancelRecording()
    }

    /**
     * True when the mic should behave as hold-to-record: the user enabled it and the upcoming recording
     * is not long-form segmented, which cannot sensibly be held down for its whole duration.
     */
    fun isPushToTalkActive(context: Context): Boolean =
        prefs.dictate.pushToTalk.get() && !isSegmentedMode(context.applicationContext)

    /**
     * True when a tap on the mic would start a fresh recording — everything except a recording already
     * running or a request in flight. Notably that includes the interrupted-recording chip (issue #111):
     * it is a resting state with an offer on it, so holding the mic there has to work exactly as it does
     * on a plain idle keyboard, which it did not while this was an `is UiState.Idle` check.
     */
    fun canStartRecording(): Boolean = when {
        discardingBar -> false
        // A start still under way already owns the next recording; see [startRecording] (#147).
        startJob?.isCompleted == false -> false
        else -> when (_state.value) {
            is UiState.Recording, is UiState.Transcribing, is UiState.Rewording -> false
            else -> true
        }
    }

    /**
     * True while the recording bar is only still on screen so a discarded mic has somewhere to land.
     * Nothing is being captured any more, so every entry point has to step aside rather than act on a
     * state that looks like a live recording but is not one.
     */
    @Volatile private var discardingBar = false

    fun onMicClick(context: Context, target: OutputTarget = OutputTarget.IME) {
        // The bar is a leftover from a discard that is still animating — acting on it would stop a
        // recording that no longer exists.
        if (discardingBar) return
        when (_state.value) {
            is UiState.Recording -> stopAndTranscribe(context)
            // Tapping the mic while transcribing or rewording aborts it (the button shows a stop icon,
            // see the ComputingEvaluator) — e.g. after accidentally sending a prompt (issue #192). A
            // transcription keeps its recording for a resend (#437).
            is UiState.Transcribing -> stopTranscription(context)
            is UiState.Rewording -> cancelCleanup()
            else -> {
                outputTarget = target
                startRecording(context)
            }
        }
    }

    /**
     * Toggles a prompt in the recording-time queue (ROW layout): while a recording/transcription is in
     * flight, tapping a prompt chip enqueues it (or removes it if already queued) instead of applying it
     * immediately. The queue is applied in tap order to the finished transcript (see [applyPendingPrompts]).
     * No-op outside the recording/transcribing states or for non-persisted prompts.
     */

    /** Toggles pause/resume of the in-progress recording. No-op outside the recording state. */
    fun togglePause() {
        val current = _state.value as? UiState.Recording ?: return
        val rec = recorder ?: return
        if (current.paused) {
            rec.resume()
            _state.value = current.copy(startedAtMs = SystemClock.elapsedRealtime(), paused = false)
        } else {
            rec.pause()
            val segment = SystemClock.elapsedRealtime() - current.startedAtMs
            _state.value = current.copy(accumulatedMs = current.accumulatedMs + segment, paused = true)
        }
    }

    /** The active dictation language (defaults to auto-detect); read live from the JetPref store. */
    fun activeLanguage(): DictateLanguage = DictateLanguages.of(prefs.dictate.activeInputLanguage.get())

    /** Advances the active language to the next entry in the user's selected subset (no-op if ≤1). */
    fun cycleLanguage() {
        val selection = DictateLanguages.parseSelection(prefs.dictate.inputLanguages.get())
        if (selection.size <= 1) return
        val currentCode = prefs.dictate.activeInputLanguage.get()
        val idx = selection.indexOfFirst { it.code == currentCode }
        val next = selection[(idx + 1) % selection.size] // idx == -1 (unknown) → starts at index 0
        scope.launch { prefs.dictate.activeInputLanguage.set(next.code) }
    }

    /** Sets the active dictation language explicitly (from the recording bar's language picker). */
    fun setLanguage(code: String) {
        scope.launch { prefs.dictate.activeInputLanguage.set(code) }
    }

    /**
     * Makes [id] the active transcription provider, from the keyboard's own picker (issue #431). On this
     * scope rather than the panel's, which leaves composition the moment the choice closes it.
     */
    fun setTranscriptionProvider(id: String) {
        scope.launch { prefs.dictate.transcriptionProviderId.set(id) }
    }

    /**
     * The keyboard's language was just switched from [previous] to [locale] — or, with no [previous], the
     * setting that follows it was just turned on (issue #431). With [prefs.dictate.languageFollowsKeyboard]
     * on, dictation follows, if the language is one the user dictates in ([DictateLanguages.forKeyboard]).
     *
     * Called from the switch itself and never from a subtype flow: that flow also answers when the keyboard
     * starts, first with the default subtype and then with the stored one, and following *that* would undo
     * a hand-picked language every time the process comes back. For the same reason a switch that lands on
     * the same language — the only subtype, or a second layout for it — changes nothing. Mid-dictation it
     * behaves like the language chip on the recording bar: a batch dictation is sent in the new language, a
     * live one keeps its own.
     */
    fun followKeyboardLanguage(locale: Locale, previous: Locale? = null) {
        if (!prefs.dictate.languageFollowsKeyboard.get()) return
        val selectionRaw = prefs.dictate.inputLanguages.get()
        val match = DictateLanguages.forKeyboard(locale, selectionRaw) ?: return
        // Compared as the dictation language each side implies, not as locales: Hindi's varnamala and
        // transliteration layouts carry different tags and are still one spoken language.
        if (previous != null && DictateLanguages.forKeyboard(previous, selectionRaw) == match) return
        if (match.code != prefs.dictate.activeInputLanguage.get()) setLanguage(match.code)
    }

    /**
     * The languages to hand to a model whose language field takes a *list* (OpenAI's gpt-transcribe
     * generation, Soniox, Gemini) — the user's own selection while auto-detect is active, nothing
     * otherwise. See [DictateLanguages.expectedLanguages] (issue #99).
     */
    private fun expectedLanguages(): List<String> = DictateLanguages.expectedLanguages(
        activeCode = prefs.dictate.activeInputLanguage.get(),
        selectionRaw = prefs.dictate.inputLanguages.get(),
    )

    /**
     * Snaps [activeInputLanguage] back into the current [inputLanguages] selection. A stale active
     * code — most importantly "detect" left over after the user disabled auto-detect — would otherwise
     * keep the transcription request on auto-detect (language = null, so e.g. Portuguese gets detected
     * as English) and show a phantom globe on the recording bar. Falls back to the first selected
     * language and persists the correction. No-op when the active code is already selected.
     */
    private suspend fun reconcileActiveLanguage() {
        val selection = DictateLanguages.parseSelection(prefs.dictate.inputLanguages.get())
        if (selection.isEmpty()) return
        val current = prefs.dictate.activeInputLanguage.get()
        if (selection.none { it.code == current }) {
            prefs.dictate.activeInputLanguage.set(selection.first().code)
        }
    }

    /**
     * Reloads the prompt list from the shared `prompts.db` into [prompts]. Cheap and idempotent;
     * called when the keyboard (re-)appears so the chip strip reflects edits made in the settings.
     */

    /** Clears a transient error back to idle (the Smartbar UI calls this after showing it briefly). */
    fun clearError() {
        if (_state.value is UiState.Error) _state.value = UiState.Idle
    }

    /**
     * Opens the Dictate provider settings from the keyboard, used by the "fixable" errors (e.g. an
     * invalid or missing API key, roadmap 1.12). Launched as a new task since an IME has no activity of
     * its own; clears the error afterwards so the Smartbar returns to normal. [addNew] lands on the
     * add-a-provider list instead, for the keyboard's provider picker (issue #431).
     */
    fun openProviderSettings(context: Context, addNew: Boolean = false) {
        val route = if (addNew) "settings/dictate/providers/add" else "settings/dictate/providers"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/$route"))
                    // BROWSABLE is required: FlorisAppActivity.onNewIntent only routes a VIEW intent to the
                    // nav-graph deep-link handler when it carries this category, otherwise it treats the
                    // intent as an extension-import and lands on the wrong screen.
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        clearError()
    }

    /**
     * Opens the Dictate Cloud screen from the keyboard so credit can be topped up without losing the
     * place in whatever was being written. Same new-task launch as [openProviderSettings].
     */
    fun openCloudSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/dictate/cloud"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        clearError()
    }

    /**
     * Which half of a dictation a failure came from. The messages have to name the right one: a rewording
     * that died used to report "Unknown error during transcription", which cost a reporter the time to
     * rule out his key, his model and his reasoning setting before writing in (issue #284).
     */
    private enum class Stage { TRANSCRIPTION, REWORDING }

    /**
     * The stage a failure belongs to, read from what was running when it was thrown: only the code that
     * starts a rewording sets [UiState.Rewording], so the state *is* the record of which half was busy.
     * Deliberately not a field of its own — one left set by a previous dictation would mislabel the next.
     */
    private fun stageOf(state: UiState): Stage =
        if (state is UiState.Rewording) Stage.REWORDING else Stage.TRANSCRIPTION

    /**
     * Enters (or updates) [UiState.Transcribing], keeping the clock that the wait actually started with
     * (issue #355).
     *
     * A retry and the handover to the on-device fallback both publish a fresh state object, but neither
     * is a fresh wait — the person watching has been waiting since the recording stopped, and that total
     * is the number worth showing. So the stamp is inherited whenever we are already transcribing, and
     * only a transition from another state (Recording, Idle) starts a new one. A hand-over to a fallback
     * provider (#455) is the same wait continued, and keeps the stamp the same way.
     */
    private fun setTranscribing(attempt: Int = 1, onDevice: Boolean = false, via: String? = null) {
        val startedAtMs = (_state.value as? UiState.Transcribing)?.startedAtMs
            ?: SystemClock.elapsedRealtime()
        _state.value = UiState.Transcribing(attempt, onDevice, startedAtMs, via)
    }

    /** Localized one-line headline for an API error [kind] (roadmap 1.12 specific error messages). */
    private fun errorMessageRes(kind: DictateApiException.Kind, stage: Stage): Int = when (kind) {
        DictateApiException.Kind.INVALID_API_KEY -> R.string.dictate__error_invalid_api_key
        DictateApiException.Kind.QUOTA_EXCEEDED -> R.string.dictate__error_quota_exceeded
        DictateApiException.Kind.CONTENT_SIZE_LIMIT -> R.string.dictate__error_content_size_limit
        DictateApiException.Kind.TEXT_SIZE_LIMIT -> R.string.dictate__error_text_size_limit
        DictateApiException.Kind.FORMAT_NOT_SUPPORTED -> R.string.dictate__error_format_not_supported
        DictateApiException.Kind.TIMEOUT -> R.string.dictate__error_timeout
        DictateApiException.Kind.NETWORK -> R.string.dictate__error_network
        DictateApiException.Kind.SERVER_ERROR -> R.string.dictate__error_server
        // Only the catch-all needs to know the stage. Every other kind either reads correctly for both
        // ("Check your API key", "Request timed out") or can only happen in one of them at all — an
        // over-long recording is never a rewording, a text too long to reword is never a transcription.
        DictateApiException.Kind.UNKNOWN -> when (stage) {
            Stage.TRANSCRIPTION -> R.string.dictate__error_unknown
            Stage.REWORDING -> R.string.dictate__cleanup_failed
        }
    }

    /**
     * Builds an [UiState.Error] from an API exception: a localized headline (per [DictateApiException.Kind]),
     * the raw provider text kept as the tappable detail, and the contextual action — resend the kept audio
     * for retryable failures, open settings for a bad/missing key, otherwise none.
     */
    /** Failures where resending is pointless but the recording is worth saving instead (issue #144). */
    private val EXPORTABLE_ERROR_KINDS = setOf(
        DictateApiException.Kind.CONTENT_SIZE_LIMIT,
        DictateApiException.Kind.FORMAT_NOT_SUPPORTED,
    )

    /**
     * Whether a failed *transcription* should be answered with "the on-device engine needs no network"
     * (issue #262).
     *
     * The reporter there is in mainland China, where every cloud provider is blocked and the app said
     * nothing more useful than "request timed out" — while the one path that does work was sitting in
     * the settings, undiscovered. Rather than guessing at censorship from the device region, this asks a
     * question that is worth answering in every case where it is true: the recording did not reach a
     * provider, and this phone is not set up to fall back to transcribing it itself. Whether the cause
     * is a firewall, a tunnel or an airplane, the advice is the same and it is correct.
     *
     * False once the fallback is armed *and* a model is installed, because then it already took over
     * silently and the failure the user is looking at is something else.
     */
    private fun shouldSuggestOnDevice(
        context: Context,
        kind: DictateApiException.Kind,
        preset: ProviderPreset,
    ): Boolean {
        return false
    }

    /**
     * A sentence saying where the failing model came from, when the provider complained about a model
     * the user never chose (issue #313).
     *
     * An empty stored model means "follow the preset", which is what lets an app update move a provider
     * off a retired model — but when nobody moves it, the user is shown a provider error naming a model
     * they have never seen in their settings. That reads as the app sending the wrong thing, and it was
     * reported as exactly that, at length.
     *
     * Deliberately narrow. It is attached only when the provider's own text mentions the very model that
     * was sent, so an unrelated failure never grows advice about model settings, and only when nothing in
     * the account chose that model — a model the user picked needs no explanation of where it came from.
     */
    private fun defaultModelNote(context: Context, stage: Stage, providerText: String?): String? {
        if (stage != Stage.REWORDING || providerText.isNullOrBlank()) return null
        val account = rewordingAccount()
        val preset = presetFor(account)
        if (!chatModelIsPresetDefault(account, preset)) return null
        val model = chatModelFor(account, preset)
        if (model.isBlank() || !providerText.contains(model)) return null
        return context.getString(
            R.string.dictate__error_rewording_default_model, model, preset.displayName,
        )
    }

    private fun apiError(
        e: DictateApiException,
        context: Context,
        canResend: Boolean,
        suggestOnDevice: Boolean = false,
        stage: Stage = Stage.TRANSCRIPTION,
        // True when the local engine did the work (#354). Only a timeout reads differently for it:
        // "Request timed out" is a sentence about a server, and nothing was requested from one.
        onDevice: Boolean = false,
    ): UiState.Error {
        val action = when {
            canResend && e.kind in EXPORTABLE_ERROR_KINDS -> ErrorAction.SAVE_AUDIO
            canResend && e.kind.isRetryable -> ErrorAction.RESEND
            e.kind == DictateApiException.Kind.INVALID_API_KEY -> ErrorAction.OPEN_SETTINGS
            else -> ErrorAction.NONE
        }
        // The chip fits one short line, so the hint replaces the headline rather than being appended to
        // it — "try on-device" is more use than repeating that there is no connection. The full sentence
        // goes into the detail popup, where there is room and where the raw provider text for a network
        // failure ("timeout") is next to useless on its own.
        val hint = suggestOnDevice
        val detail = listOfNotNull(
            e.message?.takeIf { it.isNotBlank() },
            defaultModelNote(context, stage, e.message),
        ).joinToString("\n\n").takeIf { it.isNotBlank() }
        return UiState.Error(
            message = when {
                hint -> context.getString(R.string.dictate__error_try_on_device)
                onDevice && e.kind == DictateApiException.Kind.TIMEOUT ->
                    context.getString(R.string.dictate__error_timeout_on_device)
                else -> context.getString(errorMessageRes(e.kind, stage))
            },
            kind = e.kind,
            action = action,
            detail = if (hint) {
                listOfNotNull(detail, context.getString(R.string.dictate__error_try_on_device_detail))
                    .joinToString("\n\n")
            } else {
                detail
            },
        )
    }

    /**
     * Declines the kept audio (whether from a failed transcription or an interrupted recording): drops
     * the audio and returns the Smartbar to idle. Shared dismiss (✗) for both resend chips.
     */
    fun dismissRetainedAudio() {
        discardRetainedAudio()
        if (_state.value is UiState.Error || _state.value is UiState.Interrupted) {
            _state.value = UiState.Idle
        }
    }

    /**
     * System voice input entry points (issue #67), driven by [DictateRecognitionService]. They record via
     * the normal pipeline but latch [OutputTarget.RECOGNITION_SERVICE], so the finished text is handed back
     * to the calling app through the recognition callback instead of being written into a field. Always
     * plain batch (no realtime/segmented) — see [openRealtimeSession] / [isSegmentedMode].
     *
     * [sensitiveField] keeps the dictation out of the history (#383). Only the voice IME can say so, because
     * it is the one entry point that sees the caller's field.
     */
    fun startRecognition(context: Context, sensitiveField: Boolean = false) {
        // Busy with another dictation → ignore; the service will time out and report an error.
        if (_state.value is UiState.Recording ||
            _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        outputTarget = OutputTarget.RECOGNITION_SERVICE
        recognitionFieldSensitive = sensitiveField
        startRecording(context)
    }

    /**
     * Whether the field the current voice-input request is for must stay out of the history: set by
     * [startRecognition], kept for a resend of the same request.
     */
    @Volatile
    private var recognitionFieldSensitive = false

    /** Stops the recognition recording and transcribes it; the result flows to the recognition callback. */
    fun stopRecognition(context: Context) {
        if (_state.value is UiState.Recording) stopAndTranscribe(context)
    }

    /** Aborts a recognition recording without transcribing (the caller cancelled). */
    fun cancelRecognition() {
        // A resend (#409) has no recording left to stop — what is in flight is the request itself, and
        // leaving the voice-input view has to take it with it rather than pay for an answer nobody is
        // registered to receive any more (issue #192's rule). Both calls no-op outside their own state.
        cancelTranscription()
        cancelRecording()
    }

    /**
     * Re-sends the audio kept from a failed voice-input dictation (issue #409), so the minimal voice-input
     * view can offer the same one-tap retry the keyboard's error chip has. The output latch is re-claimed
     * first: the failure left it on [OutputTarget.RECOGNITION_SERVICE], but a keyboard action in between
     * may have moved it, and the retried transcript has to reach the caller through the bridge like the
     * first attempt did. Returns whether a resend was started.
     */
    fun resendRecognition(context: Context): Boolean {
        if (!hasRetainedAudio()) return false
        sendRetainedAudio(context, OutputTarget.RECOGNITION_SERVICE)
        return true
    }

    /** Whether a usable recording is being kept for a one-tap resend (drives the retry affordances). */
    fun hasRetainedAudio(): Boolean = retained?.file?.let { it.exists() && it.length() > 0L } == true

    /** Aborts an in-progress recording and returns to idle (cancel button / leaving the keyboard). */
    fun cancelRecording(keepBarForMs: Long = 0L) {
        pttStopPending = false
        // A discard in flight keeps its flag; any other teardown clears everything.
        setPushToTalk(
            phase = PushToTalkPhase.NONE,
            lockFlash = false,
            discarding = keepBarForMs > 0L,
        )
        _cancelSlideProgress.value = 0f
        _lockSlideProgress.value = 0f
        // Cancelled, not forgotten: until the job has finished unwinding it still counts as a start under
        // way, and [startRecording] waits for it (#147).
        startJob?.cancel()
        recorder?.cancel()
        recorder = null
        // Long-form segmented (#170): abort the background segment transcriptions; the realtime cleanup
        // below removes the progressively-shown preview text (segmented reuses realtimeShown/Context).
        if (segmentedActive) {
            segmentJobs.forEach { it.cancel() }
            segmentJobs.clear()
            segmentAudioFiles.values.forEach { runCatching { it.delete() } }
            resetSegmentedState()
        }
        // Tear down any realtime stream (#128) and remove the live provisional text from the field. Set the
        // cancelled flag first so any stream callback still queued on the main thread can't re-add the text.
        realtimeCancelled = true
        realtimeSession?.cancel()
        realtimeSession = null
        realtimeClosed = null
        _interimText.value = ""
        realtimeContext?.let { ctx -> runCatching { sink(ctx).clearDictationPreview(realtimeShown.toString()) } }
        realtimeShown.setLength(0)
        realtimeTranscript.setLength(0)
        realtimeContext = null
        unregisterScreenOffReceiver()
        cleanupAudioRouting()
        // Cancelling a continued recording also throws away the carried-over interrupted segment.
        discardCarryOver()
        if (_state.value is UiState.Recording) {
            if (keepBarForMs > 0L) {
                // Capture has already stopped; only the bar stays, so the mic being thrown has a bin to
                // land in. Dropping straight to Idle made the target vanish mid-flight.
                discardingBar = true
                scope.launch {
                    delay(keepBarForMs)
                    discardingBar = false
                    setPushToTalk(discarding = false)
                    if (_state.value is UiState.Recording) _state.value = UiState.Idle
                }
            } else {
                discardingBar = false
                setPushToTalk(discarding = false)
                _state.value = UiState.Idle
            }
        }
    }

    /** Kept for the legacy in-keyboard panel; identical to [cancelRecording]. */
    fun abortRecording() = cancelRecording()

    /**
     * Starts listening for [Intent.ACTION_SCREEN_OFF] while a recording is in progress (issue #147). When
     * the screen turns off we treat it exactly like the keyboard being hidden ([stashRecording]): the
     * audio is finalized and kept, and the mic is released. This is the dependable catch-all for
     * recordings that would otherwise be orphaned when no IME teardown callback is delivered (abrupt app
     * switch, IME switch, lock). It never fires while the screen is on, so long recordings are unaffected.
     *
     * Unlike the keyboard's own teardown this applies to *every* dictation, whoever owns it: a dark screen
     * means the user has left, and that is as true for the floating button as for the keyboard (#293).
     */
    private fun registerScreenOffReceiver(appContext: Context) {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    stashRecording(appContext)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registered) {
            screenOffReceiver = receiver
            screenOffContext = appContext
        }
    }

    /** Stops listening for screen-off. Called from every path that stops a recording. Idempotent. */
    private fun unregisterScreenOffReceiver() {
        val receiver = screenOffReceiver ?: return
        val ctx = screenOffContext
        screenOffReceiver = null
        screenOffContext = null
        if (ctx != null) runCatching { ctx.unregisterReceiver(receiver) }
    }

    /**
     * Abandons an in-flight transcription and returns to idle, without keeping its recording: for callers
     * that hand the recording on themselves ([cancelAndTranscribeLocal]) or have nobody left to give it to
     * ([cancelRecognition]). The stop button is [stopTranscription]. The audio is dropped in the job's
     * finally, but the history row written when it was sent (#358) stays. No-op outside the transcribing
     * state, so this can never interrupt a rewording request.
     */
    fun cancelTranscription() {
        if (_state.value !is UiState.Transcribing) return
        // A stopped long-form dictation has no transcribeJob to cancel; its work is the segment jobs.
        val segmentContext = realtimeContext
        if (segmentedActive && segmentContext != null) {
            abandonPendingSegments(segmentContext)
            return
        }
        abandonTranscribeJob()
        _state.value = UiState.Idle
    }

    /** Cancels the transcription job and forgets what it was carrying. Leaves the state to the caller. */
    private fun abandonTranscribeJob() {
        transcribeJob?.cancel()
        unwindingTranscribeJob = transcribeJob
        transcribeJob = null
        // Cleared here, not left to the job's finally: that runs only once the job has unwound, and by then a
        // request started in the meantime may have set them for itself.
        inFlightAudio = null
        inFlightHistoryId = null
        inFlightResend = null
    }

    /**
     * The stop button while a transcription is in flight (#437). The request is given up, the recording is
     * not: it stays on a chip that sends it again, the same resend chip a failure gets, and with the same
     * history row behind it, so a resend finishes that entry instead of adding a second one.
     *
     * Stopping used to end in plain idle. The row written when the audio was sent (#358) did survive, but
     * nothing said so, and with the history off nothing survived at all. A stop looked exactly like a
     * discard, and someone who stopped a request that hung through a phone call took it for one.
     *
     * The recording is not kept when the field is a password or incognito field, which never keeps anything
     * (#383), or when the resend button is switched off. That switch hides the chip, so with it off a
     * recording that is in the history gets a notice pointing there, and one that is not is dropped as before.
     */
    fun stopTranscription(context: Context) {
        if (_state.value !is UiState.Transcribing) return
        if (segmentedActive && realtimeContext != null) {
            cancelTranscription()
            return
        }
        // Read before the cancel, which clears it.
        val resend = inFlightResend?.takeIf { it.file.exists() && it.file.length() > 0L }
        val kept = resend?.takeIf { prefs.dictate.resendButton.get() }
        // Claimed before the cancel. The job's finally deletes its recording unless the resend chip holds it,
        // and this is what makes the chip hold it. A rename or a copy would do the same, but a copy of a long
        // dictation is tens of megabytes on the main thread.
        if (kept != null) {
            if (retained?.file != kept.file) discardRetainedAudio()
            retained = kept
        }
        abandonTranscribeJob()
        // Straight from Transcribing to where the stop ends, never through Idle. An observer that sees
        // Transcribing → Idle reads a finished dictation: the floating button flashed its green check and then
        // no longer counted the chip as its own (seen on the emulator). The success buzz listens for the same
        // transition.
        val appContext = context.applicationContext
        _state.value = when {
            kept != null -> UiState.Error(
                message = appContext.getString(R.string.dictate__transcription_stopped),
                action = ErrorAction.RESEND,
                // The user's own decision, not a failure: themed like "no speech detected", not red.
                neutral = true,
            )
            resend?.historyId != null -> UiState.Error(
                message = appContext.getString(R.string.dictate__transcription_stopped_in_history),
                neutral = true,
            )
            else -> UiState.Idle
        }
    }

    /**
     * Aborts an in-flight rewording (the stop button shown on the mic while rewording, issue #192).
     * Covers both a manually applied prompt ([rewordJob]) and the post-transcription / live-prompt
     * rewording chain that runs inside [transcribeJob]. Cancelling before the model answer is committed
     * leaves the field untouched — for a "reword the selection" prompt the original text stays selected
     * and intact. No-op outside the rewording state.
     */
    fun cancelCleanup() {
        if (_state.value !is UiState.Rewording) return
        transcribeJob?.cancel()
        transcribeJob = null
        _state.value = UiState.Idle
    }

    /**
     * Starts a recording. [seedAccumulatedMs] pre-fills the elapsed timer (and the credited length) with
     * already-captured audio when continuing an interrupted recording, so the bar shows the running
     * total; it is 0 for a normal recording.
     */
    private fun startRecording(context: Context, seedAccumulatedMs: Long = 0L) {
        if (_state.value is UiState.Recording) return
        // The state only turns to Recording at the very end of [startJob], after audio focus, Bluetooth SCO
        // and the realtime socket. A second start inside that window (a second tap on a mic that has not
        // visibly reacted yet, the bubble and the keyboard at once) used to launch a second job, and both
        // opened a microphone: the later one took [recorder], and the earlier one was left with nothing that
        // would ever stop it. It captured until the process died, into the same cache file (#147). A job
        // that was cancelled but is still unwinding counts as well, because its cleanup would otherwise reach
        // the recorder of the start that followed it.
        if (startJob?.isCompleted == false) return
        val appContext = context.applicationContext
        // Before the credential check, not after: a missing key on the very first dictation is an error
        // a screen reader has to speak (#159), and the observer only hears what happens once it runs.
        ensureHapticObserver(appContext)
        // Measured from here to the recorder running and to its first frame (#447): whatever is said
        // before that frame is not in the recording at all.
        val startRequestedAt = SystemClock.elapsedRealtime()
        // Starting a fresh recording supersedes any kept audio (a failed retry or an interrupted
        // recording the user chose not to send), so drop it instead of leaving a stale offer behind.
        // A continuation keeps its carry-over (seeded above), so only drop it for a normal start.
        if (seedAccumulatedMs == 0L) {
            discardRetainedAudio()
            discardCarryOver()
        }
        // A server that has to be woken (#189) gets the length of this dictation to do it in: the
        // transcription server whenever its own switch is on, the rewording server when a rewording
        // will follow.
        startJob = scope.launch {
            try {
                // Correct any stale active language (e.g. leftover "detect" after auto-detect was
                // disabled) before the realtime session / request reads it.
                reconcileActiveLanguage()
                // Preloading continues in the background. Never hold the microphone closed for it: the
                // first tap must start recording immediately, and transcription can wait for the model
                // only if the preload has not finished by the time the user stops.
                requestAudioFocusIfEnabled(appContext)
                val audioSource = setupBluetoothIfEnabled(appContext)
                // Long-form segmented dictation (#170): transcribe cut segments in the background while
                // recording continues. Off for realtime / live-prompt / system voice input / multimodal (see the gate).
                val segmented = isSegmentedMode(appContext)
                // Auto-split: Silero VAD finds candidate pauses, then Smart Turn v3 decides whether the
                // thought is complete; the configured pause remains the Pipecat-style safety fallback.
                segmentVad?.release()
                segmentVad = if (segmented && prefs.dictate.longformMode.get() == DictateLongformMode.AUTO) {
                    LiveSpeechSplitter(
                        appContext,
                        prefs.dictate.longformAutoSplitSeconds.get() * 1000,
                        useSmartTurn = prefs.dictate.smartTurnEnabled.get() &&
                            SmartTurnModel.isModelAvailable(appContext),
                    ) { flushSegment(appContext, splitterAlreadyReset = true) }.also { it.start() }
                } else null
                // The mic PCM tap: the realtime session (batch mode), the VAD splitter (auto-split), or none.
                val pcmSink: ((ByteArray, Int) -> Unit)? = when {
                    // Off the main thread: opening the stream builds an HTTP client and a WebSocket, and
                    // doing that inline stalled the UI thread long enough for Android to cancel the
                    // in-flight touch — which killed push-to-talk ~90 ms into a hold (#235).
                    !segmented -> withContext(Dispatchers.IO) { openRealtimeSession(appContext) }
                    segmentVad != null -> { val v = segmentVad!!; { pcm, len -> v.feed(pcm, len) } }
                    else -> null
                }
                routeSwaps = 0
                // Nothing should be left here by now. If anything ever is, it is a microphone that nobody
                // would stop again once it is overwritten, so it is stopped instead (#147).
                recorder?.let { stale ->
                    Log.w(LATENCY_LOG_TAG, "phase=staleRecorder released")
                    stale.cancel()
                }
                recorder = RecordingController(appContext).also {
                    it.start(audioSource, pcmSink, onCaptureLost = { reason ->
                        onCaptureRouteLost(appContext, reason)
                    }, startRequestedAt = startRequestedAt)
                }
                Log.i(
                    LATENCY_LOG_TAG,
                    "phase=captureStarted phaseMs=${SystemClock.elapsedRealtime() - startRequestedAt} " +
                        "realtime=${realtimeSession != null} segmented=$segmented",
                )
                if (prefs.dictate.skipSilentRecordings.get()) {
                    // Hide the one-time native VAD/session setup behind the user's recording time.
                    scope.launch { SpeechGate.prewarm(appContext) }
                }
                _state.value = UiState.Recording(SystemClock.elapsedRealtime(), accumulatedMs = seedAccumulatedMs)
                startAudioLevelSampling()
                if (segmented) initSegmented(appContext)
                registerScreenOffReceiver(appContext)
                registerInputDeviceWatch(appContext)
                // Push-to-talk (#235): the finger came back up while this job was still acquiring audio
                // focus / Bluetooth SCO. Now that a recorder exists, honour that release.
                if (pttStopPending) {
                    pttStopPending = false
                    if (pttStopSends) stopAndTranscribe(appContext) else cancelRecording()
                }
            } catch (t: Throwable) {
                // Whatever this start had already opened goes with it. Only nulling [recorder] left the
                // microphone running whenever something after `start` threw, and a realtime socket opened
                // ahead of a busy microphone stayed connected with nothing to send (#147).
                recorder?.cancel()
                recorder = null
                realtimeCancelled = true
                realtimeSession?.cancel()
                realtimeSession = null
                realtimeClosed = null
                segmentVad?.release()
                segmentVad = null
                cleanupAudioRouting()
                // Cancelled by [cancelRecording] rather than failed: the user ended the start, and that is
                // not a failure to report back to them.
                if (!isActive) return@launch
                _state.value = UiState.Error(
                    // Most common cause is the missing RECORD_AUDIO permission (granted in onboarding).
                    appContext.getString(R.string.dictate__error_recording_failed, t.message ?: ""),
                )
            }
        }
    }

    /** Samples the recorder once for all UI consumers; the job ends itself with the recording state. */
    private fun startAudioLevelSampling() {
        audioLevelJob?.cancel()
        val smoother = AudioLevelSmoother()
        audioLevelJob = scope.launch {
            while (_state.value is UiState.Recording) {
                val recording = _state.value as UiState.Recording
                _audioLevel.value = if (recording.paused) {
                    smoother.reset()
                } else {
                    smoother.update(recorder?.maxAmplitude() ?: 0)
                }
                _audioPeak.tryEmit(smoother.peak)
                delay(AUDIO_LEVEL_SAMPLE_MS)
            }
            _audioLevel.value = smoother.reset()
            _audioPeak.tryEmit(smoother.peak)
        }
    }

    /**
     * Long-press "send with the local model" (#228): stop the current plain recording and transcribe it
     * on-device instead of the configured cloud provider. No-op unless a plain recording is active — in
     * long-form / realtime there is no plain send button to hold, so the shortcut doesn't apply.
     */
    fun stopAndTranscribeLocal(context: Context) {
        if (!canLongPressSendLocal()) return
        stopAndTranscribe(context, forceLocal = true)
    }

    /**
     * True while a plain (non-segmented, non-realtime) recording is in progress — the state where the mic
     * doubles as a "send" button, so its long-press can force a local-model transcription (#228). If no
     * on-device model is downloaded yet, the shortcut still fires and [transcribe] surfaces the "model not
     * installed → open settings" feedback (it never crashes), which is friendlier than silently ignoring.
     */
    fun canLongPressSendLocal(): Boolean =
        _state.value is UiState.Recording && !segmentedActive && realtimeSession == null

    /**
     * True while a cloud transcription is in flight that could still be handed to the on-device model
     * (issue #270): the request is waiting on a network we cannot hurry, and its recording is still here.
     *
     * The stop button's ordinary tap gives up on the request and keeps the recording for later (#437). This
     * is the same button, held, finishing the dictation here and now instead.
     */
    fun canCancelToLocalModel(): Boolean =
        _state.value is UiState.Transcribing && inFlightAudio != null

    /** Whether holding the Dictate button right now would run the on-device model (#228 or #270). */
    fun canLongPressLocal(): Boolean = canLongPressSendLocal() || canCancelToLocalModel()

    /**
     * What holding the Dictate button does when the on-device shortcut is enabled: send this recording to
     * the local model instead of the cloud ([stopAndTranscribeLocal], #228), or take a cloud request that
     * is still running away from it ([cancelAndTranscribeLocal], #270). One entry point, so the keyboard
     * and the floating button cannot end up disagreeing about which of the two applies.
     */
    fun holdForLocalModel(context: Context) {
        when {
            canLongPressSendLocal() -> stopAndTranscribeLocal(context)
            canCancelToLocalModel() -> cancelAndTranscribeLocal(context)
        }
    }

    /**
     * Aborts the in-flight cloud transcription and transcribes the same recording on this device (#270).
     *
     * The audio is copied first: cancelling the job runs its `finally`, which deletes the file it was
     * uploading. Copying is a few hundred kilobytes and removes the race entirely — no flag the cancelled
     * coroutine has to notice, no ordering to get right.
     */
    fun cancelAndTranscribeLocal(context: Context) {
        val audio = inFlightAudio?.takeIf { it.exists() && it.length() > 0L } ?: return
        if (_state.value !is UiState.Transcribing) return
        val seconds = inFlightSeconds
        // Read before the cancel, whose teardown clears it: this is the same dictation continuing on a
        // different engine, so it keeps the history row the cloud attempt opened (#358) rather than
        // stranding it as a failure next to the entry that is about to succeed.
        val handedOverHistoryId = inFlightHistoryId
        val rescued = File(context.applicationContext.cacheDir, RESCUED_AUDIO_NAME)
        runCatching { audio.copyTo(rescued, overwrite = true) }.getOrElse { return }
        cancelTranscription()
        // gate=false: this recording has already passed the silence gate once — running it again would
        // only spend the time twice, and a second opinion on the same audio is not the point here.
        transcribe(
            context, rescued, seconds, gate = false, forceLocal = true,
            adoptHistoryId = handedOverHistoryId,
        )
    }

    private fun stopAndTranscribe(context: Context, forceLocal: Boolean = false) {
        setPushToTalk(phase = PushToTalkPhase.NONE)
        // Long-form segmented (#170): finish the segment queue instead of uploading one big file.
        if (segmentedActive) {
            stopSegmentedAndFinalize(context)
            return
        }
        // Real-time recording (#128): finalize the stream instead of uploading the whole file.
        if (realtimeSession != null) {
            stopRealtimeAndFinalize(context)
            return
        }
        val latencyTrace = BatchLatencyTrace()
        logLatency(latencyTrace, "stopTapped")
        val activeRecorder = recorder
        recorder = null
        unregisterScreenOffReceiver()
        // Capture the recorded length before leaving the Recording state, to credit the usage counter
        // that gates the rate/donate nudges (roadmap 9.7/9.8). Includes any carried-over seconds.
        val recordedSeconds = recordedSecondsOf(_state.value)
        val recorderStopStartedNanos = SystemClock.elapsedRealtimeNanos()
        val audioFile = activeRecorder?.stop()
        logLatency(latencyTrace, "recorderStopped", recorderStopStartedNanos)
        val routingCleanupStartedNanos = SystemClock.elapsedRealtimeNanos()
        cleanupAudioRouting()
        logLatency(latencyTrace, "audioRoutingCleaned", routingCleanupStartedNanos)
        val carry = carryOverAudio
        carryOverAudio = null
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
            // The new segment is unusable. If we were continuing an interrupted recording, fall back to
            // transcribing the carried-over segment alone rather than losing it.
            if (carry != null && carry.exists() && carry.length() > 0L) {
                scope.launch { clearInterruptedAudioPref() }
                transcribe(context, carry, carryOverSeconds, forceLocal = forceLocal, latencyTrace = latencyTrace)
            } else {
                carry?.delete()
                _state.value = UiState.Error(context.getString(R.string.dictate__error_no_audio))
            }
            return
        }
        if (carry == null) {
            transcribe(context, audioFile, recordedSeconds, forceLocal = forceLocal, latencyTrace = latencyTrace)
            return
        }
        // Continuation: stitch the carried-over segment and the new one into a single audio so the whole
        // dictation is transcribed as one. The interrupted marker was already claimed when continuing.
        scope.launch { clearInterruptedAudioPref() }
        val merged = File(context.applicationContext.cacheDir, MERGED_AUDIO_NAME)
        val ok = AudioConcat.concat(listOf(carry, audioFile), merged)
        carry.delete()
        if (ok && merged.exists() && merged.length() > 0L) {
            audioFile.delete()
            transcribe(context, merged, recordedSeconds, forceLocal = forceLocal, latencyTrace = latencyTrace)
        } else {
            // Merge failed (rare): transcribe at least the newly recorded segment.
            merged.delete()
            transcribe(context, audioFile, recordedSeconds, forceLocal = forceLocal, latencyTrace = latencyTrace)
        }
    }

    /** Elapsed recorded seconds of a [UiState.Recording] (running + accumulated), else 0. */
    private fun recordedSecondsOf(state: UiState): Long {
        val rec = state as? UiState.Recording ?: return 0L
        val running = if (rec.paused) 0L else SystemClock.elapsedRealtime() - rec.startedAtMs
        return ((rec.accumulatedMs + running) / 1000L).coerceAtLeast(0L)
    }

    /**
     * Long-press entry point for the mic: hands off to [FileTranscriptionActivity] so the user can
     * pick an existing audio/video file to transcribe instead of recording. The activity stashes the
     * picked file and a pref; [consumePendingFileTranscription] finishes the job once the keyboard
     * regains focus. No-op unless we are idle.
     */
    fun startFileTranscription(context: Context) {
        if (_state.value !is UiState.Idle) return
        val intent = Intent(context, FileTranscriptionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Cache directory where [FileTranscriptionActivity] drops a picked file for the IME to pick up.
     * A dedicated directory keeps the handoff file-based (survives the IME process being killed while
     * the file picker is foreground) and unambiguous.
     */
    fun pendingTranscriptionDir(context: Context): File = File(context.cacheDir, "dictate_pending")

    /**
     * Called by the IME when the keyboard (re-)appears on a field: if [FileTranscriptionActivity]
     * stashed a picked file, transcribe it now and commit into the focused field. Returns true if a
     * transcription was started, so the caller can skip instant-recording.
     *
     * Safe to call from multiple lifecycle hooks: the pending file is *claimed* (moved out of the
     * pending dir) before transcription starts, so a second call finds nothing and is a no-op.
     */
    fun consumePendingFileTranscription(context: Context): Boolean {
        if (_state.value !is UiState.Idle) return false
        val pending = pendingTranscriptionDir(context).listFiles()?.firstOrNull { it.isFile && it.length() > 0L }
            ?: return false
        // Claim it: move out of the pending dir so it cannot be picked up twice, then clean the dir.
        val claimed = File(context.cacheDir, "dictate_import_${pending.name}")
        claimed.delete()
        if (!pending.renameTo(claimed)) {
            pending.copyTo(claimed, overwrite = true)
            pending.delete()
        }
        pendingTranscriptionDir(context).deleteRecursively()
        if (!claimed.exists() || claimed.length() == 0L) return false
        // The keyboard is the one picking this file up, on its own field (issue #409).
        claimKeyboardOutput()
        // A deliberately picked file is transcribed as-is (no silence gate — see issue #93).
        transcribe(context, claimed, gate = false, source = DictateHistorySource.IMPORT)
        return true
    }

    /**
     * Shared transcription path for both recorded and picked audio: resolves provider/key/model,
     * uploads [audioFile], commits the result and deletes the file afterwards.
     */
    private fun transcribe(
        context: Context,
        audioFile: File,
        recordedSeconds: Long = 0L,
        gate: Boolean = true,
        // Long-press "send with local model" (#228): force this one transcription onto the on-device
        // provider regardless of the configured active provider.
        forceLocal: Boolean = false,
        // History (issue #140): [isReplay] re-transcribes already-counted audio (skip stats),
        // [replayHistoryId] updates that stored entry's text in place, [source] tags the origin.
        isReplay: Boolean = false,
        source: String = DictateHistorySource.KEYBOARD,
        replayHistoryId: Long? = null,
        // The placeholder row a previous attempt at THIS dictation already opened (#358), continued here
        // instead of opened again — see [cancelAndTranscribeLocal], the one place a dictation changes
        // engine mid-flight.
        adoptHistoryId: Long? = null,
        latencyTrace: BatchLatencyTrace = BatchLatencyTrace(),
        // A realtime dictation falling back to batch: what the user took over of its preview (#421).
        handedOver: String = "",
        // The fallback provider this dictation was handed to because the active one could not be reached
        // (#455). It runs exactly as if it were the active provider, with its own model, key and formats,
        // and is never handed on again.
        fallbackAccount: ProviderAccount? = null,
    ) {
        logLatency(latencyTrace, "transcribeEntered")
        val account = localTranscriptionAccount()
        val preset = presetFor(account)
        val appContext = context.applicationContext
        val model = transcriptionModelFor(
            appContext, account, preset, ProviderRegistry.LOCAL.defaultTranscriptionModel.orEmpty(),
        )
        // History metadata (issue #140), resolved once so the success (capture) and EVERY failure path —
        // including the early returns below (no key / model not downloaded) — log the same info.
        val historyProviderName = account.displayName.ifBlank { preset.displayName }
        val historyLanguage = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT } ?: ""
        val historySource = if (outputTarget == OutputTarget.OVERLAY) DictateHistorySource.OVERLAY else source
        // Logs the failed dictation with its audio (safety net, issue #140) unless this is a replay, then
        // drops the cache file. Async so the early-return callers don't block.
        fun logFailureAndDrop() {
            if (replayHistoryId != null) {
                audioFile.delete()
                return
            }
            scope.launch {
                recordFailedHistory(
                    appContext, audioFile, account.providerId, historyProviderName, model, historyLanguage,
                    recordedSeconds, historySource, sensitive = isSensitiveDictationField(appContext),
                )
                if (audioFile.exists()) audioFile.delete()
            }
        }

        // On-device (#104): guide the user to download a model instead of failing mid-transcription.
        if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE &&
            !LocalModelManager.isInstalled(appContext, model)
        ) {
            _state.value = UiState.Error(
                message = context.getString(R.string.dictate__local_model_not_installed_error),
                kind = DictateApiException.Kind.UNKNOWN,
                action = ErrorAction.OPEN_SETTINGS,
            )
            logFailureAndDrop()
            return
        }

        ensureHapticObserver(appContext)
        setTranscribing(onDevice = true)
        // What a held button can still rescue (#270): the recording this request is carrying, for as long
        // as it is in flight. Cleared in the finally below, so the offer disappears with the request.
        inFlightAudio = null
        inFlightSeconds = recordedSeconds
        // Cleared here rather than left to the previous job's teardown, which has not necessarily run yet:
        // this attempt has no row until it writes one, and a rescue in the meantime must not be handed the
        // last dictation's (#358).
        inFlightHistoryId = adoptHistoryId
        // Nothing for a stop to keep until the job has asked the field (#437).
        inFlightResend = null
        // Live prompt is consumed by this transcription only (the next recording is normal again).
        val live = livePromptArmed
        val unwinding = unwindingTranscribeJob
        unwindingTranscribeJob = null
        val coroutineScheduledNanos = SystemClock.elapsedRealtimeNanos()
        transcribeJob = scope.launch {
            var keepAudio = false
            var outcome = "failed"
            // The history row written when the audio went out (#358), waiting to become the finished
            // dictation. Null while there is none: history off, a sensitive field, or a replay.
            var pendingHistoryId: Long? = adoptHistoryId
            // A password or incognito field (#383), asked once below. True until then, so nothing is kept
            // for a field nobody has asked about.
            var sensitiveField = true
            // The file actually uploaded. Normally the original recording; the silence trimmer (#232) may
            // swap in a shorter copy, while history/retention/cleanup keep referencing the original audioFile.
            var uploadFile = audioFile
            // Set only when the audio was sped up (#272): the same audio *before* that, which is what the
            // on-device fallback (#104) transcribes — the local engine is deliberately never fed sped-up
            // audio, since there is no bill to shorten there. Null means "the upload file is fine".
            var localFallbackFile: File? = null
            // Set only when the upload was packed into m4a (#281): the WAV it was packed from, so the
            // finally block can drop it once the on-device fallback no longer needs it.
            var packedFrom: File? = null
            // Set only when the container had to be changed for this provider (#322) — same bookkeeping
            // as packedFrom, kept separate because the two can in principle both run.
            var convertedFrom: File? = null
            try {
                logLatency(latencyTrace, "coroutineStarted", coroutineScheduledNanos)
                // A request that was stopped may still be unwinding its audio work; see [unwindingTranscribeJob].
                unwinding?.join()
                // Asked once, before anything leaves the phone, and by the field the dictation is for. A stop
                // comes later, often from another app (#437), and the history rows below are decided now too.
                sensitiveField = isSensitiveDictationField(appContext)
                if (!sensitiveField) {
                    inFlightResend = RetainedAudio(
                        audioFile, RetainReason.FAILED, live, recordedSeconds,
                        historyId = pendingHistoryId ?: replayHistoryId,
                    )
                }
                reconcileActiveLanguage() // correct a stale active language before it's read for the request
                // Local Silero VAD pass before spending an upload. Two purposes, both skipped for picked
                // files / resends (gate=false) and while long-form dictation runs its own segment-cutting:
                //   • Silence gate (#93): a recording with no speech is dropped so silent clips can't produce
                //     "ghost text" hallucinations or waste API credits.
                //   • Silence trimming (#232): long internal pauses are cut out so a gappy dictation uploads
                //     less audio (less cost/latency) without losing a word.
                // Both fail open (treated as speech, left untrimmed) if the check can't run.
                val skipSilent = prefs.dictate.skipSilentRecordings.get()
                val trimGaps = prefs.dictate.trimSilentGaps.get()
                val longform = prefs.dictate.longformMode.get().isEnabled
                if (gate && !longform && (skipSilent || trimGaps)) {
                    val gateStartedNanos = SystemClock.elapsedRealtimeNanos()
                    if (trimGaps) {
                        // One VAD pass yields both the speech decision and the segment map for trimming.
                        val analysis = SpeechGate.analyze(appContext, audioFile)
                        logLatency(latencyTrace, "speechGateCompleted", gateStartedNanos)
                        if (skipSilent && analysis != null && !analysis.hasSpeech) {
                            outcome = "noSpeech"
                            _state.value = UiState.Error(
                                message = appContext.getString(R.string.dictate__no_speech_detected),
                                action = ErrorAction.NONE,
                                neutral = true, // informational, not a failure → white/themed, not red
                            )
                            return@launch // audio is dropped by the finally block
                        }
                        if (analysis != null && analysis.hasSpeech) {
                            SpeechGate.writeTrimmedWav(
                                analysis,
                                File(appContext.cacheDir, TRIMMED_AUDIO_NAME),
                                TRIM_MAX_SILENCE_MS,
                                TRIM_KEEP_SILENCE_MS,
                            )?.let { uploadFile = it }
                        }
                    } else {
                        // Gate only: the cheaper early-exit check (returns as soon as the first speech closes).
                        val hasSpeech = SpeechGate.hasSpeech(appContext, audioFile)
                        logLatency(latencyTrace, "speechGateCompleted", gateStartedNanos)
                        if (!hasSpeech) {
                            outcome = "noSpeech"
                            _state.value = UiState.Error(
                                message = appContext.getString(R.string.dictate__no_speech_detected),
                                action = ErrorAction.NONE,
                                neutral = true, // informational, not a failure → white/themed, not red
                            )
                            return@launch // audio is dropped by the finally block
                        }
                    }
                }
                // Log the dictation now, before a byte of it leaves the phone (issue #358). Until the
                // transcript comes back the row is the ordinary failed one — a recording, a red "try
                // again", its audio force-kept — because that is exactly what this is until proven
                // otherwise, and because it means the entry is already correct if nothing ever gets the
                // chance to rewrite it: a request that hangs (#350), a process killed mid-upload, or the
                // user tapping the mic to escape a wait. It sits after the silence gate on purpose, so a
                // recording that was never sent leaves no trace of having been.
                //
                // A replay already has its row, and re-logging one would fork the entry it came from; so
                // does an attempt handed over from another engine.
                if (replayHistoryId == null && pendingHistoryId == null) {
                    pendingHistoryId = recordFailedHistory(
                        appContext, audioFile, account.providerId, historyProviderName,
                        model, historyLanguage, recordedSeconds, historySource, sensitiveField,
                    )
                }
                inFlightHistoryId = pendingHistoryId
                // A stop from here on hands the resend chip this row, so a resend finishes it.
                inFlightResend = inFlightResend?.copy(historyId = pendingHistoryId ?: replayHistoryId)
                val request = TranscriptionRequest(
                    audioFile = uploadFile,
                    model = model,
                    language = prefs.dictate.activeInputLanguage.get()
                        .takeIf { it != DictateLanguages.DETECT },
                    expectedLanguages = expectedLanguages(),
                    prompt = transcriptionStylePrompt(),
                )
                val providerStartedNanos = SystemClock.elapsedRealtimeNanos()
                LocalTranscriptionProvider.setIdleUnloadMillis(
                    prefs.dictate.localModelUnloadMinutes.get() * 60_000L,
                )
                val result = LocalTranscriptionProvider(
                    LocalTranscriptionProvider.modelDir(appContext, model),
                    timeoutMillis = localTimeoutMillis(),
                    tighteningSymbols = appContext.transcriptTighteningSymbols(),
                ).transcribe(request)
                logLatency(latencyTrace, "providerCompleted", providerStartedNanos)
                // Prompt-echo guard (issue #77): on silent/unclear audio, Whisper-style models echo the
                // transcription style prompt back verbatim (the old default was infamously returned as
                // "This sentence has capitalization and punctuation."). If the result is just that prompt
                // echoed, treat it as no speech and drop it instead of dumping the prompt into the field.
                // Skipped for the chat-audio path, whose prompt is an instruction, not a Whisper style hint.
                if (DictatePromptDefaults.looksLikeStylePromptEcho(result.text, transcriptionStyleBasePrompt())) {
                    outcome = "promptEcho"
                    // Nothing was said, so nothing failed: take the placeholder (#358) back out rather
                    // than leaving a red row offering to re-transcribe silence.
                    pendingHistoryId?.let { DictateHistoryStore.deleteById(appContext, it) }
                    _state.value = UiState.Error(
                        message = appContext.getString(R.string.dictate__no_speech_detected),
                        action = ErrorAction.NONE,
                        neutral = true,
                    )
                    return@launch // audio is dropped by the finally block
                }
                // Shared finalize: rewording/formatting + mappings + commit + stats. Reused by the
                // realtime path (issue #128), which supplies its own already-streamed transcript.
                val configured = HistoryEngine(account.providerId, historyProviderName, model)
                val capture = HistoryCapture(
                    audioFile = audioFile,
                    engine = configured,
                    language = historyLanguage,
                    source = historySource,
                    isReplay = isReplay,
                    replayHistoryId = replayHistoryId,
                    pendingHistoryId = pendingHistoryId,
                )
                val finalizeStartedNanos = SystemClock.elapsedRealtimeNanos()
                finalizeAndCommit(
                    appContext,
                    result.text,
                    recordedSeconds,
                    live,
                    alreadyFormatted = false,
                    capture = capture,
                    latencyTrace = latencyTrace,
                    handedOver = handedOver,
                )
                logLatency(latencyTrace, "finalizeCompleted", finalizeStartedNanos)
                outcome = "success"
            } catch (c: CancellationException) {
                // Stopped or abandoned: never an error here, the state is the canceller's to set. The row
                // written when the audio was sent (#358) stays, with its own copy, so giving up on a wait
                // is no longer the same as giving up on the dictation. That was the whole reason nobody
                // could use the stop button to escape a hanging request. The stop button also keeps the
                // cache file for its resend chip (#437), which is why the finally asks before deleting it.
                outcome = "cancelled"
                throw c
            } catch (e: DictateApiException) {
                outcome = "apiError"
                // Diagnostics (#447): which failure it was. Provider error text never carries the transcript.
                Log.i(
                    LATENCY_LOG_TAG,
                    "phase=apiError kind=${e.kind} http=${e.httpStatus} code=${e.code} " +
                        "cause=${e.cause?.javaClass?.simpleName} msg=${e.message?.take(160)?.replace('\n', ' ')}",
                )
                // Read before anything below touches the state: this block catches the provider call as
                // well as the live prompt's rewording inside finalizeAndCommit (issue #284).
                val stage = stageOf(_state.value)
                // Safety net (issue #140): log the failed dictation with its audio so it can be recovered
                // later; not for replays (the entry already exists) and not when the row written at send
                // time (#358) is already saying exactly this — it only ever had to be left alone. Left
                // ahead of the retain below so the chip is handed the row either of them produced.
                if (replayHistoryId == null && pendingHistoryId == null) {
                    pendingHistoryId = recordFailedHistory(appContext, audioFile, account.providerId, historyProviderName, model, historyLanguage, recordedSeconds, historySource, sensitiveField)
                }
                // Exportable failures (too large / bad format) keep the audio regardless of the resend
                // pref, so it can be saved instead of lost (issue #144).
                keepAudio = retainFailedAudio(
                    audioFile, live, recordedSeconds,
                    historyId = pendingHistoryId ?: replayHistoryId,
                    force = e.kind in EXPORTABLE_ERROR_KINDS,
                )
                _state.value = apiError(
                    e, appContext, canResend = keepAudio,
                    suggestOnDevice = false,
                    stage = stage,
                    onDevice = true,
                )
            } catch (t: Throwable) {
                outcome = "unexpectedError"
                val stage = stageOf(_state.value)
                if (replayHistoryId == null && pendingHistoryId == null) {
                    pendingHistoryId = recordFailedHistory(appContext, audioFile, account.providerId, historyProviderName, model, historyLanguage, recordedSeconds, historySource, sensitiveField)
                }
                keepAudio = retainFailedAudio(
                    audioFile, live, recordedSeconds,
                    historyId = pendingHistoryId ?: replayHistoryId,
                )
                _state.value = UiState.Error(
                    message = appContext.getString(errorMessageRes(DictateApiException.Kind.UNKNOWN, stage)),
                    kind = DictateApiException.Kind.UNKNOWN,
                    action = if (keepAudio) ErrorAction.RESEND else ErrorAction.NONE,
                    detail = t.message?.takeIf { it.isNotBlank() },
                )
            } finally {
                // The request is over, however it ended: there is nothing left for a held button to rescue,
                // no row of this attempt left for a next one to continue (#358), and nothing for a stop to
                // keep. Only while they are still this request's, though: a cancel clears them itself, and a
                // request started since then has set them for itself.
                if (transcribeJob == null || transcribeJob === coroutineContext[Job]) {
                    inFlightAudio = null
                    inFlightHistoryId = null
                    inFlightResend = null
                }
                // A recording the resend chip holds is the chip's: a stop handed it over (#437), or this
                // was a resend of it and it stays offered until a resend succeeds or the user drops it.
                if (!keepAudio && retained?.file != audioFile) audioFile.delete()
                // Drop the derived upload copies — trimmed (#232) and/or sped up (#272); the original
                // audioFile is the one history keeps.
                if (uploadFile !== audioFile) runCatching { uploadFile.delete() }
                localFallbackFile?.takeIf { it !== audioFile && it !== uploadFile }
                    ?.let { runCatching { it.delete() } }
                // The WAV the m4a was packed from (#281) — the sped-up copy, when both ran. Kept until
                // here because the on-device fallback may still have needed it.
                packedFrom?.takeIf { it !== audioFile && it !== uploadFile && it !== localFallbackFile }
                    ?.let { runCatching { it.delete() } }
                // The file the container conversion (#322) replaced, on the same terms.
                convertedFrom?.takeIf {
                    it !== audioFile && it !== uploadFile && it !== localFallbackFile && it !== packedFrom
                }?.let { runCatching { it.delete() } }
                // System voice input (#67): hand the terminal outcome back to the RecognitionService so it
                // delivers results / an error to the calling app. One hook covers every path (success,
                // no-speech, prompt-echo, API/unexpected error) since `outcome` is set before each return.
                if (outputTarget == OutputTarget.RECOGNITION_SERVICE) {
                    dev.patrickgold.florisboard.dictate.recognition.RecognitionBridge.completeOutcome(outcome)
                }
                logLatency(latencyTrace, "terminal:$outcome")
            }
        }
    }

    /**
     * Shared finalize step for a produced transcript, used by both the batch [transcribe] path and the
     * realtime path (issue #128): runs live-prompt rewording or the auto-formatting/auto-apply/pending
     * prompt chain (unless [alreadyFormatted]), applies the deterministic mappings, commits, and records
     * stats. [rawText] is the transcript to process; [live] routes it as a live-prompt instruction.
     *
     * [handedOver] is what the user took over of a live preview by editing it (issue #421). It is in the
     * field as they left it, so only the rest of [rawText] is finished and written.
     */
    private suspend fun finalizeAndCommit(
        appContext: Context,
        rawText: String,
        recordedSeconds: Long,
        live: Boolean,
        alreadyFormatted: Boolean,
        finalizeViaComposing: Boolean = false,
        capture: HistoryCapture? = null,
        latencyTrace: BatchLatencyTrace? = null,
        handedOver: String = "",
    ) {
        cleanupFailure = null
        // Legacy live-prompt flags never turn dictated words into editing commands.
        val isLive = false
        val text = TranscriptHandover.continuation(handedOver, rawText)
        val finalText = postProcessTranscript(appContext, text)
        // Paragraph splitting (issue #225): break a long *pure* transcript into paragraphs at sentence
        // boundaries. Only when nothing reworded/auto-formatted the text (a live prompt, single-call
        // multimodal, or an auto-format/prompt pass that actually changed it) — that output already carries
        // its own paragraphing and must not be second-guessed.
        val splitWords = prefs.dictate.paragraphSplitWords.get()
        val isPureTranscript = !isLive && !alreadyFormatted && finalText == text
        // Keep the raw transcript for the history when a prompt actually rewrote it (issue #240), so the
        // original wording stays recoverable without re-running (and paying for) the transcription. Only
        // the prompt chain counts: the deterministic steps below (paragraph splitting, custom mappings)
        // would otherwise store a near-identical copy differing in little more than line breaks.
        val originalForHistory = if (finalText != text) rawText else ""
        val paragraphed = if (isPureTranscript && splitWords > 0) {
            TranscriptParagraphs.split(finalText, splitWords)
        } else {
            finalText
        }
        // Deterministic find-and-replace dictionary (issue #129), applied right before insert.
        val outputText = prefs.dictate.customMappings.get().apply(paragraphed)
        // The whole dictation, for what keeps a record of it — the history, the stats, the re-insert cache —
        // while the field gets only the part that was not handed over (#421). [text] is the tail of [rawText].
        val dictated = if (text.length == rawText.length) {
            outputText
        } else {
            TranscriptJoin.join(rawText.dropLast(text.length).trimEnd(), outputText, appContext.transcriptTighteningSymbols())
        }
        // The space in front of it, if the field needs one (#443). Kept out of [outputText] on purpose:
        // the history, the re-insert cache and the clipboard copies hold the dictation, not the seam.
        val lead: String
        if (finalizeViaComposing) {
            // Realtime (#128): replace the live-streamed preview with the finished (reworded) result via the
            // minimal diff, then honor auto-enter — instead of committing on top of the preview.
            val outSink = sink(appContext)
            // Edited while it was being finished (#421): what the user saw is theirs, finished or not, so
            // the finished text has nothing left to replace. Only words that never reached the field are
            // still to be written, as they were heard.
            val shownOwn = realtimeShown.toString().removePrefix(dictationLead)
            val edited = handOverIfEdited(outSink)
            val written = if (edited) TranscriptHandover.continuation(shownOwn, text) else outputText
            // A preview already on screen carries the separator decided at its first word; asking the field
            // now would find our own preview in front of the cursor.
            lead = when {
                written.isEmpty() -> ""
                realtimeShown.isNotEmpty() -> dictationLead
                else -> leadFor(appContext, written)
            }
            // The always-copy safety net (#214) before the commit, for the reason the batch branch gives:
            // realtime and long-form dictations from the floating button are inserted here, and this
            // branch used to skip the setting that promises "every dictation".
            if (outputTarget == OutputTarget.OVERLAY && outputText.isNotEmpty() &&
                prefs.dictate.floatingButtonCopyToClipboard.get()
            ) {
                copyToSystemClipboard(appContext, outputText)
            }
            // Off the main thread for the overlay, like every other accessibility write: this one ends
            // in the same resolve-focus-write-verify round trip (see [writeToSink]).
            val landed = if (outputTarget == OutputTarget.OVERLAY) {
                withContext(Dispatchers.IO) {
                    outSink.commitDictationFinal(lead + written, realtimeShown.toString())
                }
            } else {
                outSink.commitDictationFinal(lead + written, realtimeShown.toString())
            }
            realtimeShown.setLength(0)
            realtimeTranscript.setLength(0)
            // This branch never went through commitOutput, so it never saw the insert-failure check
            // either (issue #277) — a swallowed write finished as Idle, i.e. a green check.
            if (reportOverlayInsertFailure(appContext, landed, outputText)) {
                rememberLastDictation(appContext, outputText)
                recordHistory(appContext, outputText, originalForHistory, recordedSeconds, capture, reworded = isLive)
                discardRetainedAudio()
                return
            }
            // Not after an edit at the last moment: the user is in the middle of the text, and auto-enter
            // would send it.
            if (prefs.dictate.autoEnter.get() && outputText.isNotEmpty() && !edited) outSink.performEnter()
        } else {
            // Safety net (#214): for the floating button, optionally copy every dictation to the system
            // clipboard so nothing is lost if the accessibility insert is silently swallowed (the known
            // "green check but no text" case). Done BEFORE the commit on purpose: the paste-fallback captures
            // the current clipboard as "previous" and restores it 400 ms later, so with our text already on
            // the clipboard that restore becomes a no-op instead of wiping the copy.
            if (outputTarget == OutputTarget.OVERLAY && outputText.isNotEmpty() &&
                prefs.dictate.floatingButtonCopyToClipboard.get()
            ) {
                copyToSystemClipboard(appContext, outputText)
            }
            lead = leadFor(appContext, outputText)
            val committed = commitOutput(appContext, lead + outputText)
            if (committed) latencyTrace?.let { logLatency(it, "outputCommitted") }
            // Floating button (#156): the accessibility insert can be silently swallowed by some app fields
            // (Gemini's Compose box, WebViews). Don't flash a false green check — surface an error, and
            // put the text somewhere the user can actually reach.
            if (!committed && outputTarget == OutputTarget.OVERLAY && outputText.isNotEmpty()) {
                rememberLastDictation(appContext, outputText)
                if (capture?.isReplay != true) {
                    DictateStats.recordDictation(prefs, outputText, recordedSeconds)
                }
                recordHistory(appContext, outputText, originalForHistory, recordedSeconds, capture, reworded = isLive)
                discardRetainedAudio()
                // The clipboard is the recovery route, and only here (issue #277). The old message sent
                // people to "Reinsert", which lives in the Dictate keyboard — unreachable for exactly the
                // people who hit this, since they are using the floating button with another keyboard.
                // Copying on failure alone keeps the clipboard (and the Samsung toast) out of the way of
                // everyone whose insert works; the always-copy setting (#214) stays what it is.
                reportOverlayInsertFailure(appContext, committed, outputText)
                return
            }
        }
        // Re-insert safety net (issue #111) + lifetime stats (issue #142) + history log (issue #140).
        rememberLastDictation(appContext, dictated)
        lastDictationLead = lead
        if (capture?.isReplay != true) {
            DictateStats.recordDictation(prefs, dictated, recordedSeconds)
        }
        recordHistory(appContext, dictated, originalForHistory, recordedSeconds, capture, reworded = isLive)
        discardRetainedAudio()
        if (reportCleanupFailure(appContext)) return
        _state.value = UiState.Idle
    }

    /** Copies [text] to the system clipboard — the floating-button always-copy safety net (issue #214). */
    /**
     * A floating-button write the field refused: put the text on the clipboard and say so, instead of
     * flashing a green check over nothing (issue #277).
     *
     * Returns whether it handled a failure, so callers can `if (reportOverlayInsertFailure(...)) return`.
     * Only ever true for [OutputTarget.OVERLAY] — the keyboard writes through its own InputConnection
     * and cannot silently no-op, and the recognition service has no field of its own.
     */
    private fun reportOverlayInsertFailure(context: Context, committed: Boolean, text: String): Boolean {
        if (committed || outputTarget != OutputTarget.OVERLAY || text.isEmpty()) return false
        copyToSystemClipboard(context, text)
        _state.value = UiState.Error(
            message = context.getString(R.string.dictate__error_overlay_insert_clipboard),
        )
        return true
    }

    private fun copyToSystemClipboard(context: Context, text: String) {
        // Already there, so writing it again would buy nothing but a second "copied" notice. This is the
        // normal case on the failure route: the paste attempt put the text on the clipboard moments ago,
        // and then the failure handler goes to copy the very same text as the recovery route.
        if (DictateAccessibilityService.lastClipText == text) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val copied = runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", text)) }
        // Tell the overlay what is on the clipboard now, so a paste-based insert moments later does not
        // write the identical text a second time either.
        if (copied.isSuccess) DictateAccessibilityService.lastClipText = text
    }

    // --- Real-time streaming (issue #128) -------------------------------------------------------

    /**
     * The installed on-device **streaming** model to run live (issue #233), or null if local live
     * transcription doesn't apply. Checked before [realtimeApiForActiveAccount] because that one bails
     * out on a blank API key, which the on-device provider always has.
     */
    private fun localStreamingModelDir(context: Context): File? {
        if (!prefs.dictate.realtimeTranscription.get()) return null
        val account = transcriptionAccount()
        if (presetFor(account).transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE) return null
        // The live model has its own slot on the local account (#233) — `realtimeModel`, which for this
        // provider means the streaming model rather than a remote model id. The one-shot slot is also
        // accepted as a source: before the two slots existed a streaming model could only be picked
        // there, and such a setup should keep working instead of silently dropping back to batch.
        val modelId = account.realtimeModel.takeIf { LocalModelCatalog.isStreaming(it) }
            ?: account.transcriptionModel.takeIf { LocalModelCatalog.isStreaming(it) }
            ?: return null
        if (!LocalModelManager.isInstalled(context, modelId)) return null
        return LocalTranscriptionProvider.modelDir(context, modelId)
    }

    /**
     * True if the next recording should stream in real time: the global toggle is on and either the cloud
     * provider supports realtime or (with [context]) an on-device streaming model is installed. Without a
     * context only the cloud case can be answered, since the local check has to look at the filesystem.
     */
    fun isRealtimeActive(context: Context? = null): Boolean =
        context != null && localStreamingModelDir(context.applicationContext) != null

    /** True while a real-time streaming recording is actually in progress (a session is open). */
    fun isRealtimeRecording(): Boolean = realtimeSession != null

    /**
     * Opens a realtime session for the active account and returns a PCM sink to hand [RecordingController]
     * (which feeds captured 16 kHz frames, resampled per provider). Returns null when realtime does not
     * apply or the session can't be created — the caller then records normally (batch).
     */
    private fun openRealtimeSession(appContext: Context): ((ByteArray, Int) -> Unit)? {
        realtimeHidden = false
        // System voice input (#67) always records in plain batch mode — the RecognitionService callback
        // returns one final result, so there's no realtime streaming/composing to wire up here.
        if (outputTarget == OutputTarget.RECOGNITION_SERVICE) return null
        // On-device live model (#233) wins over the cloud lookup: the local provider has no API key, so
        // realtimeApiForActiveAccount() would reject it before ever getting here.
        val localModelDir = localStreamingModelDir(appContext) ?: return null
        realtimeFinal.setLength(0)
        realtimeFailed = false
        realtimeCancelled = false
        _interimText.value = ""
        realtimeContext = appContext
        realtimeShown.setLength(0)
        realtimeShownTranscript = ""
        realtimeHandedOver = ""
        realtimeTranscript.setLength(0)
        // The floating button always holds the words back, whatever the preference says (#357).
        //
        // Live typing rewrites the field several times a second, and outside our own keyboard that goes
        // through the accessibility route: every update is a diff against what we *believe* is in the
        // field, and the moment one write is refused or the app edits the field itself, the rest of the
        // dictation is computed against a fiction — which shows up as the text stopping mid-sentence and
        // never catching up. Inside the keyboard there is a real InputConnection and the same updates are
        // cheap and exact; over the overlay they are neither.
        //
        // The stream itself still runs, so this costs nothing: the transcript is already there when the
        // button is tapped and lands in one commit — the same verified insert a batch dictation does, and
        // without the provider round trip a batch dictation would still be waiting for.
        //
        // A live prompt holds them back too, whatever the preference says: those words are an
        // instruction, and typing "make this more formal" into the field only to replace it a moment
        // later shows the user a sentence they never asked to write.
        realtimeHidden = prefs.dictate.realtimeHidePreview.get() ||
            outputTarget == OutputTarget.OVERLAY ||
            livePromptArmed
        // Read once for the session: a trigger word changed mid-dictation would judge its own first
        // words by one rule and the rest by another.
        val closed = CompletableDeferred<Unit>()
        realtimeClosed = closed
        // Type the growing transcript live into the field, applying only the minimal diff each time (#128) —
        // unless the preview is held back (#345), in which case the words are only remembered and the field
        // sees nothing until stop. The stream itself runs identically either way; this is purely what the
        // user is shown, so hiding it costs no speed and removes the word-by-word churn.
        suspend fun showLive(full: String) {
            if (realtimeCancelled) return   // a late callback must not re-add text after a cancel or a finalize
            _interimText.value = full
            realtimeTranscript.setLength(0)
            realtimeTranscript.append(full)
            // The spoken command word (#139). Unlike batch, streaming can act on it while the user is
            // still talking — which is also why it has to: the words are being typed into the field as
            // they arrive, so the decision has to be made before the first of them lands there.
            //
            // PARTIAL is the whole reason this is not a plain prefix check. "Ja" is either the start of
            // "Jarvis" or the start of "Ja, das passt", and nothing yet says which, so the preview waits
            // — a fraction of a second, until the next piece of text settles it. Without that wait a
            // command would type its own trigger word into the field and take it back out again.
            //
            // Only while the recording actually runs. A finished stream keeps delivering for a moment —
            // that is the tail wait (#372), and those late callbacks land here with the *whole*
            // transcript, trigger word and all. [stopRealtimeAndFinalize] has by then read
            // livePromptArmed and cleared it, so arming again from one of them set a flag nothing was
            // going to read: the next recording started with the chip lit and ran as a rewording,
            // without a command word having been said.
            if (realtimeHidden) return
            val outSink = sink(appContext)
            // Text the user has marked mid-dictation is theirs to act on, and a write now would type over
            // it (#421). Waiting loses nothing: the next update brings the whole transcript again. A
            // selection from before the first word is the opposite — dictating over it is the point.
            if (realtimeShown.isNotEmpty() && outSink.selectedText().isNotEmpty()) return
            handOverIfEdited(outSink)
            val own = TranscriptHandover.continuation(realtimeHandedOver, full)
            // The separator is settled before the first word goes in (#443) and then travels with the
            // preview, so the finalize replaces like with like.
            if (realtimeShown.isEmpty()) dictationLead = leadFor(appContext, own)
            val shown = if (own.isEmpty()) "" else dictationLead + own
            runCatching { outSink.setDictationPreview(shown, realtimeShown.toString()) }
            realtimeShown.setLength(0)
            realtimeShown.append(shown)
            realtimeShownTranscript = full
        }
        // Read once for the whole session rather than per piece, so a subtype switch mid-dictation cannot
        // leave one transcript joined by two different conventions.
        val tightening = appContext.transcriptTighteningSymbols()
        val callbacks = object : RealtimeCallbacks {
            override fun onPartial(text: String) {
                // Stamped here rather than in showLive: this is when the provider spoke, which is what the
                // tail wait on stop is measuring — not when a queued coroutine got around to the field.
                realtimeLastTextAt = SystemClock.elapsedRealtime()
                scope.launch {
                    showLive(TranscriptJoin.join(realtimeFinal.toString(), text, tightening))
                }
            }
            override fun onFinalSegment(text: String) {
                realtimeLastTextAt = SystemClock.elapsedRealtime()
                scope.launch {
                    TranscriptJoin.appendPiece(realtimeFinal, text, tightening)
                    showLive(realtimeFinal.toString())
                }
            }
            override fun onError(t: Throwable) { realtimeFailed = true }
            override fun onClosed() { closed.complete(Unit) }
        }
        val session = runCatching {
            LocalTranscriptionProvider.setIdleUnloadMillis(
                prefs.dictate.localModelUnloadMinutes.get() * 60_000L,
            )
            LocalRealtimeSession(localModelDir, callbacks)
        }.getOrElse { realtimeFailed = true; null } ?: return null
        realtimeSession = session
        return { pcm, len ->
            runCatching { session.sendAudio(pcm, len) }
        }
    }

    /**
     * Waits out the tail of a finished stream (#372): returns as soon as [closed] completes, or once the
     * provider has been silent for [REALTIME_TAIL_IDLE_MS], and in no case later than
     * [REALTIME_TAIL_MAX_MS] after the stop.
     *
     * The silence is measured from the last text the stream produced, not from the stop, and the recording
     * having just ended does not mean the provider is done: it is still transcribing whatever audio it has
     * not caught up with. So an idle window that keeps being renewed is what tells the two apart — a
     * provider with nothing left to say goes quiet and we commit, one that is still working keeps sending
     * and we keep listening.
     *
     * Returns false in the one case where the stream was cut rather than finished: still producing text
     * when the hard cap ran out. The transcript is then known to be missing its end, and the caller has a
     * complete recording to transcribe instead.
     */
    private suspend fun awaitRealtimeTail(closed: CompletableDeferred<Unit>?): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        // Whatever the provider said while the microphone was open says nothing about how long its closing
        // words will take, so the idle window starts fresh at the stop.
        realtimeLastTextAt = startedAt
        var endedBy = "cap"
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val idleUntil = realtimeLastTextAt + REALTIME_TAIL_IDLE_MS
            val until = minOf(idleUntil, startedAt + REALTIME_TAIL_MAX_MS)
            if (until <= now) {
                if (idleUntil <= now) endedBy = "silence"
                break
            }
            if (withTimeoutOrNull(until - now) { closed?.await(); true } != null) {
                endedBy = "provider"
                break
            }
        }
        Log.i(
            LATENCY_LOG_TAG,
            "phase=realtimeTail endedBy=$endedBy phaseMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return endedBy != "cap"
    }

    /**
     * Stops a realtime recording: finalizes the stream, then commits the accumulated transcript through the
     * shared [finalizeAndCommit]. Keeps the recorded WAV so any stream failure (or an empty transcript)
     * falls back to a normal batch [transcribe] of the audio — the user never loses their dictation.
     */
    private fun stopRealtimeAndFinalize(context: Context) {
        val session = realtimeSession
        realtimeSession = null
        realtimeContext = null
        val activeRecorder = recorder
        recorder = null
        unregisterScreenOffReceiver()
        val recordedSeconds = recordedSecondsOf(_state.value)
        val wavFile = activeRecorder?.stop()
        cleanupAudioRouting()
        val live = livePromptArmed
        val closed = realtimeClosed
        realtimeClosed = null
        setTranscribing()
        val appContext = context.applicationContext
        transcribeJob = scope.launch {
            try {
                runCatching { session?.finish() }
                // Wait for the provider to flush the last words, then force-close the socket — several
                // providers keep it open after finish, which otherwise stalls us until the timeout and
                // later trips a ping/pong failure.
                //
                // The wait ends the moment the session closes itself, which is the normal case and takes a
                // fraction of a second. What it must not do is end while the tail is still arriving: the
                // last word or two of a dictation only reach us in the closing segment, so a fixed budget
                // committed a sentence one word short whenever the stream was running behind (#372). So
                // the clock is restarted by every piece of text and only silence ends it early.
                val tailComplete = awaitRealtimeTail(closed)
                runCatching { session?.cancel() }
                // From here the transcript is read, and nothing that arrives later may touch the field.
                // `close(1000)` keeps frames coming until the server's own close, and a final that lands
                // after the commit used to go through showLive with an empty realtimeShown, typing the
                // whole dictation in a second time: on a slow connection the provider's ordinary last
                // message is enough, once it misses the silence window (#172). The yield first lets
                // whatever is already queued on the main thread land: a session that closed before the
                // wait began left `closed.await()` with nothing to suspend for, and its last segment can
                // still be waiting behind this coroutine.
                yield()
                realtimeCancelled = true
                // The transcript is everything the stream produced (finals + last partial), which with a
                // hidden preview (#345) is the only place it exists; fall back to the finalized-segments
                // buffer only if the stream produced nothing at all.
                val transcript = realtimeTranscript.toString().trim().ifEmpty { realtimeFinal.toString().trim() }
                _interimText.value = ""
                // A stream still delivering when the tail cap ran out is missing its end, and half a
                // dictation is worse than the wait: the recording is complete, so transcribe that instead.
                if (realtimeFailed || !tailComplete || transcript.isEmpty()) {
                    Log.i(
                        LATENCY_LOG_TAG,
                        "phase=realtimeFallback reason=" + when {
                            realtimeFailed -> "streamFailed"
                            !tailComplete -> "tailCap"
                            else -> "emptyTranscript"
                        },
                    )
                    // Drop the live provisional text; the batch path commits fresh from the WAV. With the
                    // preview hidden there is nothing in the field to take back, and realtimeShown says so.
                    // What the user has edited stays, and the batch text leaves out what they took (#421).
                    val handedOver = withdrawPreview(appContext)
                    realtimeTranscript.setLength(0)
                    if (wavFile != null && wavFile.exists() && wavFile.length() > 0L) {
                        transcribe(context, wavFile, recordedSeconds, gate = false, handedOver = handedOver)
                    } else {
                        _state.value = UiState.Error(appContext.getString(R.string.dictate__error_no_audio))
                    }
                    return@launch
                }
                // History (issue #140): capture the metadata + WAV before deleting the cache file, so
                // audio retention (if on) can copy it in during finalize; then drop the cache original.
                val rtAccount = transcriptionAccount()
                val rtPreset = presetFor(rtAccount)
                val rtModel = rtAccount.realtimeModel.takeIf { it.isNotBlank() }
                    ?: rtPreset.defaultRealtimeModel ?: ""
                val rtCapture = HistoryCapture(
                    audioFile = wavFile?.takeIf { it.exists() && it.length() > 0L },
                    engine = HistoryEngine(
                        rtAccount.providerId, rtAccount.displayName.ifBlank { rtPreset.displayName }, rtModel,
                    ),
                    language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT } ?: "",
                    source = DictateHistorySource.REALTIME,
                )
                finalizeAndCommit(
                    appContext, transcript, recordedSeconds, live, alreadyFormatted = false,
                    finalizeViaComposing = true, capture = rtCapture, handedOver = realtimeHandedOver,
                )
                wavFile?.delete()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _interimText.value = ""
                val handedOver = withdrawPreview(appContext)
                realtimeTranscript.setLength(0)
                if (wavFile != null && wavFile.exists() && wavFile.length() > 0L) {
                    transcribe(context, wavFile, recordedSeconds, gate = false, handedOver = handedOver)
                } else {
                    _state.value = UiState.Error(appContext.getString(R.string.dictate__error_unknown))
                }
            }
        }
    }

    // --- Long-form segmented dictation (issue #170) ---------------------------------------------

    /**
     * Whether the next recording should run in segmented mode: the feature is on, it's not a live-prompt
     * recording, realtime streaming is not active, and the provider isn't in single-call multimodal mode
     * (which would format per segment).
     *
     * The keyboard and the floating button both qualify (#460); system voice input does not, since it hands
     * back one result and has no field to show progress in. Over the floating button the segments are held
     * back until stop, see [segmentsHeldBack].
     */
    private fun isSegmentedMode(context: Context): Boolean =
        prefs.dictate.longformMode.get().isEnabled &&
            outputTarget != OutputTarget.RECOGNITION_SERVICE &&
            !livePromptArmed &&
            !isRealtimeActive(context) &&
            !transcriptionAccount().transcriptionViaChat

    private fun initSegmented(appContext: Context) {
        segmentedActive = true
        segmentNextIndex = 0
        segmentCommitIndex = 0
        segmentResults.clear()
        segmentAudioFiles.clear()
        segmentInFlightCount = 0
        segmentStopped = false
        segmentRecordedSeconds = 0L
        _segmentFlushCount.value = 0
        // Keep + merge the segment audio only when the history feature would actually store it.
        segmentKeepAudio = prefs.dictate.historyEnabled.get() && prefs.dictate.historyAudioRetention.get()
        realtimeShown.setLength(0)
        realtimeShownTranscript = ""
        realtimeHandedOver = ""
        realtimeTranscript.setLength(0)
        // Segmented dictation has its own rule for holding the text back ([segmentsHeldBack]); the
        // hidden-preview mode belongs to realtime alone, and the two are mutually exclusive (see
        // [isSegmentedMode]).
        realtimeHidden = false
        // Reuse the realtime shown-text context so the existing cancel/interrupt cleanup clears the preview.
        realtimeContext = appContext
        realtimeCancelled = false
        _segmentsInFlight.value = 0
        _segmentedRecording.value = true
    }

    private fun resetSegmentedState() {
        segmentGeneration++
        segmentedActive = false
        segmentNextIndex = 0
        segmentCommitIndex = 0
        segmentResults.clear()
        segmentAudioFiles.clear() // files themselves are deleted by finalize/cancel, not here
        segmentInFlightCount = 0
        segmentStopped = false
        segmentFallbackEngine = null
        segmentAnsweredAsConfigured = false
        segmentVad?.release()
        segmentVad = null
        _interimText.value = "" // the floating button's caption of the held-back segments
        _segmentsInFlight.value = 0
        _segmentedRecording.value = false
    }

    /**
     * Whether a segmented dictation keeps its segments out of the field until stop: over the floating button
     * (#460), for the reason realtime holds its words back there (#357) — a field written piece by piece
     * through accessibility drifts out of step with what we believe is in it. The segments go to the
     * button's live caption instead, and the field gets one verified insert at the end.
     */
    private fun segmentsHeldBack(): Boolean = outputTarget == OutputTarget.OVERLAY

    /**
     * Cuts the current segment and keeps recording (the "Next segment" button, issue #170). The cut audio
     * is transcribed in the background and its raw text appended to the field in order. No-op unless a
     * segmented recording is actually in progress.
     */
    fun flushSegment(context: Context) {
        flushSegment(context, splitterAlreadyReset = false)
    }

    private fun flushSegment(context: Context, splitterAlreadyReset: Boolean) {
        if (!segmentedActive || _state.value !is UiState.Recording) return
        val appContext = context.applicationContext
        val generation = segmentGeneration
        // Manual cuts reset the analyzer at call time so audio queued after this point belongs to the next
        // turn. Automatic cuts already reset atomically inside LiveSpeechSplitter before invoking us.
        if (!splitterAlreadyReset) segmentVad?.notifyCut()
        scope.launch {
            val assigned = segmentMutex.withLock {
                if (!segmentedActive || _state.value !is UiState.Recording) return@withLock null
                val i = segmentNextIndex++
                val w = withContext(Dispatchers.IO) { recorder?.rotate() }
                if (segmentKeepAudio && w != null && w.exists() && w.length() > 0L) segmentAudioFiles[i] = w
                segmentInFlightCount++
                _segmentsInFlight.value = segmentInFlightCount
                _segmentFlushCount.value = _segmentFlushCount.value + 1
                i to w
            } ?: return@launch
            val (idx, wav) = assigned
            if (wav != null && wav.exists() && wav.length() > 0L) {
                launchSegmentTranscription(appContext, idx, wav, generation)
            } else {
                // Nothing captured since the last cut (e.g. a double tap): keep the index sequence
                // contiguous so the ordered drain never stalls.
                onSegmentResult(appContext, idx, "", generation)
            }
        }
    }

    /**
     * Cancel-button behaviour: in long-form mode, tapping the trash button ends the recording but keeps
     * everything transcribed so far — the already-committed segments are finalized and saved to history as
     * usual; only the current, not-yet-cut chunk is thrown away instead of being transcribed (#183).
     * Outside long-form it aborts the whole recording ([cancelRecording]). Used by the Smartbar, the legacy
     * layout and the floating button's × (#460), so every cancel button behaves the same.
     */
    fun cancelOrDiscardSegment(context: Context) {
        if (segmentedActive && _state.value is UiState.Recording) {
            stopSegmentedAndFinalize(context, discardFinal = true)
        } else {
            cancelRecording()
        }
    }

    /**
     * Stops a segmented recording: cuts the final open segment, then finishes once every queued segment
     * has transcribed and been committed in order — at which point the whole assembled transcript runs
     * through the normal post-processing once (auto-format + prompts + mappings) and replaces the preview.
     *
     * [discardFinal] (the cancel button, #183) throws the final open chunk away instead of transcribing it,
     * so the session still finalizes + saves to history with everything captured up to the last cut, but
     * the current unfinished utterance is dropped.
     */
    private fun stopSegmentedAndFinalize(context: Context, discardFinal: Boolean = false) {
        val appContext = context.applicationContext
        unregisterScreenOffReceiver()
        segmentRecordedSeconds = recordedSecondsOf(_state.value)
        _segmentedRecording.value = false
        setTranscribing()
        val generation = segmentGeneration
        scope.launch {
            val assigned = segmentMutex.withLock {
                val i = segmentNextIndex++
                val activeRecorder = recorder
                recorder = null
                val w = withContext(Dispatchers.IO) { activeRecorder?.stop() }
                cleanupAudioRouting()
                if (discardFinal) {
                    // Deleted chunk: drop its audio so it lands in neither the transcript nor the history WAV.
                    withContext(Dispatchers.IO) { runCatching { w?.delete() } }
                } else if (segmentKeepAudio && w != null && w.exists() && w.length() > 0L) {
                    segmentAudioFiles[i] = w
                }
                segmentStopped = true
                segmentInFlightCount++
                _segmentsInFlight.value = segmentInFlightCount
                i to w
            }
            val (idx, wav) = assigned
            if (!discardFinal && wav != null && wav.exists() && wav.length() > 0L) {
                launchSegmentTranscription(appContext, idx, wav, generation)
            } else {
                onSegmentResult(appContext, idx, "", generation)
            }
        }
    }

    /**
     * The stop button while a stopped long-form dictation still waits for its last segments (#172). It used
     * to cancel nothing: the state went to Idle, the segment jobs ran on and committed their text anyway,
     * and one still running when the next recording started wrote into that one.
     *
     * Now it keeps what has already landed in the field, gives up on everything still pending, and finishes
     * with that through the normal end (formatting, prompts, history) — the promise the trash button makes
     * during the recording (#183). A segment that finished out of order is dropped with the rest: it sits
     * behind a gap that is never going to close.
     */
    private fun abandonPendingSegments(appContext: Context) {
        scope.launch {
            segmentMutex.withLock {
                segmentGeneration++ // whatever is still in flight now belongs to no dictation
                segmentJobs.forEach { it.cancel() }
                segmentJobs.clear()
                segmentResults.clear()
                segmentAudioFiles.keys.filter { it >= segmentCommitIndex }.forEach { idx ->
                    segmentAudioFiles.remove(idx)?.let { runCatching { it.delete() } }
                }
                segmentInFlightCount = 0
                _segmentsInFlight.value = 0
                segmentStopped = true
            }
            finalizeSegmentedEnd(appContext)
        }
    }

    private fun launchSegmentTranscription(appContext: Context, idx: Int, wav: File, generation: Int) {
        val job = scope.launch {
            // Best-effort cross-segment continuity: bias the recognizer with what's been said so far.
            val continuity = realtimeTranscript.toString()
            // One retry before giving up; a still-failed segment leaves a gap (no placeholder), but its
            // audio is preserved in the merged history WAV so nothing is truly lost.
            val segment = transcribeSegmentRaw(appContext, wav, continuity)
                ?: transcribeSegmentRaw(appContext, wav, continuity)
            // Keep the WAV when it will be merged into the history audio; otherwise drop it now.
            if (!segmentKeepAudio) withContext(Dispatchers.IO) { runCatching { wav.delete() } }
            onSegmentResult(appContext, idx, segment?.text ?: "", generation, segment?.fallbackEngine)
        }
        segmentJobs.add(job)
        job.invokeOnCompletion { segmentJobs.remove(job) }
    }

    /**
     * Buffers a finished segment's raw text and drains the ordered commit queue: appends every now-in-order
     * segment to the field's live preview. When the last segment lands after a stop, runs the end finalize.
     */
    private suspend fun onSegmentResult(
        appContext: Context,
        idx: Int,
        text: String,
        generation: Int,
        fallbackEngine: HistoryEngine? = null,
    ) {
        val tightening = appContext.transcriptTighteningSymbols()
        val shouldFinish = segmentMutex.withLock {
            if (generation != segmentGeneration) return@withLock false
            segmentResults[idx] = text
            if (text.isNotBlank()) {
                if (fallbackEngine != null) segmentFallbackEngine = fallbackEngine else segmentAnsweredAsConfigured = true
            }
            while (segmentResults.containsKey(segmentCommitIndex)) {
                val raw = segmentResults.remove(segmentCommitIndex)!!.trim()
                segmentCommitIndex++
                if (raw.isNotEmpty()) {
                    // The whole dictation, whatever happens to it in the field: the finalize records it.
                    TranscriptJoin.appendPiece(realtimeTranscript, raw, tightening)
                    if (segmentsHeldBack()) {
                        // Nothing reaches the field before stop; the caption by the button shows it instead.
                        _interimText.value = realtimeTranscript.toString()
                        continue
                    }
                    val outSink = sink(appContext)
                    // Segments never revise each other, so edited ones simply stay the user's, and the
                    // preview starts afresh at the cursor with this one (#421).
                    handOverIfEdited(outSink)
                    val own = TranscriptHandover.continuation(realtimeHandedOver, realtimeTranscript.toString())
                    val prev = realtimeShown.toString()
                    // The first segment is the one that meets the field's own text (#443).
                    if (prev.isEmpty()) dictationLead = leadFor(appContext, own)
                    val full = dictationLead + own
                    runCatching { outSink.setDictationPreview(full, prev) }
                    realtimeShown.setLength(0)
                    realtimeShown.append(full)
                    realtimeShownTranscript = realtimeTranscript.toString()
                }
            }
            segmentInFlightCount--
            _segmentsInFlight.value = segmentInFlightCount.coerceAtLeast(0)
            segmentStopped && segmentInFlightCount <= 0
        }
        if (shouldFinish) finalizeSegmentedEnd(appContext)
    }

    /**
     * All segments are in and committed as a raw preview; run the whole dictation through the normal
     * post-processing once and replace the preview with the finished (formatted/reworded) text. Over the
     * floating button there is no preview ([segmentsHeldBack]), so the replace is the one insert.
     */
    private suspend fun finalizeSegmentedEnd(appContext: Context) {
        val account = transcriptionAccount()
        val preset = presetFor(account)
        val model = transcriptionModelFor(appContext, account, preset)
        val assembled = realtimeTranscript.toString().trim()
        val handedOver = realtimeHandedOver
        val recordedSeconds = segmentRecordedSeconds
        // Snapshot the kept segment files (in cut order) before resetting; merge them into one WAV so the
        // whole dictation has a single retained-audio file in the history (issue #170 / #140 reuse).
        val keepAudio = segmentKeepAudio
        val audioFiles = segmentAudioFiles.toSortedMap().values.filter { it.exists() && it.length() > 0L }
        val fallbackEngine = segmentFallbackEngine
        val partlyAsConfigured = segmentAnsweredAsConfigured
        resetSegmentedState()
        val mergedWav = if (keepAudio && audioFiles.isNotEmpty()) {
            val merged = File(appContext.cacheDir, "dictate_seg_merged.wav")
            merged.delete()
            if (withContext(Dispatchers.IO) { AudioConcat.concat(audioFiles, merged) } && merged.exists() && merged.length() > 0L) merged else null
        } else null
        withContext(Dispatchers.IO) { audioFiles.forEach { runCatching { it.delete() } } }
        if (assembled.isEmpty()) {
            runCatching { sink(appContext).clearDictationPreview(realtimeShown.toString()) }
            realtimeShown.setLength(0)
            mergedWav?.delete()
            _state.value = UiState.Idle
            return
        }
        val configured = HistoryEngine(account.providerId, account.displayName.ifBlank { preset.displayName }, model)
        val capture = HistoryCapture(
            audioFile = mergedWav, // the merged segment audio, or null when retention is off
            engine = fallbackEngine?.let { configured.fellBackTo(it, partly = partlyAsConfigured) } ?: configured,
            language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT } ?: "",
            source = if (outputTarget == OutputTarget.OVERLAY) DictateHistorySource.OVERLAY else DictateHistorySource.KEYBOARD,
        )
        finalizeAndCommit(
            appContext, assembled, recordedSeconds, live = false,
            alreadyFormatted = false, finalizeViaComposing = true, capture = capture, handedOver = handedOver,
        )
        mergedWav?.delete() // history already copied it during finalize
    }

    /** A segment's raw text, and the fallback that transcribed it when one had to (#457, #455). */
    private class SegmentText(val text: String, val fallbackEngine: HistoryEngine?)

    /**
     * Transcribes one cut segment to RAW text (no formatting/rewording — that runs once at the end). Uses
     * the dedicated STT endpoint (never the multimodal chat path, which would format), with the offline
     * on-device fallback. Returns null on failure (the segment is dropped, keeping the sequence contiguous).
     *
     * [fallbackAccount] is the provider a segment goes to when the active one could not be reached (#455):
     * each segment asks the active provider first, so a server that comes back mid-dictation is used again.
     */
    private suspend fun transcribeSegmentRaw(
        appContext: Context,
        wav: File,
        continuity: String,
        fallbackAccount: ProviderAccount? = null,
    ): SegmentText? {
        // Silence gate (issue #93): skip a segment that is just silence — e.g. the trailing pause before a
        // cut/stop — so the model can't hallucinate ghost text ("Vielen Dank" / "Thanks for watching")
        // from it. Fails open (transcribes) if the check can't run, so real speech is never dropped.
        if (prefs.dictate.skipSilentRecordings.get() && !SpeechGate.hasSpeech(appContext, wav)) return null
        val account = localTranscriptionAccount()
        val model = transcriptionModelFor(
            appContext, account, presetFor(account), ProviderRegistry.LOCAL.defaultTranscriptionModel.orEmpty(),
        )
        val language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT }
        val style = transcriptionStylePrompt()
        val prompt = continuity.takeLast(200).trim().let { if (it.isEmpty()) style else "$it $style".trim() }
        if (!LocalModelManager.isInstalled(appContext, model)) return null
        val request = TranscriptionRequest(
            audioFile = wav, model = model, language = language, prompt = prompt,
            expectedLanguages = expectedLanguages(),
        )
        var fallbackEngine: HistoryEngine? = null
        return try {
            val result = withContext(Dispatchers.IO) {
                LocalTranscriptionProvider(
                    LocalTranscriptionProvider.modelDir(appContext, model),
                    timeoutMillis = localTimeoutMillis(),
                    tighteningSymbols = appContext.transcriptTighteningSymbols(),
                ).transcribe(request)
            }
            SegmentText(result.text, null)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    // --- Output behavior + resend (roadmap section 10) ------------------------------------------

    /**
     * Resolves the output sink for the current dictation: where the finished text is written and how the
     * focused field is read. The keyboard's own editor ([ImeDictationSink]) for in-keyboard dictation, or
     * an accessibility-backed sink ([dev.patrickgold.florisboard.dictate.overlay.AccessibilitySink]) when
     * the dictation was started from the floating button (issue #88) and must inject into another app.
     * This single seam keeps the rest of the engine editor-agnostic.
     */
    private fun sink(context: Context): DictationSink = when (outputTarget) {
        OutputTarget.IME -> ImeDictationSink(context)
        OutputTarget.OVERLAY -> AccessibilitySink()
        // System voice input (#67): the finished text is handed back to the OS via the RecognitionService
        // callback (the calling app/keyboard inserts it), not written into a field ourselves.
        OutputTarget.RECOGNITION_SERVICE -> RecognitionSink()
    }

    /**
     * Commits [text] into the focused field honoring the output prefs: either all at once
     * ([prefs.dictate.instantOutput]) or "typed" character by character at the configured speed, then
     * an optional auto-enter (10.1). Runs on the caller's (Main) coroutine, so the typewriter delay
     * suspends rather than blocks.
     */
    private suspend fun commitOutput(context: Context, text: String): Boolean {
        // Empty result (e.g. silence): nothing to insert — a no-op is a success, not a failed write.
        if (text.isEmpty()) return true
        val sink = sink(context)
        var committed: Boolean
        // System voice input (#67) returns the whole result at once — no typewriter animation, which only
        // makes sense when we're the one typing into a visible field.
        if (prefs.dictate.instantOutput.get() || outputTarget == OutputTarget.RECOGNITION_SERVICE) {
            committed = writeToSink(sink, text)
        } else {
            val perChar = perCharDelayMs(prefs.dictate.outputSpeed.get())
            val results = ArrayList<Boolean>(text.length)
            // Verify the first character only: it is the one [typedCommitLanded] judges, and a read-back
            // per character would be hundreds of extra round trips into the host app (issue #277).
            text.forEachIndexed { i, ch ->
                results.add(writeToSink(sink, ch.toString(), verify = i == 0))
                delay(perChar)
            }
            committed = typedCommitLanded(results)
        }
        // No auto-enter on an empty result (e.g. silence): don't fire a stray newline into the field (#124),
        // and none when nothing landed either (#278) — an Enter on a swallowed insert sends an empty message.
        if (committed && prefs.dictate.autoEnter.get()) {
            sink.performEnter()
        }
        return committed
    }

    /**
     * Gives the live preview to the user if they have edited it (issue #421), and says whether it did.
     *
     * [realtimeShown] is what the dictation believes sits in front of the cursor, and every write is a
     * diff against it. Once the user has deleted a word somebody else said, or cleared the field, a write
     * against that belief deletes their own words and types the removed ones back in. So the field as it
     * stands becomes the baseline instead: everything the user saw is theirs ([realtimeHandedOver]) and
     * never revised again, and the dictation goes on at the cursor with what is said after it.
     *
     * Cutting off the provider's revisions at that point is the price, and the right one: a revision of
     * text the user has already corrected by hand could only undo the correction.
     */
    private fun handOverIfEdited(sink: DictationSink): Boolean {
        if (realtimeShown.isEmpty()) return false
        if (runCatching { sink.holdsPreview(realtimeShown.toString()) }.getOrDefault(true)) return false
        realtimeHandedOver = realtimeShownTranscript
        realtimeShown.setLength(0)
        return true
    }

    /**
     * Takes the live preview back out of the field for a batch transcription to replace (the stream failed,
     * or ran out of time), and returns what the user had taken over of it (#421). That part stays in the
     * field as they left it, so the batch text has to leave it out. An edit since the last update counts.
     */
    private fun withdrawPreview(appContext: Context): String {
        val outSink = sink(appContext)
        handOverIfEdited(outSink)
        runCatching { outSink.clearDictationPreview(realtimeShown.toString()) }
        realtimeShown.setLength(0)
        return realtimeHandedOver
    }

    /**
     * The separator a dictation of [text] needs against what already sits in front of the cursor: a space,
     * or nothing (issue #443). Two dictations in a row used to be glued — `1, 2, 3.Testing` — on the
     * keyboard as much as over the floating button, because the keyboard only ever spaced a seam it made
     * itself, after a picked suggestion. The rule is [TranscriptJoin.separatorBefore]; this only asks the
     * field, off the main thread for the overlay like every other accessibility call.
     *
     * Nothing for the system voice input: the calling app joins our result into its own field.
     */
    private suspend fun leadFor(appContext: Context, text: String): String {
        if (text.isEmpty() || outputTarget == OutputTarget.RECOGNITION_SERVICE) return ""
        val sink = sink(appContext)
        // One character is all the rule looks at.
        val before = if (outputTarget == OutputTarget.OVERLAY) {
            withContext(Dispatchers.IO) { sink.textBeforeCursor(1) }
        } else {
            sink.textBeforeCursor(1)
        }
        return appContext.transcriptSeparatorBefore(before, text)
    }

    /**
     * Writes into the sink, off the main thread when it goes through the accessibility service.
     *
     * A floating-button insert is not a quick call: it resolves the field, focuses it, writes, and then
     * waits — sometimes a few hundred milliseconds — for the field to show the write. On the main thread
     * that is the bubble visibly freezing mid-dictation, which is exactly what it looked like. The
     * keyboard's own sink stays where it is: it talks to the editor through the IME's InputConnection,
     * which belongs to this thread.
     */
    private suspend fun writeToSink(sink: DictationSink, text: String, verify: Boolean = true): Boolean =
        if (outputTarget == OutputTarget.OVERLAY) {
            withContext(Dispatchers.IO) { sink.commitText(text, verify) }
        } else {
            sink.commitText(text, verify)
        }

    /**
     * Whether a character-by-character commit counts as having landed. **The first write decides.**
     *
     * Latching on any single refusal (issue #277) turned one flake out of hundreds into a total failure:
     * a 400-character dictation makes 400 separate calls into the accessibility service, and a scroll, a
     * recomposition or the host app rebuilding its field is enough for one of them to come back false —
     * whereupon the user was told "Couldn't insert into this app" while the text sat visibly in the field.
     * Those false alarms are the reason read-back verification was ruled out for the insert path, so they
     * are worth removing at the source.
     *
     * If the field took the *first* character it accepts our writes; what follows is the app's business.
     * An empty result cannot fail, hence true.
     */
    internal fun typedCommitLanded(writeResults: List<Boolean>): Boolean = writeResults.firstOrNull() ?: true

    /** Per-character delay for the typewriter output: speed 1 → 100 ms … 5 → 20 ms … 10 → 10 ms (legacy mapping). */
    private fun perCharDelayMs(speed: Int): Long = (100L / speed.coerceIn(1, 10)).coerceAtLeast(1L)

    // --- Retained audio + unified resend (failed transcription / interrupted recording) ---------

    /**
     * Retains [audioFile] after a failed transcription so the error chip's resend button can retry it,
     * if the resend button is enabled and the file is usable; returns true when kept. The file stays in
     * the cache (a transient failure does not need to survive process death). Any previously kept audio
     * is discarded first.
     */
    private fun retainFailedAudio(
        audioFile: File,
        wasLive: Boolean,
        recordedSeconds: Long,
        // The history row this failure already wrote (issue #358), carried so a resend can finish it.
        historyId: Long?,
        // Keep even when the resend button is off — used for exportable failures so the recording can be
        // saved (issue #144); otherwise retention is gated on the resend-button preference.
        force: Boolean = false,
    ): Boolean {
        if (!force && !prefs.dictate.resendButton.get()) return false
        if (!audioFile.exists() || audioFile.length() == 0L) return false
        if (retained?.file != audioFile) discardRetainedAudio()
        retained = RetainedAudio(audioFile, RetainReason.FAILED, wasLive, recordedSeconds, historyId)
        return true
    }

    /** Deletes the kept audio (if any), forgets it, and clears the persisted interrupted-audio marker. */
    fun discardRetainedAudio() {
        retained?.file?.takeIf { it.exists() }?.delete()
        retained = null
        scope.launch { clearInterruptedAudioPref() }
    }

    /**
     * Re-sends the currently kept audio — used by *both* the error-resend chip and the interrupted-
     * recording chip (unified path). Repeats the original mode (a kept live-prompt resends as a live
     * prompt) and re-credits the recorded seconds towards the nudges. No-op unless we are idle/showing
     * one of the resend chips and a usable file exists. Interrupted audio is claimed (its persisted
     * marker cleared) up front, so a crash mid-transcription cannot re-offer the same recording.
     *
     * [target] is the surface the tap came from: the floating button passes OVERLAY, the voice-input view
     * RECOGNITION_SERVICE, and the keyboard's chips pass nothing and claim the keyboard (#409). Inheriting
     * the latch was wrong as soon as the floating button could resend a dictation the keyboard had started
     * (#437): its text went to the keyboard's editor, which was not on screen.
     */
    fun sendRetainedAudio(context: Context, target: OutputTarget? = null) {
        if (_state.value !is UiState.Error && _state.value !is UiState.Interrupted &&
            _state.value !is UiState.Idle
        ) return
        val r = retained
        if (r == null || !r.file.exists() || r.file.length() == 0L) {
            discardRetainedAudio()
            _state.value = UiState.Idle
            return
        }
        if (target != null) outputTarget = target else claimKeyboardOutput()
        if (r.reason == RetainReason.INTERRUPTED) scope.launch { clearInterruptedAudioPref() }
        // A user-initiated resend of already-captured audio is sent as-is (no silence gate — issue #93).
        // The failure it is retrying already has a history row, so this rewrites that one (#358) rather
        // than filing a second entry for the same recording — the chip and the ↻ in the History panel are
        // the same button on the same audio, and they were disagreeing about that.
        transcribe(context, r.file, r.seconds, gate = false, replayHistoryId = r.historyId)
    }

    /**
     * Exports the kept recording to the public Downloads folder (issue #144), so a dictation that failed
     * for a non-retryable reason (too large / unsupported format) can be recovered instead of lost. On
     * success the audio is dropped and a toast confirms the file name; on failure it is kept so the user
     * can try again. No-op unless a usable kept file exists.
     */
    fun saveRetainedAudio(context: Context) {
        val r = retained
        if (r == null || !r.file.exists() || r.file.length() == 0L) {
            discardRetainedAudio()
            _state.value = UiState.Idle
            return
        }
        val appContext = context.applicationContext
        val src = r.file
        scope.launch {
            val savedName = withContext(Dispatchers.IO) { exportAudioToDownloads(appContext, src) }
            val message = if (savedName != null) {
                appContext.getString(R.string.dictate__audio_saved, savedName)
            } else {
                appContext.getString(R.string.dictate__audio_save_failed)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            // Keep the audio on failure so the user can retry the export; drop it once safely saved.
            if (savedName != null) discardRetainedAudio()
            _state.value = UiState.Idle
        }
    }

    /**
     * Copies [src] into Downloads/Dictate via MediaStore (API 29+) or the public dir. Returns the file
     * name, or null on failure.
     *
     * Name and MIME follow what is actually in the file (#322). This used to say `.wav` and `audio/wav`
     * unconditionally, which is right for a dictation and wrong for a failed *import* — the retained
     * audio there is the file the user brought, so an Ogg voice note landed in their Downloads folder
     * registered as a WAV, where some players simply refuse it.
     */
    private fun exportAudioToDownloads(context: Context, src: File): String? = runCatching {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val container = AudioContainer.of(src)
        val extension = container.extension.ifEmpty { src.extension.lowercase().ifEmpty { "wav" } }
        val name = "dictate-recording-$stamp.$extension"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, container.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dictate")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Dictate")
            dir.mkdirs()
            src.inputStream().use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        name
    }.getOrNull()

    /**
     * Continues an interrupted recording instead of sending it: the kept audio becomes a carry-over
     * segment and a fresh recording starts, with the timer seeded so it shows the running total. When the
     * user finally stops, the carry-over and the new segment are merged into one audio and transcribed
     * together (see [stopAndTranscribe]); if the keyboard closes again first, both are merged back into
     * the persisted interrupted file (see [stashRecording]). No-op unless the interrupted chip is
     * showing with a usable file.
     */
    fun continueInterruptedRecording(context: Context) {
        if (_state.value !is UiState.Interrupted) return
        val r = retained
        if (r == null || r.reason != RetainReason.INTERRUPTED || !r.file.exists() || r.file.length() == 0L) {
            discardRetainedAudio()
            _state.value = UiState.Idle
            return
        }
        // Claim the interrupted audio as the carry-over and clear the offer/marker, then record on top.
        retained = null
        carryOverAudio = r.file
        carryOverSeconds = r.seconds
        scope.launch { clearInterruptedAudioPref() }
        _state.value = UiState.Idle
        // coerceAtLeast(1) keeps the "continuation" path (non-zero seed) so the carry-over is not dropped
        // by the normal-start cleanup, even for a sub-second carried-over segment.
        startRecording(context, seedAccumulatedMs = (r.seconds * 1000L).coerceAtLeast(1L))
    }

    /** Deletes the carry-over recording segment (if any) and forgets it. */
    private fun discardCarryOver() {
        carryOverAudio?.takeIf { it.exists() }?.delete()
        carryOverAudio = null
        carryOverSeconds = 0L
    }

    // --- Interrupted recording (keyboard closed mid-recording) ----------------------------------

    /** Stable on-disk location for an interrupted recording: in filesDir so it survives the cache wipe. */
    private fun interruptedAudioFile(context: Context): File =
        File(context.applicationContext.filesDir, "dictate_interrupted.wav")

    /**
     * Called when the keyboard window is hidden or the IME service is torn down (see
     * [FlorisImeService.onWindowHidden] / [FlorisImeService.onDestroy]) — but only ends a dictation the
     * keyboard actually owns. A recording driven by the floating button or the system voice-input service
     * is none of the keyboard's business and is left running (#293): rotating the device tears the target
     * app's window down and takes the keyboard with it, which used to end a bubble dictation that had
     * nothing to do with any of it. Their teardown signals are their own service going away and the screen
     * turning off.
     */
    fun stashRecordingOnHide(context: Context) {
        if (foreignDictationInFlight()) return
        stashRecording(context)
    }

    /**
     * True while a dictation the keyboard does not own is starting or running: the floating button (#88)
     * or the system voice-input service (#67). Both deliberately outlive the keyboard window — that
     * independence is the floating button's whole purpose.
     *
     * The live-dictation half of the check is not optional: [outputTarget] is latched by every start and
     * never reset, so on its own it would also silence the keyboard's ordinary idle teardown for the rest
     * of the process's life after a single bubble dictation. [startJob] covers the gap between the tap and
     * [UiState.Recording], during which audio focus, Bluetooth SCO and the realtime socket are still
     * opening — a rotation landing there would otherwise cancel a dictation that had just begun.
     */
    private fun foreignDictationInFlight(): Boolean =
        outputTarget != OutputTarget.IME && (
            startJob?.isActive == true ||
                _state.value is UiState.Recording ||
                _state.value is UiState.Transcribing ||
                _state.value is UiState.Rewording
            )

    /**
     * Claims the output latch for the keyboard, for an action the *keyboard* started.
     *
     * [outputTarget] is set by every start and never reset (see [foreignDictationInFlight]), so after a
     * single floating-button or system-voice-input dictation it keeps pointing at the accessibility sink
     * or the recognition bridge for the rest of the process's life. A later keyboard action that reads
     * and writes the field through [sink] then works on a surface that is no longer there, and does it
     * silently: [RecognitionSink] reports an empty selection and an empty field, so a prompt sees nothing
     * to rewrite and returns, and a re-insert hands its text to a bridge with no receiver left. Both look
     * exactly like a dead button (issue #409) — the report this fixes described it as "takes no action
     * unless audio was transcribed in that session", which is precisely the moment the latch is IME again.
     *
     * A foreign dictation that is genuinely in flight keeps the latch: it owns the output it is about to
     * produce, and taking it away mid-flight would deliver that text into the keyboard's field instead
     * (the ownership rule from issue #293).
     */
    private fun claimKeyboardOutput() {
        if (foreignDictationInFlight()) return
        outputTarget = OutputTarget.IME
    }

    /**
     * The floating button's service is going away (switched off in the system settings, unbound by the
     * system) while it owns a dictation. Nobody is left to show or inject it, so it is finalized and kept
     * here — the duty the keyboard used to discharge by accident, now carried by the owner (#293). No-op
     * for a keyboard dictation: turning the accessibility service off must not take one of those with it.
     */
    fun stashRecordingOnOverlayGone(context: Context) {
        if (outputTarget != OutputTarget.OVERLAY) return
        if (_state.value !is UiState.Recording && startJob?.isActive != true) return
        stashRecording(context)
    }

    /**
     * Finalizes and keeps an in-progress recording instead of discarding it: the audio is stopped cleanly
     * (so the WAV is valid even if the recorder/process is destroyed afterwards) and moved to
     * [interruptedAudioFile], with its metadata mirrored to prefs. The next keyboard open then offers to
     * send it (see [maybeOfferInterruptedRecording]). Outside the recording state this falls back to the
     * normal teardown ([cancelRecording]).
     *
     * A long-form dictation is finished instead, as if stop had been tapped. Its earlier segments are no
     * longer in the recording — each cut handed them on — so stashing kept only the part since the last cut,
     * while the segments still transcribing ran on into a dictation that was over and never finished it
     * (#460: the floating button's screen-off made this reachable). Finishing keeps all of it: text that can
     * no longer be inserted is kept for re-insert and in the history like any other dictation's, and over
     * the floating button a refused insert puts it on the clipboard.
     */
    private fun stashRecording(context: Context) {
        val current = _state.value
        val activeRecorder = recorder
        if (current !is UiState.Recording || activeRecorder == null) {
            // Not actively recording (e.g. a start that never got going): use the normal teardown.
            cancelRecording()
            return
        }
        if (segmentedActive) {
            stopSegmentedAndFinalize(context)
            return
        }
        recorder = null
        // Realtime (#128): drop the stream; the WAV is stashed below and recoverable via batch as usual.
        realtimeCancelled = true
        realtimeSession?.cancel()
        realtimeSession = null
        realtimeClosed = null
        _interimText.value = ""
        realtimeContext?.let { ctx -> runCatching { sink(ctx).clearDictationPreview(realtimeShown.toString()) } }
        realtimeShown.setLength(0)
        realtimeTranscript.setLength(0)
        realtimeContext = null
        unregisterScreenOffReceiver()
        val seconds = recordedSecondsOf(current)
        val wasLive = livePromptArmed
        val audioFile = activeRecorder.stop()
        cleanupAudioRouting()
        _state.value = UiState.Idle

        // Interrupted-recording recovery is disabled while instant recording is on (issue #120): every
        // keyboard open auto-starts a recording, so a stashed segment would only block the next open.
        // Discard the finalized audio instead of keeping it (the user is told about this trade-off when
        // enabling instant recording, and the whole feature only applies with instant recording off).
        if (prefs.dictate.instantRecording.get()) {
            audioFile?.takeIf { it.exists() }?.delete()
            discardCarryOver()
            scope.launch { clearInterruptedAudioPref() }
            return
        }

        val carry = carryOverAudio
        carryOverAudio = null
        val dest = interruptedAudioFile(context)
        val newValid = audioFile != null && audioFile.exists() && audioFile.length() > 0L
        // Resolve the audio to keep (and its length). dest == carry for a continuation, so when both
        // segments are present they are merged into a cache temp first and then moved onto dest.
        val keptSeconds: Long? = when {
            carry != null && newValid -> {
                val merged = File(context.applicationContext.cacheDir, MERGED_AUDIO_NAME)
                val ok = AudioConcat.concat(listOf(carry, audioFile!!), merged)
                if (ok && merged.exists() && merged.length() > 0L) {
                    carry.delete()
                    audioFile.delete()
                    runCatching {
                        dest.delete()
                        if (!merged.renameTo(dest)) {
                            merged.copyTo(dest, overwrite = true)
                            merged.delete()
                        }
                    }
                    if (dest.exists() && dest.length() > 0L) seconds else null
                } else {
                    // Merge failed (rare): keep the carry-over (already at dest), drop the new segment.
                    merged.delete()
                    audioFile.delete()
                    if (carry.exists() && carry.length() > 0L) carryOverSeconds else null
                }
            }
            // Continuation, but the new segment is unusable: keep the carried-over segment alone.
            carry != null -> if (carry.exists() && carry.length() > 0L) carryOverSeconds else null
            // Plain recording interrupted: move the finalized segment out of the cache into filesDir.
            newValid -> {
                runCatching {
                    dest.parentFile?.mkdirs()
                    dest.delete()
                    if (!audioFile!!.renameTo(dest)) {
                        audioFile.copyTo(dest, overwrite = true)
                        audioFile.delete()
                    }
                }
                if (dest.exists() && dest.length() > 0L) seconds else null
            }
            else -> null
        }
        carryOverSeconds = 0L
        if (keptSeconds == null) {
            // Nothing usable was kept; make sure no stale offer remains.
            scope.launch { clearInterruptedAudioPref() }
            return
        }
        // Persist the marker + metadata so the offer can be restored even after a process death.
        scope.launch {
            prefs.dictate.interruptedAudioSeconds.set(keptSeconds)
            prefs.dictate.interruptedAudioLive.set(wasLive)
            prefs.dictate.interruptedAudioPending.set(true)
        }
    }

    /**
     * On keyboard open, restores the "recording interrupted — send it?" offer if an interrupted audio
     * file is waiting. Returns true when the offer is now shown, so the caller can skip instant-recording.
     * No-op unless idle. A stale marker without a usable file is cleared.
     */
    fun maybeOfferInterruptedRecording(context: Context): Boolean {
        if (_state.value !is UiState.Idle) return false
        if (!prefs.dictate.interruptedAudioPending.get()) return false
        val file = interruptedAudioFile(context)
        if (!file.exists() || file.length() == 0L) {
            scope.launch { clearInterruptedAudioPref() }
            return false
        }
        val seconds = prefs.dictate.interruptedAudioSeconds.get()
        retained = RetainedAudio(file, RetainReason.INTERRUPTED, prefs.dictate.interruptedAudioLive.get(), seconds)
        _state.value = UiState.Interrupted(seconds)
        return true
    }

    /** Clears the persisted interrupted-audio marker (best-effort; the file itself is handled separately). */
    private suspend fun clearInterruptedAudioPref() {
        if (prefs.dictate.interruptedAudioPending.get()) {
            prefs.dictate.interruptedAudioPending.set(false)
        }
    }

    // --- Re-insert last dictation (issue #111) --------------------------------------------------

    /**
     * Persists [text] as the last successful dictation so the "Re-insert last dictation" Smartbar action
     * can recover it after the field is cleared (rotation, context switch, host app refreshing its
     * state). No-op when the feature is off or the text is blank. Held until the next successful
     * dictation overwrites it; stored to a pref so it survives the IME process being killed.
     *
     * Not for a password or incognito field, for the history's reason (#383): the pref is a file on disk,
     * and it goes into the platform backup with the rest of the settings.
     */
    private suspend fun rememberLastDictation(appContext: Context, text: String) {
        if (!prefs.dictate.rememberLastDictation.get() || text.isBlank()) return
        if (isSensitiveDictationField(appContext)) return
        prefs.dictate.lastDictation.set(text)
    }

    /**
     * Whether a re-insertable last dictation exists (feature enabled and a non-empty cache). Read
     * synchronously by the Smartbar action's enabled-state evaluation, so the button greys out when
     * there is nothing to re-insert.
     */
    fun hasLastDictation(): Boolean =
        prefs.dictate.rememberLastDictation.get() && prefs.dictate.lastDictation.get().isNotEmpty()

    /** Whether the history feature is enabled — gates the history Smartbar button's enabled state (#140). */
    fun isHistoryEnabled(): Boolean = prefs.dictate.historyEnabled.get()

    /**
     * Re-inserts the last successful dictation into the focused field (issue #111). The cached text is
     * committed verbatim (no auto-formatting/auto-enter, which already ran on the original) and is kept,
     * so it can be re-inserted repeatedly until the next dictation replaces it. No-op while a
     * recording/transcription/rewording is in flight, or when there is nothing cached.
     */
    fun reinsertLastDictation(context: Context) {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        if (!prefs.dictate.rememberLastDictation.get()) return
        val text = prefs.dictate.lastDictation.get()
        if (text.isEmpty()) return
        claimKeyboardOutput()
        sink(context).commitText(text)
        clearError()
    }

    /**
     * Undo (issue #133): removes the last successful dictation from the focused field again — the
     * inverse of [reinsertLastDictation]. Used by the floating button's optional undo control, so it
     * outputs through the overlay sink. No-op while a recording/transcription/rewording is in flight,
     * or when nothing is cached. On success the cache is cleared so a second tap can't delete unrelated
     * text. Returns true when the field accepted the removal.
     */
    fun undoLastDictation(context: Context): Boolean {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return false
        if (!prefs.dictate.rememberLastDictation.get()) return false
        val text = prefs.dictate.lastDictation.get()
        if (text.isEmpty()) return false
        outputTarget = OutputTarget.OVERLAY
        // The space put in front of it (#443) goes with it; the bare text is the fallback for a field the
        // user has edited in between, and for a dictation from before the process last restarted.
        val sink = sink(context)
        val lead = lastDictationLead
        if (!(lead.isNotEmpty() && sink.deleteLastText(lead + text)) && !sink.deleteLastText(text)) return false
        lastDictationLead = ""
        scope.launch { prefs.dictate.lastDictation.set("") }
        clearError()
        return true
    }

    // --- Transcription history (issue #140) -----------------------------------------------------

    /**
     * Logs a finished dictation to the history store, honoring the opt-in and the privacy gate. Called
     * from [finalizeAndCommit] with the final committed text and the threaded [capture] metadata. A replay
     * with a known entry id overwrites that entry's text in place; otherwise a new row is inserted (and the
     * WAV copied in when audio retention is on). No-op when history is off, the field is sensitive, or
     * there is no capture context (e.g. a plain re-insert).
     */
    private suspend fun recordHistory(
        appContext: Context,
        text: String,
        originalText: String,
        recordedSeconds: Long,
        capture: HistoryCapture?,
        reworded: Boolean,
    ) {
        if (capture == null) return
        // The placeholder row written when the audio was sent (#358) already carries the decision to log
        // this dictation: it passed the gates below at that moment. Finish it instead of asking again —
        // and drop it when the provider answered with nothing at all, since a recording that had no words
        // in it is not a failure and logs nothing today.
        capture.pendingHistoryId?.let { id ->
            if (text.isBlank()) {
                DictateHistoryStore.deleteById(appContext, id)
            } else {
                DictateHistoryStore.completePending(
                    context = appContext,
                    id = id,
                    text = text,
                    originalText = originalText,
                    keepAudio = prefs.dictate.historyAudioRetention.get(),
                    engine = capture.engine,
                )
            }
            return
        }
        if (text.isBlank()) return
        if (!prefs.dictate.historyEnabled.get()) return
        if (isSensitiveDictationField(appContext)) return
        capture.replayHistoryId?.let { id ->
            DictateHistoryStore.updateText(appContext, id, text, originalText, capture.engine)
            return
        }
        DictateHistoryStore.record(
            context = appContext,
            prefs = prefs,
            text = text,
            originalText = originalText,
            providerId = capture.engine.providerId,
            providerName = capture.engine.providerName,
            model = capture.engine.model,
            language = capture.language,
            durationSecs = recordedSeconds,
            source = capture.source,
            reworded = reworded,
            audioFile = capture.audioFile,
        )
    }

    /**
     * Logs a *failed* dictation to the history (issue #140 safety net) so its audio can be recovered and
     * re-transcribed later — the resend chip is transient (it disappears; see issue #114). Only when
     * history + audio retention are on (a failure with no kept audio is not recoverable), and never in a
     * sensitive field. The entry carries a placeholder text and `failed=true`.
     *
     * Also writes the placeholder for a dictation that has only just been *sent* (issue #358) — the same
     * row, because until a transcript exists the two are the same thing to the person looking at the
     * list: a recording with no text and a button to try again. Returns the row's id so the success path
     * can finish it instead of inserting a second one.
     */
    private suspend fun recordFailedHistory(
        appContext: Context,
        audioFile: File,
        providerId: String,
        providerName: String,
        model: String,
        language: String,
        recordedSeconds: Long,
        source: String,
        // [isSensitiveDictationField], asked by the caller: the request asks it once for everything it
        // decides, and for the floating button every ask is a round trip to another app.
        sensitive: Boolean,
    ): Long? {
        // Gated only on the master history switch: a failed dictation has no text, so its audio is the ONLY
        // recovery path — we keep it even when "keep audio" (which governs successful dictations) is off.
        if (!prefs.dictate.historyEnabled.get()) return null
        if (sensitive) return null
        if (!audioFile.exists() || audioFile.length() == 0L) return null
        return DictateHistoryStore.record(
            context = appContext,
            prefs = prefs,
            text = appContext.getString(R.string.dictate__history_failed),
            providerId = providerId,
            providerName = providerName,
            model = model,
            language = language,
            durationSecs = recordedSeconds,
            source = source,
            reworded = false,
            audioFile = audioFile,
            failed = true,
            forceAudio = true,
        )
    }

    /** Exports a history entry's retained audio to Downloads/Dictate (issue #140), toasting the result. */
    fun exportHistoryAudio(context: Context, entry: DictateHistoryEntry) {
        val path = entry.audioPath ?: return
        val src = File(path)
        if (!src.exists() || src.length() == 0L) return
        val appContext = context.applicationContext
        scope.launch {
            val savedName = withContext(Dispatchers.IO) { exportAudioToDownloads(appContext, src) }
            val message = if (savedName != null) {
                appContext.getString(R.string.dictate__audio_saved, savedName)
            } else {
                appContext.getString(R.string.dictate__audio_save_failed)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * True when the dictation is going into a password field or an incognito one, so it must not be
     * logged or kept for re-insert.
     *
     * Every path is asked, because the privacy policy promises it of every path (#383). Until then only
     * the keyboard was; the floating button was assumed to have no signal, though the accessibility node
     * says `isPassword`. The voice IME passes its field's answer in [startRecognition]. An app asking
     * Android's speech recognition says nothing about what the words are for, so that request is logged.
     */
    private suspend fun isSensitiveDictationField(context: Context): Boolean = when (outputTarget) {
        OutputTarget.IME -> runCatching {
            val state = context.keyboardManager().value.activeState
            state.isIncognitoMode || state.keyVariation == KeyVariation.PASSWORD
        }.getOrDefault(false)
        // Off the main thread like every other accessibility round trip (see [writeToSink]).
        OutputTarget.OVERLAY -> withContext(Dispatchers.IO) {
            runCatching { DictateAccessibilityService.focusedFieldIsPassword() }.getOrDefault(false)
        }
        OutputTarget.RECOGNITION_SERVICE -> recognitionFieldSensitive
    }

    /**
     * Commits a stored history entry's text into the focused field (issue #140), verbatim like
     * [reinsertLastDictation] — no auto-formatting/auto-enter (those already ran). Used by the in-keyboard
     * history panel's per-row insert. No-op while a recording/transcription/rewording is in flight.
     */
    fun insertHistoryText(context: Context, text: String) {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        if (text.isEmpty()) return
        claimKeyboardOutput()
        sink(context).commitText(text)
        clearError()
    }

    /**
     * Re-transcribes a stored entry's retained audio (issue #140) and commits the fresh result into the
     * field, then overwrites the entry's text in place. The history-owned file is copied to a cache temp
     * first so the shared transcribe path's finally-delete can't remove it. Marked as a replay so stats
     * aren't double-counted. No-op when the entry has no retained audio or a dictation is in flight.
     *
     * The temp copy keeps the stored extension (#322). It used to be `.wav` unconditionally, which for
     * an imported voice note renamed Ogg bytes into a WAV and told the provider so.
     */
    fun retranscribeHistoryEntry(context: Context, entry: DictateHistoryEntry) {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        val path = entry.audioPath ?: return
        val src = File(path)
        if (!src.exists() || src.length() == 0L) return
        val temp = File(context.cacheDir, "dictate_history_replay.${src.extension.ifEmpty { "wav" }}")
        runCatching { src.copyTo(temp, overwrite = true) }.getOrElse { return }
        claimKeyboardOutput()
        clearError()
        // A failed entry's first successful re-transcribe SHOULD count stats (it was never counted); an
        // already-successful entry's re-transcribe must not double-count → isReplay only when not failed.
        transcribe(
            context, temp, entry.durationSecs, gate = false,
            isReplay = !entry.failed, source = entry.source, replayHistoryId = entry.id,
        )
    }

    // --- Automatic on-device cleanup ---------------------------------------------------------

    /** Keep the unfiltered transcript on failure; report the failure only after inserting safe text. */
    internal suspend fun cleanupOrKeep(text: String, block: suspend () -> String): String =
        try {
            block().ifBlank { text }
        } catch (c: CancellationException) {
            throw c // the stop button (issue #192) is not a failure and has nothing to report
        } catch (t: Throwable) {
            if (cleanupFailure == null) cleanupFailure = t
            text
        }

    /**
     * Surfaces a cleanup failure that [cleanupOrKeep] swallowed, *after* the text was committed — the
     * dictation is safe in the field, so this is a notice and not a failure: themed rather than red, no
     * action, gone by itself after a moment, and carrying the provider's own message for anyone who taps
     * it. Returns whether it took over the state (the caller then skips the idle nudges: a dictation that
     * half-worked is not the moment to ask for a Play rating).
     *
     * Not shown for the system voice input (issue #67): there is no Smartbar in front of that user, and
     * the calling app already has its text.
     */
    private fun reportCleanupFailure(context: Context): Boolean {
        val failure = cleanupFailure ?: return false
        cleanupFailure = null
        if (outputTarget == OutputTarget.RECOGNITION_SERVICE) return false
        _state.value = UiState.Error(
            message = context.getString(R.string.dictate__cleanup_failed),
            kind = (failure as? DictateApiException)?.kind,
            action = ErrorAction.NONE,
            detail = failure.message?.takeIf { it.isNotBlank() },
            neutral = true,
        )
        return true
    }

    /**
     * Runs exactly one cleanup pass. Legacy auto-formatting, saved prompts and spoken commands are
     * intentionally not consulted. Failure retains the original transcript.
     */
    private suspend fun postProcessTranscript(context: Context, transcript: String): String =
        LocalCleanupPipeline.automatic(transcript, prefs.dictate.cleanupEnabled.get()) {
            _state.value = UiState.Rewording(context.getString(R.string.dictate__local_cleanup_running))
            cleanupOrKeep(transcript) {
                LocalCleanupProvider.complete(
                    transcript, cleanupModelId(), prefs.dictate.requestTimeout.get().toLong() * 1000,
                    cleanupOnly = true,
                    language = prefs.dictate.activeInputLanguage.get(),
                    instructionOverride = LocalCleanupPrompt.customOverride(
                        prefs.dictate.cleanupPromptSelection.get(), prefs.dictate.cleanupPromptCustom.get(),
                    ),
                ).also { DictateStats.recordRewording(prefs) }
            }
        }


    /**
     * Style/punctuation prompt sent with the transcription request (independent of rewording). The
     * user's custom words (roadmap 11.12) are appended on top of whichever style prompt is active, so
     * names/jargon are spelled correctly even with the predefined punctuation prompt or with none.
     */
    private fun transcriptionStylePrompt(): String? =
        DictatePromptDefaults.appendCustomWords(transcriptionStyleBasePrompt(), prefs.dictate.customWords.get())

    /**
     * The style prompt WITHOUT the appended custom-words glossary — the predefined per-language sentence or
     * the user's custom style prompt. This is the part a Whisper-style model echoes on silence, so the
     * prompt-echo guard (#77) compares against it rather than the full prompt (whose trailing glossary
     * would otherwise throw off the overlap check).
     */
    private fun transcriptionStyleBasePrompt(): String? = when (prefs.dictate.stylePromptSelection.get()) {
        DictatePromptDefaults.SELECTION_PREDEFINED ->
            DictatePromptDefaults.punctuationPromptFor(prefs.dictate.activeInputLanguage.get())
        DictatePromptDefaults.SELECTION_CUSTOM ->
            prefs.dictate.stylePromptCustom.get().takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * The instruction sent alongside the audio in the single-call multimodal path (issue #130). Folds
     * everything the two-call flow would otherwise do into one prompt: the spoken language (readable
     * name), the style/punctuation prompt + custom words, and — when rewording is enabled — the
     * auto-formatting rules and the user's auto-apply prompts. The client prepends a "transcribe, return
     * only the text" preamble.
     */

    /** The active transcription provider's stored credentials (keyring). */
    private fun transcriptionAccount(): ProviderAccount =
        prefs.dictate.providerAccounts.get().getOrEmpty(ProviderRegistry.LOCAL.id)

    /**
     * The on-device provider's stored account (issue #228): the selected local model lives in its
     * [ProviderAccount.transcriptionModel]. Used by the long-press "send with local model" shortcut.
     */
    private fun localTranscriptionAccount(): ProviderAccount =
        prefs.dictate.providerAccounts.get().getOrEmpty(ProviderRegistry.LOCAL.id)

    /**
     * The transcription model to run for [account], resolving the on-device special case: the local
     * provider holds two picks (#233), and if the user only installed a streaming model, batch paths —
     * real-time off, long-form, the floating button — must use that one instead of failing with
     * "no model downloaded".
     */
    private fun transcriptionModelFor(
        context: Context,
        account: ProviderAccount,
        preset: ProviderPreset,
        fallback: String = "",
    ): String {
        val chosen = account.transcriptionModel.takeIf { it.isNotBlank() }
            ?: preset.defaultTranscriptionModel
            ?: fallback
        if (preset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE) return chosen
        if (chosen.isNotBlank() && LocalModelManager.isInstalled(context, chosen)) return chosen
        return account.realtimeModel.takeIf {
            it.isNotBlank() && LocalModelManager.isInstalled(context, it)
        } ?: chosen
    }

    /** The active rewording provider's stored credentials (keyring). */
    private fun rewordingAccount(): ProviderAccount =
        prefs.dictate.providerAccounts.get().getOrEmpty(ProviderRegistry.LOCAL_CLEANUP.id)

    /** The cleanup checkpoint chosen for the local provider, or the quality default. */
    private fun cleanupModelId(): String =
        chatModelFor(rewordingAccount(), ProviderRegistry.LOCAL_CLEANUP)



    private fun requestAudioFocusIfEnabled(context: Context) {
        if (!prefs.dictate.audioFocus.get()) return
        val am = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    // Another app permanently took focus: pause the recording so we don't fight it.
                    val current = _state.value
                    if (current is UiState.Recording && !current.paused) togglePause()
                }
            }
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private suspend fun setupBluetoothIfEnabled(context: Context): Int {
        // Non-Bluetooth path uses the user's chosen audio source (issue #62); Bluetooth SCO always needs
        // VOICE_COMMUNICATION. If BT is requested but can't be activated, fall back to the chosen source.
        val localSource = prefs.dictate.audioInputSource.get().resolve(context)
        if (!prefs.dictate.useBluetoothMic.get()) {
            logAudioRoute("off", localSource)
            return localSource
        }
        val router = BluetoothMicRouter(context).also { btRouter = it }
        val activated = router.activate()
        val source = if (activated) MediaRecorder.AudioSource.VOICE_COMMUNICATION else localSource
        logAudioRoute(if (activated) "active" else "unavailable", source)
        return source
    }

    /**
     * Says which microphone a recording (or a handover, #411) actually ended up on. Without it, a
     * Bluetooth test that silently fell back to the local microphone is indistinguishable from one that
     * used the headset and survived — a day's worth of measurements can mean nothing. Both values are
     * platform constants and say nothing about the user.
     */
    private fun logAudioRoute(bluetooth: String, source: Int) {
        Log.i(LATENCY_LOG_TAG, "phase=audioRoute bluetooth=$bluetooth source=$source")
    }

    /**
     * Watches the input devices for as long as a recording runs, so a microphone that goes away
     * mid-sentence is answered instead of read until it stops producing anything (#411).
     *
     * Only a **removal** is acted on, and only of the device this capture is actually routed to: a route
     * change may rescue a dictation, never hijack one. A headset that connects while the user is already
     * speaking is theirs to pick next time, not ours to switch to mid-sentence.
     */
    private fun registerInputDeviceWatch(appContext: Context) {
        if (deviceWatch != null) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val watch = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val routed = recorder?.routedInputDeviceId()
                val ours = removedDevices.any { device ->
                    device.isSource && (
                        device.id == routed ||
                            // `AudioRecord.getRoutedDevice()` is NOT a reliable identity for the microphone
                            // we are listening to. Measured on an A55 (2026-09-21): while capture was
                            // routed to a Bluetooth headset via `setCommunicationDevice`, it kept reporting
                            // a built-in device (id 14) — and switching the headset off removed the SCO
                            // input under its own id (3714), which the id comparison therefore missed. So a
                            // Bluetooth input going away counts whenever *we* asked for Bluetooth,
                            // whatever the platform believes the route to be.
                            (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && btRouter != null)
                        )
                }
                // Logged whether or not it matched: a headset that is switched off mid-dictation did not
                // produce a removal we recognised (#411, A55), and the only way to find out what the
                // platform *did* report is to have written it down. Device ids and types are platform
                // constants, nothing about the user.
                if (_state.value is UiState.Recording) {
                    Log.i(
                        LATENCY_LOG_TAG,
                        "phase=deviceRemoved matched=$ours routed=$routed inputs=" +
                            removedDevices.filter { it.isSource }.joinToString(",") { "${it.type}#${it.id}" },
                    )
                }
                if (ours) onCaptureRouteLost(appContext, "deviceRemoved")
            }
        }
        runCatching { am.registerAudioDeviceCallback(watch, Handler(Looper.getMainLooper())) }
            .onSuccess {
                deviceWatch = watch
                deviceWatchManager = am
            }
    }

    /** Stops watching input devices. Called from every path that stops a recording. Idempotent. */
    private fun unregisterInputDeviceWatch() {
        val watch = deviceWatch ?: return
        val am = deviceWatchManager
        deviceWatch = null
        deviceWatchManager = null
        runCatching { am?.unregisterAudioDeviceCallback(watch) }
    }

    /**
     * The microphone this dictation was recording from is gone (#411) — either the device watch saw it
     * removed, or the capture thread has been getting errors instead of frames for long enough to be sure.
     *
     * Reached from the capture thread as well as from the main one, so it does nothing but hop onto the
     * main thread; everything that reads the recording's state happens in [handleCaptureRouteLost].
     */
    private fun onCaptureRouteLost(appContext: Context, reason: String) {
        scope.launch { handleCaptureRouteLost(appContext, reason) }
    }

    private fun handleCaptureRouteLost(appContext: Context, reason: String) {
        // Every exit is logged, including the silent ones. A handover that never starts looks exactly
        // like one that was never needed — in a realtime run on 2026-09-21 the capture ended up on a
        // different source with no handover logged at all, which no reading of this code explains.
        val declined = when {
            _state.value !is UiState.Recording -> "notRecording"
            routeSwaps >= MAX_ROUTE_SWAPS -> "capped"
            routeChangeJob?.isActive == true -> "inFlight"
            recorder == null -> "noRecorder"
            else -> null
        }
        if (declined != null) {
            Log.i(LATENCY_LOG_TAG, "phase=routeLost reason=$reason declined=$declined")
            return
        }
        // Note that "inFlight" above is also what keeps a handover already under way from being restarted:
        // cancelling one mid-acquisition would leave the capture parked on a recorder that is never
        // installed. A route that is still broken afterwards reports itself again, by which time this job
        // has finished.
        Log.i(LATENCY_LOG_TAG, "phase=routeLost reason=$reason accepted")
        routeChangeJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            // Removals arrive in bursts — one disconnect is several events — and the audio service needs a
            // moment to settle on what is left. Decide once, after the dust, not once per event.
            delay(ROUTE_SETTLE_MS)
            // Logged rather than returned silently: these two are the difference between "we decided not
            // to" and "we never got here", and telling those apart from the outside is impossible.
            val activeRecorder = recorder
            if (activeRecorder == null || _state.value !is UiState.Recording) {
                Log.i(
                    LATENCY_LOG_TAG,
                    "phase=routeSwap reason=$reason outcome=abandoned " +
                        "recorder=${activeRecorder != null} state=${_state.value::class.simpleName}",
                )
                return@launch
            }
            // Re-run the routing decision from scratch rather than assuming what is left: with the
            // Bluetooth headset gone this hands back the user's own local source, which is exactly the
            // route the dictation should continue on.
            btRouter?.deactivate()
            btRouter = null
            val source = runCatching { setupBluetoothIfEnabled(appContext) }.getOrNull()
            val continued = source != null && activeRecorder.swapSource(source)
            if (continued) routeSwaps++
            // A handover is invisible by design — a short gap and the dictation goes on — which also makes
            // it impossible to tell from "nothing happened" when testing it. The audio source is a
            // platform constant, so this says which route took over without saying anything about the user.
            Log.i(
                LATENCY_LOG_TAG,
                "phase=routeSwap reason=$reason outcome=${if (continued) "continued" else "ended"} " +
                    "source=$source swaps=$routeSwaps phaseMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            if (continued) return@launch
            // Re-check before ending it: the handover can take the best part of a second, and a stop that
            // arrived in the meantime has already finalized this dictation. Stopping it a second time
            // finds no recorder, and would replace the running transcription with "no audio".
            if (_state.value !is UiState.Recording || recorder !== activeRecorder) return@launch
            // Nothing left to record from. Everything spoken up to the handover is already in the WAV, so
            // end the dictation the way the stop button would: the user keeps what was captured, and we
            // never hand them a transcript of silence we knew we were recording.
            stopAndTranscribe(appContext)
        }
    }

    private fun cleanupAudioRouting() {
        routeChangeJob?.cancel()
        routeChangeJob = null
        unregisterInputDeviceWatch()
        focusRequest?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        focusRequest = null
        audioManager = null
        btRouter?.deactivate()
        btRouter = null
    }

    private fun presetFor(account: ProviderAccount): ProviderPreset =
        ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.LOCAL

    /**
     * Whether [account] needs a credential: built-in cloud providers do; custom/local servers may not.
     *
     * The rule itself lives on [ProviderAccount.requiresCredential], because the setup wizard has to
     * answer the same question and used to answer it differently (#273).
     */
    private fun requiresKey(account: ProviderAccount): Boolean = account.requiresCredential

    /** [account] as a history entry names it, with the model it transcribes with (#455). */
    private fun historyEngineOf(context: Context, account: ProviderAccount): HistoryEngine {
        val preset = presetFor(account)
        val model = transcriptionModelFor(
            context, account, preset, ProviderRegistry.LOCAL.defaultTranscriptionModel.orEmpty(),
        )
        return HistoryEngine(account.providerId, account.displayName.ifBlank { preset.displayName }, model)
    }

    /** The phone's own [model] as a history entry names it (#457), for text the offline fallback wrote. */
    private fun onDeviceHistoryEngine(model: String): HistoryEngine {
        val account = localTranscriptionAccount()
        return HistoryEngine(
            account.providerId, account.displayName.ifBlank { ProviderRegistry.LOCAL.displayName }, model,
        )
    }

    /**
     * The budget an on-device transcription gets, from the same "Request timeout" setting a provider call
     * uses (issue #354). One number for "how long am I willing to wait", whichever engine is working.
     *
     * The trade-off is deliberate and the setting is where it is answered: decoding on the phone is far
     * slower than a cloud round-trip, so a long dictation on an older device may now time out where it
     * would eventually have finished. The slider goes to 600 s for exactly that case.
     */
    private fun localTimeoutMillis(): Long = prefs.dictate.requestTimeout.get() * 1000L
}
