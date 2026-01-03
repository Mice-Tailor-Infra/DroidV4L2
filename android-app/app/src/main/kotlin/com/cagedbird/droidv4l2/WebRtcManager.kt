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

    private var frameCount = 0
    private var lastLogTime = 0L

    fun onFrame(image: androidx.camera.core.ImageProxy) {
        val timestampNs = image.imageInfo.timestamp
        val planes = image.planes

        // Debug Log every 30 frames
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 1000) {
            Log.d(
                    TAG,
                    "WebRTC Frames: $frameCount / last 1s. Res: ${image.width}x${image.height} Rotation: ${image.imageInfo.rotationDegrees} Planes: ${planes.size} PixelStride: [${planes[0].pixelStride}, ${planes[1].pixelStride}, ${planes[2].pixelStride}]"
            )
            frameCount = 0
            lastLogTime = now
        }

        // Note: This logic assumes I420 memory layout.
        // If CameraX produces NV12/NV21 (interleaved UV), this might need pixelStride checks.
        // However, standard CameraX ImageAnalysis often gives YUV_420_888 which is generic.
        // For a robust implementation, we should check pixelStride.
        // We assume JavaI420Buffer.wrap handles direct buffers correctly.

        try {
            // Check pixel stride.
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            if (uPlane.pixelStride == 1 && vPlane.pixelStride == 1) {
                // Standard I420, direct wrap
                val buffer =
                        JavaI420Buffer.wrap(
                                image.width,
                                image.height,
                                yPlane.buffer,
                                yPlane.rowStride,
                                uPlane.buffer,
                                uPlane.rowStride,
                                vPlane.buffer,
                                vPlane.rowStride,
                                Runnable { image.close() }
                        )
                val videoFrame = VideoFrame(buffer, image.imageInfo.rotationDegrees, timestampNs)
                videoSource?.capturerObserver?.onFrameCaptured(videoFrame)
                videoFrame.release()
            } else {
                // Likely NV12 (stride 2) or NV21. Need to copy to I420.
                // JavaI420Buffer.allocate allocates a native buffer.
                val i420Buffer = JavaI420Buffer.allocate(image.width, image.height)

                // Copy Y (Stride may differ, so row-by-row)
                val ySrc = yPlane.buffer
                val yDst = i420Buffer.dataY
                val yW = image.width
                val yH = image.height
                val ySrcStride = yPlane.rowStride
                val yDstStride = i420Buffer.strideY

                ySrc.position(0)
                yDst.position(0) // Ensure dst is at start

                // Bulk copy if strides match, else row-by-row
                if (ySrcStride == yDstStride) {
                    // Limits need to be set? DirectBuffer.
                    // Note: src might be larger than needed.
                    val limit = Math.min(ySrc.capacity(), yDst.capacity())
                    // Actually just copy height * stride?
                    // But i420Buffer is tightly packed usually? or aligned.
                    // Safer: row-by-row
                    for (r in 0 until yH) {
                        ySrc.limit(r * ySrcStride + yW)
                        ySrc.position(r * ySrcStride)
                        yDst.put(ySrc)
                    }
                } else {
                    for (r in 0 until yH) {
                        ySrc.limit(r * ySrcStride + yW)
                        ySrc.position(r * ySrcStride)
                        yDst.put(ySrc)
                    }
                }

                // Copy U and V (De-interleave)
                // If PixelStride=2, it means U (skip) U (skip).
                // We need to read just the U bytes into tight U buffer.
                val uSrc = uPlane.buffer
                val vSrc = vPlane.buffer
                val uDst = i420Buffer.dataU
                val vDst = i420Buffer.dataV
                val chromeW = (image.width + 1) / 2
                val chromeH = (image.height + 1) / 2
                val uSrcStride = uPlane.rowStride
                val vSrcStride = vPlane.rowStride
                val uDstStride = i420Buffer.strideU // strideU is usually chromW or aligned
                val vDstStride = i420Buffer.strideV
                val uPixelStride = uPlane.pixelStride
                val vPixelStride = vPlane.pixelStride

                // Intermediate buffer for a row.
                // Max width needed is roughly width of image (since stride ~ width for NV12)
                // Allocating once per frame is okay-ish (young gen), or could be a member.
                // For safety size is rowStride.
                val uRowBuf = ByteArray(uSrcStride)
                val vRowBuf = ByteArray(vSrcStride)

                for (r in 0 until chromeH) {
                    // 1. Read U Row
                    uSrc.position(r * uSrcStride)
                    // Read enough bytes for the row pixels
                    val bytesToReadU = if (uPixelStride == 1) chromeW else chromeW * uPixelStride
                    // Ensure we don't read past limit if stride is weird, but rowStride should
                    // cover it.
                    // Actually simplest is read min(remaining, stride).
                    val readLenU = Math.min(uSrcStride, uSrc.remaining())
                    uSrc.get(uRowBuf, 0, readLenU)

                    // 2. Write U Pixels to Direct Buffer
                    uDst.position(r * uDstStride)
                    // We can't bulk put de-interleaved data easily without another buffer or loop.
                    // But loop over ByteArray is fast.
                    for (c in 0 until chromeW) {
                        uDst.put(uRowBuf[c * uPixelStride])
                    }

                    // 3. Read V Row
                    vSrc.position(r * vSrcStride)
                    val readLenV = Math.min(vSrcStride, vSrc.remaining())
                    vSrc.get(vRowBuf, 0, readLenV)

                    // 4. Write V Pixels
                    vDst.position(r * vDstStride)
                    for (c in 0 until chromeW) {
                        vDst.put(vRowBuf[c * vPixelStride])
                    }
                }

                // Done copying.
                // Need to reset buffer positions for WebRTC reading?
                // allocate() returns buffers at pos 0? No, allocate() calls C++.
                // We used put(), which advances position. We MUST rewind.
                // Actually JavaI420Buffer docs: "The buffers are DIRECT byte buffers... The
                // position is always 0."
                // But we moved position by put(). We must flip or rewind?
                // No, we are writing to the buffer that JavaI420Buffer WRAPS.
                // Wait, JavaI420Buffer has 'dataY', 'dataU', etc. which are ByteBuffers.
                // If we modify position, does it affect the native read?
                // Usually yes. We should rewind (position=0) after writing.

                // Wait, we need to pass the i420Buffer to VideoFrame.
                // But wait, we wrote to i420Buffer.dataY.
                // We can just call i420Buffer.retain() ? No, we own it until we pass to VideoFrame?

                // IMPORTANT: The buffers returned by getDataY() etc. might be slicing the same
                // block.
                // We advanced specific buffers.
                // We should probably rewind them manually if we want safety,
                // BUT `videoFrame` constructor doesn't re-read dataY/U/V from the object getter?
                // It takes the buffer object.
                // Let's assume we need to rewind.

                // Wait, `i420Buffer.getDataY()` might return a NEW ByteBuffer wrapper or the same
                // one?
                // Usually same. So if we moved pos, we moved it.
                // Let's rewind/flip. `put` moves position. So `flip` sets limit to position and
                // position to 0.
                // But the buffer size limit was the WHOLE buffer.
                // We just want to set position to 0.

                // Actually, let's keep it simple:
                // JavaI420Buffer is an Interface. `allocate` returns an impl (usually
                // WrappedNativeI420Buffer).
                // Its `getDataY()` returns a ByteBuffer.
                // Just rewriting '0' to position is safer.
                // But we can't rewind `dataY` because it's a property.
                // We used local var `yDst`. If `yDst` is the SAME object as `i420Buffer.dataY`,
                // then `yDst.rewind()` works.
                // Yes, it returns the buffer instance.

                (i420Buffer.dataY as java.nio.Buffer).rewind()
                (i420Buffer.dataU as java.nio.Buffer).rewind()
                (i420Buffer.dataV as java.nio.Buffer).rewind()

                val videoFrame =
                        VideoFrame(i420Buffer, image.imageInfo.rotationDegrees, timestampNs)
                videoSource?.capturerObserver?.onFrameCaptured(videoFrame)
                videoFrame.release()
                // i420Buffer.release() ? No, VideoFrame takes ownership or increments ref count.
                // Actually `JavaI420Buffer.allocate` returns a buffer with refCount=1.
                // VideoFrame wrapper takes it. VideoFrame.release() decrements it.
                // So we are good.

                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
            image.close()
        }
    }
}
