package com.google.android.exoplayer2.drm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.DeniedByServerException;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by Daichi Furiya on 2017/03/03.
 */
@TargetApi(18)
public class DefaultDrmSession<T extends ExoMediaCrypto> implements DrmSession<T> {

  private int openCount;

  /**
   * Listener of {@link DefaultDrmSession} events.
   */
  public interface EventListener {

    /**
     * Called each time keys are loaded.
     */
    void onDrmKeysLoaded();

    /**
     * Called when a drm error occurs.
     *
     * @param e The corresponding exception.
     */
    void onDrmSessionManagerError(Exception e);

    /**
     * Called each time offline keys are restored.
     */
    void onDrmKeysRestored();

    /**
     * Called each time offline keys are removed.
     */
    void onDrmKeysRemoved();
  }

  private static final String TAG = "OfflineDrmSession";
  private static final String CENC_SCHEME_MIME_TYPE = "cenc";

  private static final int MSG_PROVISION = 0;
  private static final int MSG_KEYS = 1;

  private final Handler eventHandler;
  private final EventListener eventListener;
  private final FrameworkMediaDrm mediaDrm;
  private final HashMap<String, String> optionalKeyRequestParameters;

  private final MediaDrmCallback callback;
  private final UUID uuid;

  private MediaDrmHandler mediaDrmHandler;
  private PostResponseHandler postResponseHandler;

  private Looper playbackLooper;
  private HandlerThread requestHandlerThread;
  private Handler postRequestHandler;

  private boolean provisioningInProgress;
  @DrmSession.State private int state;
  private T mediaCrypto;
  private DrmSessionException lastException;
  private byte[] schemeInitData;
  private String schemeMimeType;
  private byte[] sessionId;

  public DefaultDrmSession(UUID uuid, MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
      DefaultDrmSession.EventListener eventListener) {
    this.uuid = uuid;
    try {
      this.mediaDrm = FrameworkMediaDrm.newInstance(uuid);
    } catch (UnsupportedDrmException e) {
      throw new IllegalArgumentException(e);
    }

    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    this.optionalKeyRequestParameters = optionalKeyRequestParameters;
    this.mediaDrm.setOnEventListener(new MediaDrmEventListener());
    this.callback = callback;
    state = STATE_CLOSED;
  }

  @Override public DrmSession<T> openSession(Looper playbackLooper, DrmInitData drmInitData) {
    Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
    if (++openCount != 1) {
      return this;
    }

    if (this.playbackLooper == null) {
      this.playbackLooper = playbackLooper;
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
      postResponseHandler = new PostResponseHandler(playbackLooper);
    }

    requestHandlerThread = new HandlerThread("DrmRequestHandler");
    requestHandlerThread.start();
    postRequestHandler = new PostRequestHandler(requestHandlerThread.getLooper());

    DrmInitData.SchemeData schemeData = drmInitData.get(uuid);
    if (schemeData == null) {
      onError(new IllegalStateException("Media does not support uuid: " + uuid));
      return this;
    }
    schemeInitData = schemeData.data;
    schemeMimeType = schemeData.mimeType;
    if (Util.SDK_INT < 21) {
      // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
      byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(schemeInitData, C.WIDEVINE_UUID);
      if (psshData == null) {
        // Extraction failed. schemeData isn't a Widevine PSSH atom, so leave it unchanged.
      } else {
        schemeInitData = psshData;
      }
    }
    if (Util.SDK_INT < 26 && C.CLEARKEY_UUID.equals(uuid) && (
        MimeTypes.VIDEO_MP4.equals(schemeMimeType) || MimeTypes.AUDIO_MP4.equals(schemeMimeType))) {
      // Prior to API level 26 the ClearKey CDM only accepted "cenc" as the scheme for MP4.
      schemeMimeType = CENC_SCHEME_MIME_TYPE;
    }
    state = STATE_OPENING;
    openInternal(true);
    return this;
  }

  @Override public void closeSession() {
    if (--openCount != 0) {
      return;
    }
    state = STATE_CLOSED;
    provisioningInProgress = false;
    mediaDrmHandler.removeCallbacksAndMessages(null);
    postResponseHandler.removeCallbacksAndMessages(null);
    postRequestHandler.removeCallbacksAndMessages(null);
    postRequestHandler = null;
    requestHandlerThread.quit();
    requestHandlerThread = null;
    schemeInitData = null;
    schemeMimeType = null;
    mediaCrypto = null;
    lastException = null;
    if (sessionId != null) {
      mediaDrm.closeSession(sessionId);
      sessionId = null;
    }
  }

  @Override public int getState() {
    return state;
  }

