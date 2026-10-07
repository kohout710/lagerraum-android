package de.lagerraum.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;

public class MainActivity extends Activity {
  private static final int FILE_CHOOSER = 1001;
  private static final int QR_CAMERA = 2001;

  private WebView webView;
  private ValueCallback<Uri[]> fileCallback;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);

    getWindow().setStatusBarColor(Color.rgb(15, 20, 16));
    getWindow().setNavigationBarColor(Color.rgb(15, 20, 16));

    webView = new WebView(this);
    webView.setBackgroundColor(Color.rgb(15, 20, 16));
    webView.setOnApplyWindowInsetsListener((v, insets) -> {
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
        v.setPadding(0, bars.top, 0, bars.bottom);
      } else {
        v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
      }
      return insets;
    });
    setContentView(webView);

    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setAllowFileAccess(true);

    webView.addJavascriptInterface(new AndroidBridge(), "Android");
    webView.setWebViewClient(new WebViewClient());
    webView.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        Intent intent;
        try { intent = params.createIntent(); }
        catch (Exception e) {
          intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("*/*");
        }
        startActivityForResult(intent, FILE_CHOOSER);
        return true;
      }
    });

    if (state == null) webView.loadUrl("file:///android_asset/index.html");
    else webView.restoreState(state);
  }

  private void notifyQrError(String message) {
    if (webView == null) return;
    String json = org.json.JSONObject.quote(message);
    webView.post(() -> webView.evaluateJavascript("window.onNativeQrError(" + json + ")", null));
  }

  private void notifyQrResult(String value) {
    if (webView == null) return;
    String json = org.json.JSONObject.quote(value);
    webView.post(() -> webView.evaluateJavascript("window.onNativeQrScanned(" + json + ")", null));
  }

  private String decodeQr(Bitmap bitmap) {
    if (bitmap == null) return null;
    try {
      int width = bitmap.getWidth();
      int height = bitmap.getHeight();
      int[] pixels = new int[width * height];
      bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
      RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
      BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
      Result result = new MultiFormatReader().decode(binary);
      return result == null ? null : result.getText();
    } catch (Throwable t) {
      return null;
    }
  }

  public class AndroidBridge {
    @JavascriptInterface
    public void scanQr() {
      runOnUiThread(() -> {
        try {
          Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
          if (intent.resolveActivity(getPackageManager()) == null) {
            notifyQrError("Keine Kamera-App gefunden.");
            return;
          }
          startActivityForResult(intent, QR_CAMERA);
        } catch (Throwable t) {
          notifyQrError("Kamera konnte nicht geöffnet werden.");
        }
      });
    }

    @JavascriptInterface
    public String generateQr(String content) {
      try {
        int size = 700;
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) {
          for (int x = 0; x < size; x++) {
            bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
          }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
      } catch (Throwable t) {
        return "";
      }
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == QR_CAMERA) {
      if (resultCode != RESULT_OK || data == null || data.getExtras() == null) {
        notifyQrError("Aufnahme abgebrochen.");
        return;
      }

      Object value = data.getExtras().get("data");
      if (!(value instanceof Bitmap)) {
        notifyQrError("Kamerabild konnte nicht gelesen werden.");
        return;
      }

      String decoded = decodeQr((Bitmap) value);
      if (decoded == null || decoded.trim().isEmpty()) {
        notifyQrError("Kein QR-Code erkannt. Bitte den QR-Code größer und mittig aufnehmen.");
      } else {
        notifyQrResult(decoded);
      }
      return;
    }

    if (requestCode == FILE_CHOOSER && fileCallback != null) {
      fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
      fileCallback = null;
    }
  }

  @Override protected void onSaveInstanceState(Bundle outState) {
    if (webView != null) webView.saveState(outState);
    super.onSaveInstanceState(outState);
  }

  @Override public void onBackPressed() {
    if (webView != null && webView.canGoBack()) webView.goBack();
    else super.onBackPressed();
  }

  @Override protected void onDestroy() {
    if (webView != null) { webView.destroy(); webView = null; }
    super.onDestroy();
  }
}
