package com.uneedzf.web_screen;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.projection.MediaProjection;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraEnumerator;
import org.webrtc.CustomHardwareVideoEncoderFactory;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.DefaultVideoEncoderFactoryExtKt;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoEncoderSupportedCallback;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class WebRtcManager {
    private static final String TAG = WebRtcManager.class.getSimpleName();

    private static final boolean ENABLE_INTEL_VP8_ENCODER = false;
    private static final boolean ENABLE_H264_HIGH_PROFILE = true;
    private static final int FRAMES_PER_SECOND = 30;
    private static final String SDP_PARAM = "sdp";
    private static final String ICE_PARAM = "ice";

    private VideoCapturer videoCapturer = null;
    private EglBase rootEglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private MediaConstraints audioConstraints;
    private MediaConstraints videoConstraints;
    private VideoTrack localVideoTrack = null;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private Map<String, PeerConnection> peerConnectionMap;
    private MediaConstraints sdpConstraints = null;
    private HttpServer server;

    List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();
    private List<IceServer> iceServers = null;

    private Display display;
    private DisplayMetrics screenMetrics = new DisplayMetrics();
    private Thread rotationDetectorThread = null;
    private MediaStream videoStream = null;

    public WebRtcManager(Intent intent, Context context, HttpServer server) {
        this.server = server;
        //XXX getIceServers();
        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        display = wm.getDefaultDisplay();
        peerConnectionMap = new HashMap<>();
        createMediaProjection(intent);
        initWebRTC(context);
    }

    public void destroy() {
        stopAllWebRTCP2p();
//        stopRotationDetector();
        destroyMediaProjection();
    }

    private void createMediaProjection(Intent intent) {
        videoCapturer = new ScreenCapturerAndroid(intent,
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.e(TAG, "User has revoked media projection permissions");
                    }
                });
    }

    private void destroyMediaProjection() {
        try {
            videoCapturer.stopCapture();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return;
        }
        videoCapturer = null;
    }

    private void initWebRTC(Context context) {
        rootEglBase = EglBase.create();

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                rootEglBase.getEglBaseContext(), ENABLE_INTEL_VP8_ENCODER,
                ENABLE_H264_HIGH_PROFILE);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new
                DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());

        CustomHardwareVideoEncoderFactory customHardwareVideoEncoderFactory = new CustomHardwareVideoEncoderFactory(rootEglBase.getEglBaseContext(), ENABLE_INTEL_VP8_ENCODER,
                ENABLE_H264_HIGH_PROFILE, new VideoEncoderSupportedCallback() {
            @Override
            public boolean isSupportedH264(@NonNull MediaCodecInfo info) {
                String name = info.getName();
                if(name.startsWith("OMX.rk"))
                    return true;
                else
                    return false;
            }

            @Override
            public boolean isSupportedVp8(@NonNull MediaCodecInfo info) {
                return false;
            }

            @Override
            public boolean isSupportedVp9(@NonNull MediaCodecInfo info) {
                return false;
            }
        });

        //use java
//        DefaultVideoEncoderFactory encoderFactory = DefaultVideoEncoderFactoryExtKt.createCustomVideoEncoderFactory(rootEglBase.getEglBaseContext(), ENABLE_INTEL_VP8_ENCODER,
//                ENABLE_H264_HIGH_PROFILE, new VideoEncoderSupportedCallback() {
//            @Override
//            public boolean isSupportedH264(@NonNull MediaCodecInfo info) {
//                String name = info.getName();
//                if(name.startsWith("OMX.rk"))
//                    return true;
//                else
//                    return false;
//            }
//
//            @Override
//            public boolean isSupportedVp8(@NonNull MediaCodecInfo info) {
//                return false;
//            }
//
//            @Override
//            public boolean isSupportedVp9(@NonNull MediaCodecInfo info) {
//                return false;
//            }
//        });

        peerConnectionFactory = PeerConnectionFactory.builder()
        .setOptions(options)
        .setVideoEncoderFactory(customHardwareVideoEncoderFactory)
        .setVideoDecoderFactory(defaultVideoDecoderFactory)
        .createPeerConnectionFactory();

        //XXX enable camera
        //videoCapturer = createCameraCapturer(new Camera1Enumerator(false));

        audioConstraints = new MediaConstraints();
        videoConstraints = new MediaConstraints();

        SurfaceTextureHelper surfaceTextureHelper;
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread",
                rootEglBase.getEglBaseContext());
        VideoSource videoSource =
                peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoSource.adaptOutputFormat(1280, 720, 30);
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());

        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);

        //TODO audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        //TODO localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);
        // audioTest(stiller)
