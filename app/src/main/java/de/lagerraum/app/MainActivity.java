package de.lagerraum.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.CaptureActivity;

import java.io.ByteArrayOutputStream;

public class MainActivity extends Activity {
  private static final int FILE_CHOOSER = 1001;
  private static final int CAMERA_PERMISSION = 1002;
  private WebView webView;
  private ValueCallback<Uri[]> fileCallback;
  private boolean scanAfterPermission = false;

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

      @Override public void onPermissionRequest(PermissionRequest request) {
        runOnUiThread(() -> {
          if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(request.getResources());
          } else {
            request.deny();
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
          }
        });
      }
    });

    if (state == null) webView.loadUrl("file:///android_asset/index.html");
    else webView.restoreState(state);
  }

  private void startQrScanner() {
    try {
      IntentIntegrator integrator = new IntentIntegrator(this);
      integrator.setCaptureActivity(CaptureActivity.class);
      integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
      integrator.setPrompt("QR-Code scannen");
      integrator.setBeepEnabled(true);
      integrator.setOrientationLocked(false);
      integrator.initiateScan();
    } catch (Throwable t) {
      notifyQrError("QR-Scanner konnte nicht gestartet werden.");
    }
  }

  private void notifyQrError(String message) {
    if (webView == null) return;
    String json = org.json.JSONObject.quote(message);
    webView.post(() -> webView.evaluateJavascript("window.onNativeQrError(" + json + ")", null));
  }

  public class AndroidBridge {
    @JavascriptInterface
    public void scanQr() {
      runOnUiThread(() -> {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
          startQrScanner();
        } else {
          scanAfterPermission = true;
          requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
      });
    }

    @JavascriptInterface
    public String generateQr(String content) {
      try {
        BarcodeEncoder encoder = new BarcodeEncoder();
        Bitmap bitmap = encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 700, 700);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
      } catch (Exception e) {
        return "";
      }
    }
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == CAMERA_PERMISSION) {
      boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
      if (granted && scanAfterPermission) {
        scanAfterPermission = false;
        startQrScanner();
      } else if (!granted) {
        scanAfterPermission = false;
        notifyQrError("Kamerazugriff wurde nicht erlaubt.");
      }
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
    if (scan != null) {
      if (scan.getContents() != null && webView != null) {
        String json = org.json.JSONObject.quote(scan.getContents());
        webView.evaluateJavascript("window.onNativeQrScanned(" + json + ")", null);
      }
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
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
