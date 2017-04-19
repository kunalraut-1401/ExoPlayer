package com.google.android.exoplayer2.demo;

import android.net.Uri;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.ExoMediaDrm;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.gson.Gson;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Created by Daichi Furiya on 2017/01/27.
 */

public class AbemaDrmCallback implements MediaDrmCallback {

  private class JsonWebKey {
    public List<Key> keys;
    public String type;
  }

  private class Key {
    public String kty;
    public String k;
    public String kid;
  }

  private static final Map<String, String> PLAYREADY_KEY_REQUEST_PROPERTIES;

  static {
    PLAYREADY_KEY_REQUEST_PROPERTIES = new HashMap<>();
    PLAYREADY_KEY_REQUEST_PROPERTIES.put("Content-Type", "text/xml");
    PLAYREADY_KEY_REQUEST_PROPERTIES.put("SOAPAction",
        "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense");
  }

  private final HttpDataSource.Factory dataSourceFactory;
  private final Uri licenseUri;
  private final String deviceId;
  private final Map<String, String> keyRequestProperties;

  private Gson gson = new Gson();

  /**
   * @param licenseUri The default license URL.
   * @param dataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
   * @param keyRequestProperties Request properties to set when making key requests, or null.
   */
  public AbemaDrmCallback(Uri licenseUri, HttpDataSource.Factory dataSourceFactory,
      Map<String, String> keyRequestProperties, String deviceId) {
    this.dataSourceFactory = dataSourceFactory;
    this.licenseUri = licenseUri;
    this.deviceId = deviceId;
    this.keyRequestProperties = keyRequestProperties;
  }

  @Override public byte[] executeProvisionRequest(UUID uuid, ExoMediaDrm.ProvisionRequest request)
      throws IOException {
    String url = request.getDefaultUrl() + "&signedRequest=" + new String(request.getData());
    return executePost(Uri.parse(url), new byte[0], null);
  }

  @Override public byte[] executeKeyRequest(UUID uuid, ExoMediaDrm.KeyRequest request)
      throws Exception {
    Map<String, String> requestProperties = new HashMap<>();
    requestProperties.put("Content-Type", "application/octet-stream");
    if (C.PLAYREADY_UUID.equals(uuid)) {
      requestProperties.putAll(PLAYREADY_KEY_REQUEST_PROPERTIES);
    }
    if (keyRequestProperties != null) {
      requestProperties.putAll(keyRequestProperties);
    }

    String clearKeyLicenseUrl = "https://license-master.abema.io/abematv?t=6Zw1sv9zt69ykf1oFeCzrhVkPsiZcyiyWQsJBHjMU7fq5PMoK7qpf97VsmugHrDDWBKH2fkBoqpCWtfBwNv9wvfKe2rBj4hyCjPn6rpGS3MaGY1xx1QiquyTLC32MGQHLvbQbimniNvfWYzS8g5XqsMoc2CaiQ62cKGTxy41JA5ieBxUGH";
    String widevineLicenseUrl = "https://license-master.abema.io/widevine?t=6Zw1sv9zt69ykf1oFeCzrhVkPsiZcyiyWQsJBHjMU7fq5PMoK7qpf97VsmugHrDDWBKH2fkBoqpCWtfBwNv9wvfKe2rBj4hyCjPn6rpGS3MaGY1xx1QiquyTLC32MGQHLvbQbimniNvfWYzS8g5XqsMoc2CaiQ62cKGTxy41JA5ieBxUGH";

    // @FIXME UUID
    if (C.CLEARKEY_UUID.equals(uuid)) {
      return executePostForCENC(Uri.parse(clearKeyLicenseUrl), request.getData(),
          requestProperties);
    } else if (C.WIDEVINE_UUID.equals(uuid)) {
      return executePost(Uri.parse(widevineLicenseUrl), request.getData(), requestProperties);
    } else {
      throw new IllegalArgumentException("This device does not support the required DRM scheme.");
    }
  }

  private byte[] executePost(Uri uri, byte[] data, Map<String, String> requestProperties)
      throws IOException {
    HttpDataSource dataSource = dataSourceFactory.createDataSource();
    if (requestProperties != null) {
      for (Map.Entry<String, String> requestProperty : requestProperties.entrySet()) {
        dataSource.setRequestProperty(requestProperty.getKey(), requestProperty.getValue());
      }
    }
    DataSpec dataSpec =
        new DataSpec(uri, data, 0, 0, C.LENGTH_UNSET, null, DataSpec.FLAG_ALLOW_GZIP);
    DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
    try {
      return Util.toByteArray(inputStream);
    } finally {
      Util.closeQuietly(inputStream);
    }
  }

