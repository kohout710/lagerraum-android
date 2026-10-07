package de.lagerraum.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScannerActivity extends ComponentActivity {
  private static final int CAMERA_PERMISSION = 3001;

  private PreviewView previewView;
  private ExecutorService cameraExecutor;
  private Camera camera;
  private final AtomicBoolean finished = new AtomicBoolean(false);
  private Button torchButton;
  private TextView statusText;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);

    previewView = new PreviewView(this);
    previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
    root.addView(previewView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT));

    root.addView(new FinderOverlay(this), new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT));

    TextView hint = new TextView(this);
    hint.setText("QR-Code in den Rahmen halten");
    hint.setTextColor(Color.WHITE);
    hint.setTextSize(18f);
    hint.setGravity(Gravity.CENTER);
    hint.setBackgroundColor(0x99000000);
    FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        dp(56));
    hintParams.gravity = Gravity.TOP;
    hintParams.topMargin = dp(24);
    hintParams.leftMargin = dp(18);
    hintParams.rightMargin = dp(18);
    root.addView(hint, hintParams);

    statusText = new TextView(this);
    statusText.setText("Suche QR-Code …");
    statusText.setTextColor(Color.WHITE);
    statusText.setTextSize(16f);
    statusText.setGravity(Gravity.CENTER);
    statusText.setBackgroundColor(0x88000000);
    FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        dp(48));
    statusParams.gravity = Gravity.BOTTOM;
    statusParams.bottomMargin = dp(94);
    statusParams.leftMargin = dp(18);
    statusParams.rightMargin = dp(18);
    root.addView(statusText, statusParams);

    Button cancel = new Button(this);
    cancel.setText("Abbrechen");
    FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
        dp(140), dp(54));
    cancelParams.gravity = Gravity.BOTTOM | Gravity.START;
    cancelParams.leftMargin = dp(18);
    cancelParams.bottomMargin = dp(28);
    root.addView(cancel, cancelParams);
    cancel.setOnClickListener(v -> finish());

    torchButton = new Button(this);
    torchButton.setText("Taschenlampe");
    FrameLayout.LayoutParams torchParams = new FrameLayout.LayoutParams(
        dp(160), dp(54));
    torchParams.gravity = Gravity.BOTTOM | Gravity.END;
    torchParams.rightMargin = dp(18);
    torchParams.bottomMargin = dp(28);
    root.addView(torchButton, torchParams);
    torchButton.setOnClickListener(v -> toggleTorch());

    setContentView(root);

    cameraExecutor = Executors.newSingleThreadExecutor();

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) {
      startCamera();
    } else {
      ActivityCompat.requestPermissions(
          this,
          new String[]{Manifest.permission.CAMERA},
          CAMERA_PERMISSION);
    }
  }

  private void startCamera() {
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(() -> {
      try {
        ProcessCameraProvider provider = future.get();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();

        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
          if (finished.get()) {
            imageProxy.close();
            return;
          }

          try {
            String decoded = decodeQr(imageProxy);
            if (decoded != null && !decoded.trim().isEmpty()) {
              if (finished.compareAndSet(false, true)) {
                runOnUiThread(() -> {
                  statusText.setText("QR-Code erkannt");
                  previewView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                  Intent data = new Intent();
                  data.putExtra("qr_value", decoded);
                  setResult(RESULT_OK, data);
                  finish();
                });
              }
            }
          } catch (Throwable ignored) {
          } finally {
            imageProxy.close();
          }
        });

        provider.unbindAll();
        camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis);

        boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
        torchButton.setEnabled(hasFlash);
        if (!hasFlash) torchButton.setText("Keine Lampe");
      } catch (Throwable t) {
        setResult(RESULT_CANCELED);
        finish();
      }
    }, ContextCompat.getMainExecutor(this));
  }

  private String decodeQr(ImageProxy imageProxy) {
    ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
    if (planes == null || planes.length == 0) return null;

    int width = imageProxy.getWidth();
    int height = imageProxy.getHeight();
    int rowStride = planes[0].getRowStride();

    ByteBuffer buffer = planes[0].getBuffer();
    buffer.rewind();
    byte[] raw = new byte[buffer.remaining()];
    buffer.get(raw);

    byte[] y = new byte[width * height];
    if (rowStride == width) {
      System.arraycopy(raw, 0, y, 0, Math.min(y.length, raw.length));
    } else {
      for (int row = 0; row < height; row++) {
        int src = row * rowStride;
        int dst = row * width;
        if (src + width <= raw.length) {
          System.arraycopy(raw, src, y, dst, width);
        }
      }
    }

    MultiFormatReader reader = new MultiFormatReader();
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    reader.setHints(hints);

    Result result = tryDecode(reader, y, width, height, 0, 0, width, height);
    if (result != null) return result.getText();

    int crop = (int)(Math.min(width, height) * 0.82f);
    int left = Math.max(0, (width - crop) / 2);
    int top = Math.max(0, (height - crop) / 2);
    result = tryDecode(reader, y, width, height, left, top, crop, crop);
    return result == null ? null : result.getText();
  }

  private Result tryDecode(
      MultiFormatReader reader,
      byte[] y,
      int dataWidth,
      int dataHeight,
      int left,
      int top,
      int width,
      int height) {
    try {
      PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
          y, dataWidth, dataHeight, left, top, width, height, false);
      BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
      return reader.decodeWithState(bitmap);
    } catch (Throwable t) {
      reader.reset();
      return null;
    }
  }

  private void toggleTorch() {
    if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
    Integer state = camera.getCameraInfo().getTorchState().getValue();
    boolean enabled = state != null && state == 1;
    camera.getCameraControl().enableTorch(!enabled);
    torchButton.setText(enabled ? "Taschenlampe" : "Lampe aus");
  }

  @Override public void onRequestPermissionsResult(
      int requestCode,
      @NonNull String[] permissions,
      @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == CAMERA_PERMISSION) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        startCamera();
      } else {
        setResult(RESULT_CANCELED);
        finish();
      }
    }
  }

  @Override protected void onDestroy() {
    if (cameraExecutor != null) cameraExecutor.shutdown();
    super.onDestroy();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static class FinderOverlay extends View {
    private final Paint shade = new Paint();
    private final Paint frame = new Paint();
    private final Paint corners = new Paint();

    FinderOverlay(android.content.Context context) {
      super(context);
      shade.setColor(0x99000000);
      frame.setColor(0x88FFFFFF);
      frame.setStyle(Paint.Style.STROKE);
      frame.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
      corners.setColor(0xFFFFFFFF);
      corners.setStyle(Paint.Style.STROKE);
      corners.setStrokeWidth(5f * getResources().getDisplayMetrics().density);
      corners.setStrokeCap(Paint.Cap.SQUARE);
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float w = getWidth();
      float h = getHeight();
      float size = Math.min(w * 0.78f, h * 0.46f);
      float left = (w - size) / 2f;
      float top = (h - size) / 2f - h * 0.05f;
      RectF box = new RectF(left, top, left + size, top + size);

      canvas.drawRect(0, 0, w, box.top, shade);
      canvas.drawRect(0, box.bottom, w, h, shade);
      canvas.drawRect(0, box.top, box.left, box.bottom, shade);
      canvas.drawRect(box.right, box.top, w, box.bottom, shade);
      canvas.drawRoundRect(box, 18f, 18f, frame);

      float c = Math.min(size * 0.13f, 64f);
      canvas.drawLine(box.left, box.top, box.left + c, box.top, corners);
      canvas.drawLine(box.left, box.top, box.left, box.top + c, corners);
      canvas.drawLine(box.right, box.top, box.right - c, box.top, corners);
      canvas.drawLine(box.right, box.top, box.right, box.top + c, corners);
      canvas.drawLine(box.left, box.bottom, box.left + c, box.bottom, corners);
      canvas.drawLine(box.left, box.bottom, box.left, box.bottom - c, corners);
      canvas.drawLine(box.right, box.bottom, box.right - c, box.bottom, corners);
      canvas.drawLine(box.right, box.bottom, box.right, box.bottom - c, corners);
    }
  }
}
