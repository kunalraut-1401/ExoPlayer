package com.google.android.exoplayer2.demo;

import android.annotation.TargetApi;
import android.media.MediaDrm;
import android.os.Handler;
import android.os.Looper;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DefaultDrmSession;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.ExoMediaCrypto;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import java.util.HashMap;

/**
 * A {@link DrmSessionManager} that supports playbacks using {@link MediaDrm}.
 */
@TargetApi(18)
public class AbemaDrmSessionManager<T extends ExoMediaCrypto> implements DrmSessionManager<T> {

  /**
   * The key to use when passing CustomData to a PlayReady instance in an optional parameter map.
   */
  public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";

  private DefaultDrmSession<T> widevine;
  private DefaultDrmSession<T> clearkey;

  /**
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   * to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   * null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   */
  public AbemaDrmSessionManager(MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
      DefaultDrmSession.EventListener eventListener) {

    this.widevine = new DefaultDrmSession<>(C.WIDEVINE_UUID, callback, optionalKeyRequestParameters,
        eventHandler, eventListener);
    this.clearkey = new DefaultDrmSession<>(C.CLEARKEY_UUID, callback, optionalKeyRequestParameters,
        eventHandler, eventListener);
  }

  @Override public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
    if (drmInitData.get(C.WIDEVINE_UUID) != null) {
      return widevine.openSession(playbackLooper, drmInitData);
    } else if (drmInitData.get(C.CLEARKEY_UUID) != null) {
      return clearkey.openSession(playbackLooper, drmInitData);
    }

    // @FIXME
    throw new IllegalStateException("WASABEEF");
  }

  @Override public void releaseSession(DrmSession<T> session) {
    session.closeSession();
  }
}
