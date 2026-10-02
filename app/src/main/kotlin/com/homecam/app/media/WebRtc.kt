package com.homecam.app.media

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One [PeerConnectionFactory] and one [EglBase] for the process. libwebrtc's native side
 * is initialised once and is not reference-counted for us, so a second factory is a
 * source of hard crashes rather than a second factory.
 */
object WebRtc {

    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile private var factory: PeerConnectionFactory? = null

    private const val ADAPTER_TYPE_LOOPBACK = 16

    /** Receives what WebRTC's recorder captures, on its audio thread, while a session runs. */
    @Volatile var capturedAudio: ((JavaAudioDeviceModule.AudioSamples) -> Unit)? = null

    fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: build(context.applicationContext).also { factory = it }
    }

    private fun build(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-SupportVP9SVC/Default/")
                .createInitializationOptions()
        )
        val audio = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { capturedAudio?.invoke(it) }
            .createAudioDeviceModule()

        return PeerConnectionFactory.builder()
            .setOptions(
                PeerConnectionFactory.Options().apply {
                    // With Android's network monitor on, libwebrtc offers only the default
                    // network's address and never the VPN interface's. With no STUN or TURN
                    // by design, the tailnet address is the only path between the phones
                    // away from home, so interfaces are enumerated natively instead.
                    disableNetworkMonitor = true
                    networkIgnoreMask = ADAPTER_TYPE_LOOPBACK
                }
            )
            .setVideoEncoderFactory(
                // Hardware H.264 is the whole reason a 2020 phone can encode continuously;
                // the software fallback exists so a device without it still works, badly.
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()
    }
}
