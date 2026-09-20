package com.morton.trucknav.nav

import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.flow.StateFlow
import uniffi.ferrostar.SpokenInstruction

// Sits between Ferrostar and the TTS observer. Two jobs:
// 1. Mute means silence *and* no audio-focus request. Ferrostar's observer
//    asks for navigation-guidance focus before it checks the mute flag, so
//    muted turns still ducked the music (measured on the emulator: 3 focus
//    requests, 0 synthesis, per muted leg). Muted instructions stop here.
// 2. Alert classes (S17.4): instructions whose class the driver turned off
//    never reach the voice. Classification is by text until Valhalla gives
//    us structured types for the non-turn announcements.
class VoiceGate(private val tts: AndroidTtsObserver) : SpokenInstructionObserver {
    @Volatile var disabledClasses: Set<String> = emptySet()

    override fun onSpokenInstructionTrigger(instruction: SpokenInstruction) {
        if (tts.isMuted) { NavLog.log("voice", "muted, dropped: \"${instruction.text}\""); return }
        val cls = classify(instruction.text)
        if (cls in disabledClasses) { NavLog.log("voice", "class $cls off, dropped: \"${instruction.text}\""); return }
        tts.onSpokenInstructionTrigger(instruction)
    }
    override fun stopAndClearQueue() = tts.stopAndClearQueue()
    override fun setMuted(muted: Boolean) { tts.setMuted(muted); if (muted) tts.stopAndClearQueue() }
    override val muteState: StateFlow<Boolean> get() = tts.muteState
    override val isMuted: Boolean get() = tts.isMuted

    companion object {
        // Announcement classes the settings sheet can switch off.
        val CLASSES = listOf("turn", "continue", "arrival", "reroute", "exit", "merge", "roundabout")
        fun classify(text: String): String {
            val t = text.lowercase()
            return when {
                "arrive" in t || "destination" in t -> "arrival"
                "rerout" in t || "recalculat" in t -> "reroute"
                "roundabout" in t || "rotary" in t -> "roundabout"
                "merge" in t -> "merge"
                "exit" in t || "ramp" in t -> "exit"
                t.startsWith("continue") || t.startsWith("drive") || t.startsWith("keep") -> "continue"
                else -> "turn"
            }
        }
    }
}