//        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
//        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);



        display.getRealMetrics(screenMetrics);
        if (videoCapturer != null) {
            videoCapturer.startCapture(screenMetrics.widthPixels, screenMetrics.heightPixels,
                    FRAMES_PER_SECOND);
//            startRotationDetector();
        }
    }

    public void startWebRTCP2p(HttpServer server, String remoteIPAddress) {
        Log.d(TAG, "WebRTC start");
        createPeerConnection(remoteIPAddress);
        doCall(server, remoteIPAddress);
    }

    public void stopWebRTCP2p(String remoteIPAddress) {
        Log.d(TAG, "WebRTC stop");
        if (remoteIPAddress == null || remoteIPAddress.isEmpty())
            return;

        if(peerConnectionMap.containsKey(remoteIPAddress))
        {
            PeerConnection localPeer = peerConnectionMap.get(remoteIPAddress);
            localPeer.close();
            localPeer = null;
            peerConnectionMap.remove(remoteIPAddress);
        }
    }

    public void stopAllWebRTCP2p() {
        Log.d(TAG, "WebRTC stop ALL!");
        for (PeerConnection localPeer: peerConnectionMap.values()) {
            localPeer.close();
            localPeer = null;
        }

        peerConnectionMap.clear();
    }

    private void createPeerConnection(String remoteIPAddress) {
        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(peerIceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy
                .GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;

        if(peerConnectionMap.containsKey(remoteIPAddress))
        {
            PeerConnection localPeer = peerConnectionMap.get(remoteIPAddress);
            localPeer.close();
            peerConnectionMap.remove(remoteIPAddress);
        }

        PeerConnection localPeer = peerConnectionFactory.createPeerConnection(rtcConfig,
                new CustomPeerConnectionObserver("localPeerCreation") {
                    @Override
                    public void onIceCandidate(IceCandidate iceCandidate) {
                        super.onIceCandidate(iceCandidate);
                        onIceCandidateReceived(iceCandidate, remoteIPAddress);
                    }

                    @Override
                    public void onAddStream(MediaStream mediaStream) {
                        super.onAddStream(mediaStream);
                        Log.d(TAG, "Unexpected remote stream received.");
                    }
                });

        if(videoStream == null)
        {
            videoStream = peerConnectionFactory.createLocalMediaStream("102");
            videoStream.addTrack(localVideoTrack);
        }
        //TODO stream.addTrack(localAudioTrack); stiller
        //stream.addTrack(localAudioTrack);
        localPeer.addStream(videoStream);

        peerConnectionMap.put(remoteIPAddress, localPeer);
    }

    public void onIceCandidateReceived(IceCandidate iceCandidate, String remoteIPAddress) {
        JSONObject messageJson = new JSONObject();
        JSONObject iceJson = new JSONObject();
        try {
            iceJson.put("type", "candidate");
            iceJson.put("label", iceCandidate.sdpMLineIndex);
            iceJson.put("id", iceCandidate.sdpMid);
            iceJson.put("candidate", iceCandidate.sdp);

            messageJson.put("type", "ice");
            messageJson.put("ice", iceJson);

            String messageJsonStr = messageJson.toString();
            //XXX broadcast
            server.send(messageJson.toString(), remoteIPAddress);
            Log.d(TAG, "Send ICE candidates: " + messageJsonStr);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }

    private void addStreamToLocalPeer() {

    }

    private void doCall(HttpServer server, String remoteIPAddress) {
        sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        //TODO sdpConstraints.mandatory.add(
        //        new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        if(peerConnectionMap.containsKey(remoteIPAddress))
        {
            PeerConnection localPeer = peerConnectionMap.get(remoteIPAddress);

            localPeer.createOffer(new CustomSdpObserver("localCreateOffer") {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    super.onCreateSuccess(sessionDescription);
                    localPeer.setLocalDescription(new CustomSdpObserver("localSetLocalDesc"),
                            sessionDescription);

                    JSONObject messageJson = new JSONObject();
                    JSONObject sdpJson = new JSONObject();
                    try {
                        sdpJson.put("type", sessionDescription.type.canonicalForm());
                        sdpJson.put("sdp", sessionDescription.description);

                        messageJson.put("type", "sdp");
                        messageJson.put("sdp", sdpJson);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        return;
                    }

                    String messageJsonStr = messageJson.toString();
                    try {
                        server.send(messageJsonStr, remoteIPAddress);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return;
                    }
                    Log.d(TAG, "Send SDP: " + messageJsonStr);
                }
            }, sdpConstraints);
        }
    }

    public void onAnswerReceived(JSONObject data, String remoteIPAddress) {
        JSONObject json;
        try {
            json = data.getJSONObject(SDP_PARAM);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        Log.d(TAG, "Remote SDP received: " + json.toString());

        try {

            if(peerConnectionMap.containsKey(remoteIPAddress))
            {
                PeerConnection localPeer = peerConnectionMap.get(remoteIPAddress);
                localPeer.setRemoteDescription(new CustomSdpObserver("localSetRemote"),
                        new SessionDescription(SessionDescription.Type.fromCanonicalForm(json.getString(
                                "type").toLowerCase()), json.getString("sdp")));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void onIceCandidateReceived(JSONObject data, String remoteIPAddress) {
        JSONObject json;
        try {
            json = data.getJSONObject(ICE_PARAM);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }
        Log.d(TAG, "ICE candidate received: " + json.toString());

        try {
            if(peerConnectionMap.containsKey(remoteIPAddress))
            {
                PeerConnection localPeer = peerConnectionMap.get(remoteIPAddress);
                localPeer.addIceCandidate(new IceCandidate(json.getString("id"), json.getInt("label"),
                        json.getString("candidate")));
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

//    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
//        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());
//        final String[] deviceNames = enumerator.getDeviceNames();
//
//        // First, try to find front facing camera
//        Logging.d(TAG, "Looking for front facing cameras.");
//        for (String deviceName : deviceNames) {
//            if (enumerator.isFrontFacing(deviceName)) {
//                Logging.d(TAG, "Creating front facing camera capturer.");
//                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
//
//                if (videoCapturer != null) {
//                    return videoCapturer;
//                }
//            }
//        }
//
//        // Front facing camera not found, try something else
//        Logging.d(TAG, "Looking for other cameras.");
//        for (String deviceName : deviceNames) {
//            if (!enumerator.isFrontFacing(deviceName)) {
//                Logging.d(TAG, "Creating other camera capturer.");
//                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
//
//                if (videoCapturer != null) {
//                    return videoCapturer;
//                }
//            }
//        }
//
//        return null;
//    }
//
//    public void getIceServers() {
//        final String API_ENDPOINT = "https://global.xirsys.net";
//
//        Log.d(TAG, "getIceServers");
//
//        byte[] data = new byte[0];
//        try {
//            data = ("<xirsys_ident>:<xirsys_secret>").getBytes("UTF-8");
//        } catch (UnsupportedEncodingException e) {
//            e.printStackTrace();
//            return;
//        }
//        Log.d(TAG, "getIceServers2");
//
//        String authToken = "Basic " + Base64.encodeToString(data, Base64.NO_WRAP);
//        Retrofit retrofit = new Retrofit.Builder()
//                .baseUrl(API_ENDPOINT)
//                .addConverterFactory(GsonConverterFactory.create())
//                .build();
//        Log.d(TAG, "getIceServers3");
//        TurnServer turnServer = retrofit.create(TurnServer.class);
//        Log.d(TAG, "getIceServers4");
//        turnServer.getIceCandidates(authToken).enqueue(new Callback<TurnServerPojo>() {
//            @Override
//            public void onResponse(@NonNull Call<TurnServerPojo> call,
//                                   @NonNull Response<TurnServerPojo> response) {
//                Log.d(TAG, "getIceServers Response");
//                TurnServerPojo body = response.body();
//                if (body != null)
//                    iceServers = body.iceServerList.iceServers;
//
//                Log.d(TAG, "getIceServers iceServers=" + iceServers);
//
//                for (IceServer iceServer : iceServers) {
//                    if (iceServer.credential == null) {
//                        PeerConnection.IceServer peerIceServer = PeerConnection.IceServer
//                                .builder(iceServer.url).createIceServer();
//                        peerIceServers.add(peerIceServer);
//                    } else {
//                        PeerConnection.IceServer peerIceServer = PeerConnection.IceServer
//                                .builder(iceServer.url)
//                                .setUsername(iceServer.username)
//                                .setPassword(iceServer.credential)
//                                .createIceServer();
//                        peerIceServers.add(peerIceServer);
//                    }
//                }
//                Log.d(TAG, "IceServers:\n" + iceServers.toString());
//            }
//
//            @Override
//            public void onFailure(@NonNull Call<TurnServerPojo> call, @NonNull Throwable t) {
//                t.printStackTrace();
//            }
//        });
//    }

//    private void startRotationDetector() {
//        Runnable runnable = new Runnable() {
//            @Override
//            public void run() {
//                Log.d(TAG, "Rotation detector start");
//                display.getRealMetrics(screenMetrics);
//                while (true) {
//                    DisplayMetrics metrics = new DisplayMetrics();
//                    display.getRealMetrics(metrics);
//                    if (metrics.widthPixels != screenMetrics.widthPixels ||
//                            metrics.heightPixels != screenMetrics.heightPixels) {
//                        Log.d(TAG, "Rotation detected\n" + "w=" + metrics.widthPixels + " h=" +
//                                metrics.heightPixels + " d=" + metrics.densityDpi);
//                        screenMetrics = metrics;
//                        if (videoCapturer != null) {
//                            try {
//                                videoCapturer.stopCapture();
//                            } catch (Exception e) {
//                                e.printStackTrace();
//                            }
//                            videoCapturer.startCapture(screenMetrics.widthPixels,
//                                    screenMetrics.heightPixels, FRAMES_PER_SECOND);
//                        }
//                    }
//                    try {
//                        Thread.sleep(1000);
//                    } catch (InterruptedException e) {
//                        Log.d(TAG, "Rotation detector exit");
//                        Thread.interrupted();
//                        break;
//                    }
//                }
//            }
//        };
//        rotationDetectorThread = new Thread(runnable);
//        rotationDetectorThread.start();
//    }
//
//    private void stopRotationDetector() {
//        rotationDetectorThread.interrupt();
//    }
 }
