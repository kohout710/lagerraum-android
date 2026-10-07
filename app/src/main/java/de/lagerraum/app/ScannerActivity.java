package de.lagerraum.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.InvertedLuminanceSource;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.util.Arrays;
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
  private ImageCapture imageCapture;
  private final AtomicBoolean busy = new AtomicBoolean(false);
  private Button torchButton;
  private Button captureButton;
  private TextView statusText;
  private BarcodeScanner mlScanner;

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
    hint.setText("QR-Code oder Barcode in den Rahmen legen");
    hint.setTextColor(Color.WHITE);
    hint.setTextSize(18f);
    hint.setGravity(Gravity.CENTER);
    hint.setBackgroundColor(0x99000000);
    FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, dp(56));
    hintParams.gravity = Gravity.TOP;
    hintParams.topMargin = dp(24);
    hintParams.leftMargin = dp(18);
    hintParams.rightMargin = dp(18);
    root.addView(hint, hintParams);

    statusText = new TextView(this);
    statusText.setText("Umgebung außerhalb des Rahmens wird möglichst ausgeblendet.");
    statusText.setTextColor(Color.WHITE);
    statusText.setTextSize(15f);
    statusText.setGravity(Gravity.CENTER);
    statusText.setBackgroundColor(0x88000000);
    FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, dp(54));
    statusParams.gravity = Gravity.BOTTOM;
    statusParams.bottomMargin = dp(156);
    statusParams.leftMargin = dp(18);
    statusParams.rightMargin = dp(18);
    root.addView(statusText, statusParams);

    captureButton = new Button(this);
    captureButton.setText("Code fotografieren");
    FrameLayout.LayoutParams captureParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, dp(58));
    captureParams.gravity = Gravity.BOTTOM;
    captureParams.leftMargin = dp(18);
    captureParams.rightMargin = dp(18);
    captureParams.bottomMargin = dp(88);
    root.addView(captureButton, captureParams);
    captureButton.setOnClickListener(v -> captureQr());

    Button cancel = new Button(this);
    cancel.setText("Abbrechen");
    FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(dp(140), dp(54));
    cancelParams.gravity = Gravity.BOTTOM | Gravity.START;
    cancelParams.leftMargin = dp(18);
    cancelParams.bottomMargin = dp(24);
    root.addView(cancel, cancelParams);
    cancel.setOnClickListener(v -> finish());

    torchButton = new Button(this);
    torchButton.setText("Taschenlampe");
    FrameLayout.LayoutParams torchParams = new FrameLayout.LayoutParams(dp(160), dp(54));
    torchParams.gravity = Gravity.BOTTOM | Gravity.END;
    torchParams.rightMargin = dp(18);
    torchParams.bottomMargin = dp(24);
    root.addView(torchButton, torchParams);
    torchButton.setOnClickListener(v -> toggleTorch());

    setContentView(root);
    cameraExecutor = Executors.newSingleThreadExecutor();
    BarcodeScannerOptions mlOptions = new BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_ITF)
        .build();
    mlScanner = BarcodeScanning.getClient(mlOptions);

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCamera();
    } else {
      ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }
  }

  private void startCamera() {
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(() -> {
      try {
        ProcessCameraProvider provider = future.get();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build();

        provider.unbindAll();
        camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture);

        boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
        torchButton.setEnabled(hasFlash);
        if (!hasFlash) torchButton.setText("Keine Lampe");
      } catch (Throwable t) {
        statusText.setText("Kamera konnte nicht gestartet werden.");
        captureButton.setEnabled(false);
      }
    }, ContextCompat.getMainExecutor(this));
  }

  private void captureQr() {
    if (imageCapture == null || !busy.compareAndSet(false, true)) return;

    captureButton.setEnabled(false);
    statusText.setText("Foto wird ausgewertet …");

    try {
      File photo = File.createTempFile("lagerraum_qr_", ".jpg", getCacheDir());
      ImageCapture.OutputFileOptions options =
          new ImageCapture.OutputFileOptions.Builder(photo).build();

      imageCapture.takePicture(
          options,
          cameraExecutor,
          new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
              analyzePhoto(photo);
            }

            @Override public void onError(@NonNull ImageCaptureException exception) {
              runOnUiThread(() -> {
                statusText.setText("Foto konnte nicht aufgenommen werden.");
                captureButton.setEnabled(true);
                busy.set(false);
              });
            }
          });
    } catch (Throwable t) {
      statusText.setText("Foto konnte nicht vorbereitet werden.");
      captureButton.setEnabled(true);
      busy.set(false);
    }
  }

  private void analyzePhoto(File photo) {
    cameraExecutor.execute(() -> {
      try {
        Bitmap bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath());
        photo.delete();

        if (bitmap == null) {
          recognitionFailed();
          return;
        }

        // Zuerst nur den mittleren Scanbereich auswerten, damit Text,
        // Verpackungsgrafiken und Farben außerhalb des Rahmens nicht stören.
        float[] cropFractions = new float[]{0.64f, 0.76f, 0.90f, 1.00f};

        for (float fraction : cropFractions) {
          Bitmap crop = centerCrop(bitmap, fraction);
          String zxing = decodeBitmap(crop);
          if (zxing != null && !zxing.trim().isEmpty()) {
            if (crop != bitmap) crop.recycle();
            bitmap.recycle();
            finishWithCode(zxing);
            return;
          }
          if (crop != bitmap) crop.recycle();
        }

        runMlKitCrops(bitmap, cropFractions, 0);
      } catch (Throwable t) {
        photo.delete();
        recognitionFailed();
      }
    });
  }

  private void runMlKitCrops(Bitmap original, float[] fractions, int index) {
    if (index >= fractions.length) {
      original.recycle();
      recognitionFailed();
      return;
    }

    Bitmap crop = centerCrop(original, fractions[index]);
    InputImage image = InputImage.fromBitmap(crop, 0);

    mlScanner.process(image)
        .addOnSuccessListener(barcodes -> {
          for (Barcode barcode : barcodes) {
            String value = barcode.getRawValue();
            if (value != null && !value.trim().isEmpty()) {
              if (crop != original) crop.recycle();
              original.recycle();
              finishWithCode(value);
              return;
            }
          }

          if (crop != original) crop.recycle();
          runMlKitCrops(original, fractions, index + 1);
        })
        .addOnFailureListener(e -> {
          if (crop != original) crop.recycle();
          runMlKitCrops(original, fractions, index + 1);
        });
  }

  private Bitmap centerCrop(Bitmap bitmap, float fraction) {
    int width = Math.max(64, Math.round(bitmap.getWidth() * fraction));
    int height = Math.max(64, Math.round(bitmap.getHeight() * Math.min(1.0f, fraction + 0.08f)));
    width = Math.min(width, bitmap.getWidth());
    height = Math.min(height, bitmap.getHeight());
    int left = Math.max(0, (bitmap.getWidth() - width) / 2);
    int top = Math.max(0, (bitmap.getHeight() - height) / 2);
    return Bitmap.createBitmap(bitmap, left, top, width, height);
  }

  private void finishWithCode(String decoded) {
    runOnUiThread(() -> {
      if (isFinishing()) return;
      previewView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
      statusText.setText("Code erkannt");
      Intent data = new Intent();
      data.putExtra("qr_value", decoded);
      setResult(RESULT_OK, data);
      finish();
    });
  }

  private void recognitionFailed() {
    runOnUiThread(() -> {
      if (isFinishing()) return;
      statusText.setText("Nicht erkannt. Bitte QR-Code oder Barcode vollständig und scharf im Rahmen platzieren.");
      captureButton.setEnabled(true);
      busy.set(false);
    });
  }

  private String decodeBitmap(Bitmap original) {
    if (original == null) return null;

    int[] angles = new int[]{0, 90, 180, 270};
    for (int angle : angles) {
      Bitmap bitmap = angle == 0 ? original : rotateBitmap(original, angle);

      Result result = decodeBitmapOnce(bitmap, false);
      if (result == null) result = decodeBitmapOnce(bitmap, true);
      if (result != null) return result.getText();

      int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
      int cropSize = Math.max(1, (int)(size * 0.86f));
      int left = Math.max(0, (bitmap.getWidth() - cropSize) / 2);
      int top = Math.max(0, (bitmap.getHeight() - cropSize) / 2);
      Bitmap crop = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize);

      result = decodeBitmapOnce(crop, false);
      if (result == null) result = decodeBitmapOnce(crop, true);
      if (crop != bitmap) crop.recycle();
      if (result != null) return result.getText();

      if (bitmap != original) bitmap.recycle();
    }
    return null;
  }

  private Result decodeBitmapOnce(Bitmap bitmap, boolean inverted) {
    try {
      int width = bitmap.getWidth();
      int height = bitmap.getHeight();
      int[] pixels = new int[width * height];
      bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

      LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
      if (inverted) source = new InvertedLuminanceSource(source);

      BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
      MultiFormatReader reader = new MultiFormatReader();
      Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
      hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
          BarcodeFormat.QR_CODE,
          BarcodeFormat.EAN_13,
          BarcodeFormat.EAN_8,
          BarcodeFormat.UPC_A,
          BarcodeFormat.UPC_E,
          BarcodeFormat.CODE_128,
          BarcodeFormat.CODE_39,
          BarcodeFormat.ITF));
      hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
      hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
      reader.setHints(hints);
      return reader.decodeWithState(binary);
    } catch (Throwable t) {
      return null;
    }
  }

  private Bitmap rotateBitmap(Bitmap source, int degrees) {
    Matrix matrix = new Matrix();
    matrix.postRotate(degrees);
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
  }

  private void toggleTorch() {
    if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
    Integer state = camera.getCameraInfo().getTorchState().getValue();
    boolean enabled = state != null && state == 1;
    camera.getCameraControl().enableTorch(!enabled);
    torchButton.setText(enabled ? "Taschenlampe" : "Lampe aus");
  }

  @Override public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == CAMERA_PERMISSION) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
      else { setResult(RESULT_CANCELED); finish(); }
    }
  }

  @Override protected void onDestroy() {
    if (mlScanner != null) mlScanner.close();
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
      float boxW = w * 0.82f;
      float boxH = Math.min(h * 0.34f, boxW * 0.62f);
      float left = (w - boxW) / 2f;
      float top = (h - boxH) / 2f - h * 0.08f;
      RectF box = new RectF(left, top, left + boxW, top + boxH);

      canvas.drawRect(0, 0, w, box.top, shade);
      canvas.drawRect(0, box.bottom, w, h, shade);
      canvas.drawRect(0, box.top, box.left, box.bottom, shade);
      canvas.drawRect(box.right, box.top, w, box.bottom, shade);
      canvas.drawRoundRect(box, 18f, 18f, frame);

      float c = Math.min(Math.min(box.width(), box.height()) * 0.16f, 64f);
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