  private byte[] executePostForCENC(Uri uri, byte[] data, Map<String, String> requestProperties)
      throws IOException {
    HttpDataSource dataSource = dataSourceFactory.createDataSource();
    if (requestProperties != null) {
      for (Map.Entry<String, String> requestProperty : requestProperties.entrySet()) {
        dataSource.setRequestProperty(requestProperty.getKey(), requestProperty.getValue());
      }
    }
    DataSpec dataSpec =
        new DataSpec(uri, data, 0, 0, C.LENGTH_UNSET, null, DataSpec.FLAG_ALLOW_GZIP);
    InputStream inputStream =
        new BufferedInputStream(new DataSourceInputStream(dataSource, dataSpec));

    BufferedReader streamReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
    StringBuilder response = new StringBuilder();

    String inputStr;
    while ((inputStr = streamReader.readLine()) != null) response.append(inputStr);
    Util.closeQuietly(inputStream);

    JsonWebKey jwk = gson.fromJson(response.toString(), JsonWebKey.class);
    Key key = jwk.keys.get(0);

    if (!key.k.contains(".")) {
      return gson.toJson(jwk).getBytes();
    }

    String[] k = key.k.split("\\.");
    byte[] encryptedKey = Base58.decode(k[0]);
    byte[] encryptedPublicKey = Base58.decode(k[1]);
    byte[] iv = decodeHex(k[2]);

    SecretKeySpec commonSecret = getCommonSecretV2();

    byte[] hashKey = hmacSha256((key.kid + deviceId).getBytes(), commonSecret);
    byte[] publicKey = getEncryptionECBKey(encryptedPublicKey, hashKey);

    byte[] decryptionKey = getEncryptionCBCKey(encryptedKey, publicKey, iv);
    key.k = new String(decryptionKey).replace('-', '+').replace('_', '/');

    return gson.toJson(jwk).getBytes();
  }

  private static SecretKeySpec getCommonSecretV2() {
    byte[] src = new byte[] {
        -44, -73, 24, -69, -70, -100, -5, 125, 1, -110, -91, -113, -98, 45, 20, 106, -4, 93, -78,
        -98, 67, 82, -34, 5, -4, 76, -14, -63, 0, 88, 4, -69
    };
    byte[] dst = new byte[32];
    RC4 rc4 = new RC4(
        new byte[] { -37, -104, -88, -25, -50, -54, 52, 36, -39, 117, 40, 15, -112, -67, 3, -18 });
    rc4.update(src, dst);
    return new SecretKeySpec(dst, "hmacSHA256");
  }

  /**
   * Get encryption key from source bytes and key bytes.
   *
   * @param source An array binary of source
   * @param key An array binary of encryption key
   * @return Decrypted TS key
   * @throws IOException
   */
  private static byte[] getEncryptionECBKey(final byte[] source, final byte[] key)
      throws IOException {
    SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
    try {
      Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, keySpec);
      return cipher.doFinal(source);
    } catch (GeneralSecurityException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  private static byte[] getEncryptionCBCKey(final byte[] source, final byte[] key, final byte[] iv)
      throws IOException {
    SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
    try {
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
      AlgorithmParameterSpec cipherIV = new IvParameterSpec(iv);
      cipher.init(Cipher.DECRYPT_MODE, keySpec, cipherIV);
      return cipher.doFinal(source);
    } catch (GeneralSecurityException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /**
   * Calculate HMac-SHA256 hash with private common key.
   *
   * @param source An array of binary.
   * @return Hashed bytes
   * @throws IOException
   */
  private static byte[] hmacSha256(final byte[] source, SecretKeySpec spec) throws IOException {
    try {
      Mac mac = Mac.getInstance(spec.getAlgorithm());
      mac.init(spec);
      return mac.doFinal(source);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /**
   * decodeHex extracted from Apache Commons Codec library.
   *
   * @param text A string containing hexadecimal digits
   * @return A byte array containing binary data decoded from the supplied char array.
   */
  private static byte[] decodeHex(final String text) throws IOException {

    final char[] data = text.toCharArray();
    final int len = data.length;

    if ((len & 0x01) != 0) {
      throw new IOException("Odd number of characters.");
    }
    final byte[] out = new byte[len >> 1];
    // two characters form the hex value.
    for (int i = 0, j = 0; j < len; i++) {
      int f = toDigit(data[j], j) << 4;
      j++;
      f = f | toDigit(data[j], j);
      j++;
      out[i] = (byte) (f & 0xFF);
    }

    return out;
  }

  private static int toDigit(final char ch, final int index) throws IOException {
    final int digit = Character.digit(ch, 16);
    if (digit == -1) {
      throw new IOException("Illegal hexadecimal character " + ch + " at index " + index);
    }
    return digit;
  }
}
