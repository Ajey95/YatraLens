package com.yatralens

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Registered as an optional system assistant; the OS owns selection and invocation. */
class YatraVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

class YatraVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = YatraVoiceSession(this)
}