  @Override public T getMediaCrypto() {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      throw new IllegalStateException();
    }
    return mediaCrypto;
  }

  @Override public boolean requiresSecureDecoderComponent(String mimeType) {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      throw new IllegalStateException();
    }
    return mediaCrypto.requiresSecureDecoderComponent(mimeType);
  }

  @Override public DrmSessionException getError() {
    return state == STATE_ERROR ? lastException : null;
  }

  @Override public Map<String, String> queryKeyStatus() {
    // User may call this method rightfully even if state == STATE_ERROR. So only check if there is
    // a sessionId
    if (sessionId == null) {
      throw new IllegalStateException();
    }
    return mediaDrm.queryKeyStatus(sessionId);
  }

  @Override public byte[] getOfflineLicenseKeySetId() {
    return new byte[0];
  }

  private void openInternal(boolean allowProvisioning) {
    try {
      sessionId = mediaDrm.openSession();
      mediaCrypto = (T) mediaDrm.createMediaCrypto(uuid, sessionId);
      state = STATE_OPENED;
      postKeyRequest();
    } catch (NotProvisionedException e) {
      if (allowProvisioning) {
        postProvisionRequest();
      } else {
        onError(e);
      }
    } catch (Exception e) {
      onError(e);
    }
  }

  private void postProvisionRequest() {
    if (provisioningInProgress) {
      return;
    }
    provisioningInProgress = true;
    ExoMediaDrm.ProvisionRequest request = mediaDrm.getProvisionRequest();
    postRequestHandler.obtainMessage(MSG_PROVISION, request).sendToTarget();
  }

  private void onProvisionResponse(Object response) {
    provisioningInProgress = false;
    if (state != STATE_OPENING && state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      // This event is stale.
      return;
    }

    if (response instanceof Exception) {
      onError((Exception) response);
      return;
    }

    try {
      mediaDrm.provideProvisionResponse((byte[]) response);
      if (state == STATE_OPENING) {
        openInternal(false);
      } else {
        postKeyRequest();
      }
    } catch (DeniedByServerException e) {
      onError(e);
    }
  }

  private void postKeyRequest() {
    try {
      ExoMediaDrm.KeyRequest keyRequest =
          mediaDrm.getKeyRequest(sessionId, schemeInitData, schemeMimeType,
              MediaDrm.KEY_TYPE_STREAMING, optionalKeyRequestParameters);
      postRequestHandler.obtainMessage(MSG_KEYS, keyRequest).sendToTarget();
    } catch (Exception e) {
      onKeysError(e);
    }
  }

  private void onKeyResponse(Object response) {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      // This event is stale.
      return;
    }

    if (response instanceof Exception) {
      onKeysError((Exception) response);
      return;
    }

    try {
      mediaDrm.provideKeyResponse(sessionId, (byte[]) response);
      state = STATE_OPENED_WITH_KEYS;
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(new Runnable() {
          @Override public void run() {
            eventListener.onDrmKeysLoaded();
          }
        });
      }
    } catch (Exception e) {
      onKeysError(e);
    }
  }

  private void onKeysError(Exception e) {
    if (e instanceof NotProvisionedException) {
      postProvisionRequest();
    } else {
      onError(e);
    }
  }

  private void onError(final Exception e) {
    lastException = new DrmSessionException(e);
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable() {
        @Override public void run() {
          eventListener.onDrmSessionManagerError(e);
        }
      });
    }
    if (state != STATE_OPENED_WITH_KEYS) {
      state = STATE_ERROR;
    }
  }

  @SuppressLint("HandlerLeak")
  private class MediaDrmHandler extends Handler {

    public MediaDrmHandler(Looper looper) {
      super(looper);
    }

    @SuppressWarnings("deprecation") @Override public void handleMessage(Message msg) {
      if (openCount == 0 || (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS)) {
        return;
      }
      switch (msg.what) {
        case MediaDrm.EVENT_KEY_REQUIRED:
          postKeyRequest();
          break;
        case MediaDrm.EVENT_KEY_EXPIRED:
          // When an already expired key is loaded MediaDrm sends this event immediately. Ignore
          // this event if the state isn't STATE_OPENED_WITH_KEYS yet which means we're still
          // waiting for key response.
          if (state == STATE_OPENED_WITH_KEYS) {
            state = STATE_OPENED;
            onError(new KeysExpiredException());
          }
          break;
        case MediaDrm.EVENT_PROVISION_REQUIRED:
          state = STATE_OPENED;
          postProvisionRequest();
          break;
      }
    }
  }

  private class MediaDrmEventListener implements ExoMediaDrm.OnEventListener<ExoMediaCrypto> {

    @Override
    public void onEvent(ExoMediaDrm<? extends ExoMediaCrypto> mediaDrm, byte[] sessionId, int event,
        int extra, byte[] data) {
      mediaDrmHandler.sendEmptyMessage(event);
    }
  }

  @SuppressLint("HandlerLeak")
  private class PostResponseHandler extends Handler {

    public PostResponseHandler(Looper looper) {
      super(looper);
    }

    @Override public void handleMessage(Message msg) {
      switch (msg.what) {
        case MSG_PROVISION:
          onProvisionResponse(msg.obj);
          break;
        case MSG_KEYS:
          onKeyResponse(msg.obj);
          break;
      }
    }
  }

  @SuppressLint("HandlerLeak")
  private class PostRequestHandler extends Handler {

    public PostRequestHandler(Looper backgroundLooper) {
      super(backgroundLooper);
    }

    @Override public void handleMessage(Message msg) {
      Object response;
      try {
        switch (msg.what) {
          case MSG_PROVISION:
            response =
                callback.executeProvisionRequest(uuid, (ExoMediaDrm.ProvisionRequest) msg.obj);
            break;
          case MSG_KEYS:
            response = callback.executeKeyRequest(uuid, (ExoMediaDrm.KeyRequest) msg.obj);
            break;
          default:
            throw new RuntimeException();
        }
      } catch (Exception e) {
        response = e;
      }
      postResponseHandler.obtainMessage(msg.what, response).sendToTarget();
    }
  }
}
