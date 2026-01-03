package com.cagedbird.droidv4l2

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRtcManager(private val context: Context) {
    private val TAG = "WebRtcManager"

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    // Simplified: One PC for demo
    private var peerConnection: PeerConnection? = null
    private val iceServers =
            listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                            .createIceServer()
            )

    init {
        initializeApi()
    }

    private fun initializeApi() {
        eglBase = EglBase.create()
        val options =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        factory =
                PeerConnectionFactory.builder()
                        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                        .setVideoEncoderFactory(
                                DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
                        )
                        .createPeerConnectionFactory()

        // Create Video Source (false = not screen cast)
        videoSource = factory?.createVideoSource(false)
        videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
    }

    fun handleOffer(offerSdp: String): String? {
        Log.i(TAG, "Handling Offer SDP")

        // 1. Create PeerConnection
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection?.close()
        peerConnection =
                factory?.createPeerConnection(
                        rtcConfig,
                        object : PeerConnection.Observer {
                            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                            override fun onIceConnectionChange(
                                    state: PeerConnection.IceConnectionState?
                            ) {
                                Log.i(TAG, "IceConnectionChange: $state")
                            }
                            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                            override fun onIceGatheringChange(
                                    state: PeerConnection.IceGatheringState?
                            ) {}
                            override fun onIceCandidate(candidate: IceCandidate?) {}
                            override fun onIceCandidatesRemoved(
                                    candidates: Array<out IceCandidate>?
                            ) {}
                            override fun onAddStream(stream: MediaStream?) {}
                            override fun onRemoveStream(stream: MediaStream?) {}
                            override fun onDataChannel(dc: DataChannel?) {}
                            override fun onRenegotiationNeeded() {}
                            override fun onAddTrack(
                                    receiver: RtpReceiver?,
                                    streams: Array<out MediaStream>?
                            ) {}
                        }
                )

        if (peerConnection == null) {
            Log.e(TAG, "Failed to create PeerConnection")
            return null
        }

        // Add Video Track
        peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))

        try {
            // 2. Parse JSON (Assuming simple format {"sdp": "...", "type": "offer"})
            val jsonObject = org.json.JSONObject(offerSdp)
            val sdpString = jsonObject.getString("sdp")
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdpString)

            // 3. Set Remote Description
            val latch = java.util.concurrent.CountDownLatch(1)
            var error: String? = null

            peerConnection?.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onSetSuccess() {
                            latch.countDown()
                        }
                        override fun onCreateFailure(s: String?) {
                            error = s
                            latch.countDown()
                        }
                        override fun onSetFailure(s: String?) {
                            error = s
                            latch.countDown()
                        }
                    },
                    sessionDescription
            )

            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return null
            }
            if (error != null) {
                Log.e(TAG, "SetRemoteDescription failed: $error")
                return null
            }

            // 4. Create Answer
            val answerLatch = java.util.concurrent.CountDownLatch(1)
            var answerSdp: SessionDescription? = null

            val mediaConstraints = MediaConstraints()
            // mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
            // "true")) // We are sender, usually false or implicit

            peerConnection?.createAnswer(
                    object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            answerSdp = desc
                            // Set Local Description
                            peerConnection?.setLocalDescription(
                                    object : SdpObserver {
                                        override fun onCreateSuccess(p0: SessionDescription?) {}
                                        override fun onSetSuccess() {
                                            answerLatch.countDown()
                                        }
                                        override fun onCreateFailure(p0: String?) {
                                            answerLatch.countDown()
                                        }
                                        override fun onSetFailure(p0: String?) {
                                            answerLatch.countDown()
                                        }
                                    },
                                    desc
                            )
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(s: String?) {
                            error = s
                            answerLatch.countDown()
                        }
                        override fun onSetFailure(s: String?) {
                            error = s
                            answerLatch.countDown()
                        }
                    },
                    mediaConstraints
            )

            if (!answerLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) return null
            if (answerSdp == null) return null

            // 5. Wait for ICE Gathering (Simple approach: wait a bit or until gathering complete)
            // Ideally we wait for onIceGatheringChange to COMPLETE, but a fixed delay often works
            // for LAN mDNS.
            Thread.sleep(1000)

            // 6. Return Answer JSON
            // Note: In a real app we should return the SDP from pc.localDescription which contains
            // candidates
            val localDesc = peerConnection?.localDescription ?: answerSdp

            val responseJson = org.json.JSONObject()
            responseJson.put("type", "answer")
            responseJson.put("sdp", localDesc?.description)
            return responseJson.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Exception in handleOffer", e)
            return null
        }
    }

    fun onFrame(image: androidx.camera.core.ImageProxy) {
        val timestampNs = image.imageInfo.timestamp
        val planes = image.planes

        // Note: This logic assumes I420 memory layout.
        // If CameraX produces NV12/NV21 (interleaved UV), this might need pixelStride checks.
        // However, standard CameraX ImageAnalysis often gives YUV_420_888 which is generic.
        // For a robust implementation, we should check pixelStride.
        // We assume JavaI420Buffer.wrap handles direct buffers correctly.

        try {
            val buffer =
                    JavaI420Buffer.wrap(
                            image.width,
                            image.height,
                            planes[0].buffer,
                            planes[0].rowStride,
                            planes[1].buffer,
                            planes[1].rowStride,
                            planes[2].buffer,
                            planes[2].rowStride,
                            Runnable { image.close() }
                    )

            // Rotation is handled by WebRTC if we pass it here
            val videoFrame = VideoFrame(buffer, image.imageInfo.rotationDegrees, timestampNs)

            // Feed to WebRTC Source
            videoSource?.capturerObserver?.onFrameCaptured(videoFrame)

            // Release the frame wrapper (the buffer itself is ref-counted/callback-released)
            videoFrame.release()
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
            image.close()
        }
    }
}
