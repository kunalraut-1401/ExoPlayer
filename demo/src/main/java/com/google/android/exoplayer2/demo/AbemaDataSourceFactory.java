package com.google.android.exoplayer2.demo;

import android.content.Context;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import okhttp3.OkHttpClient;

public final class AbemaDataSourceFactory implements DataSource.Factory {

  OkHttpClient client = new OkHttpClient();

  private final Context context;
  private final TransferListener<? super DataSource> listener;
  private final DataSource.Factory baseDataSourceFactory;
  private final String deviceId;

  public AbemaDataSourceFactory(Context context, String userAgent,
      TransferListener<? super DataSource> listener, String deviceId) {
    this.context = context.getApplicationContext();
    this.listener = listener;
    this.baseDataSourceFactory = new OkHttpDataSourceFactory(client, userAgent, listener);
    this.deviceId = deviceId;
  }

  @Override public AbemaDataSource createDataSource() {
    return new AbemaDataSource(context, listener, baseDataSourceFactory.createDataSource(),
        deviceId);
  }
}

