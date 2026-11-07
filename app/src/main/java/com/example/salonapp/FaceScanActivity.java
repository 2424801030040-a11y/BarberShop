package com.example.salonapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Automatic front-camera scan. Only four face-geometry ratios are returned; images stay on-device. */
public class FaceScanActivity extends AppCompatActivity {
    public static final String EXTRA_FACE_ASPECT = "face_aspect_ratio";
    public static final String EXTRA_FOREHEAD_CHEEK = "forehead_to_cheek_ratio";
    public static final String EXTRA_JAW_CHEEK = "jaw_to_cheek_ratio";
    public static final String EXTRA_FOREHEAD_JAW = "forehead_to_jaw_ratio";

    private static final long FRAME_INTERVAL_MS = 160L;
    private PreviewView previewView;
    private OvalGuideView guideView;
    private TextView status;
    private FaceLandmarker landmarker;
    private ImageAnalysis imageAnalysis;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService scanExecutor;
    private volatile boolean scanCompleted;
    private long lastProcessedFrameMs;
    private int centeredFrames;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else showFailure("Để quét khuôn mặt, vui lòng cấp quyền sử dụng máy ảnh.");
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        scanExecutor = Executors.newSingleThreadExecutor();
        buildInterface();
        try {
            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.55f)
                    .setMinFacePresenceConfidence(0.55f)
                    .build();
            landmarker = FaceLandmarker.createFromOptions(this, options);
        } catch (Exception error) {
            showFailure("Chưa khởi động được tính năng quét. Hãy kết nối Internet, dựng lại ứng dụng và thử lại.");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        guideView = new OvalGuideView(this);
        root.addView(guideView, new FrameLayout.LayoutParams(-1, -1));

        TextView instructions = new TextView(this);
        instructions.setText("Đặt trọn khuôn mặt vào khung và nhìn thẳng vào máy ảnh.");
        instructions.setTextColor(Color.WHITE);
        instructions.setTextSize(16);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(20, 14, 20, 14);
        instructions.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams instructionParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        instructionParams.setMargins(16, 24, 16, 0);
        root.addView(instructions, instructionParams);

        status = new TextView(this);
        status.setText("Đang tìm khuôn mặt…");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(14, 9, 14, 9);
        status.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        statusParams.setMargins(16, 0, 16, 24);
        root.addView(status, statusParams);
        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                imageAnalysis.setAnalyzer(scanExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
                status.setText("Đưa khuôn mặt vào giữa khung để bắt đầu quét tự động.");
            } catch (Exception error) {
                showFailure("Không mở được máy ảnh trước. Hãy kiểm tra quyền máy ảnh rồi thử lại.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy frame) {
        Bitmap bitmap = null;
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (scanCompleted || now - lastProcessedFrameMs < FRAME_INTERVAL_MS) return;
            lastProcessedFrameMs = now;
            bitmap = toUprightBitmap(frame);
            if (bitmap == null) return;
            MPImage image = new BitmapImageBuilder(bitmap).build();
            FaceLandmarkerResult result = landmarker.detect(image);
            List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> faces = result.faceLandmarks();
            if (faces == null || faces.isEmpty()) {
                centeredFrames = 0;
                runOnUiThread(() -> { if (!isFinishing()) status.setText("Đang tìm khuôn mặt… Hãy nhìn thẳng vào máy ảnh."); });
                return;
            }
            List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> points = faces.get(0);
            Ratios ratios = measureFace(points, bitmap.getWidth(), bitmap.getHeight());
            int guideResult = faceGuideResult(points, bitmap.getWidth(), bitmap.getHeight());
            if (ratios == null || guideResult != 0) {
                centeredFrames = 0;
                String guidance = ratios == null
                        ? "Hãy nhìn thẳng vào máy ảnh và đảm bảo đủ sáng."
                        : guideResult == 1 ? "Căn giữa khuôn mặt trong khung."
                        : guideResult == 2 ? "Hãy đưa điện thoại gần khuôn mặt hơn."
                        : "Hãy lùi điện thoại ra xa một chút.";
                runOnUiThread(() -> { if (!isFinishing()) status.setText(guidance); });
                return;
            }
            centeredFrames++;
            if (centeredFrames >= 3 && !scanCompleted) {
                scanCompleted = true;
                runOnUiThread(() -> finishScan(ratios));
            }
        } catch (Exception error) {
            centeredFrames = 0;
            runOnUiThread(() -> { if (!isFinishing()) status.setText("Đang điều chỉnh khung quét…"); });
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            frame.close();
        }
    }

    private Bitmap toUprightBitmap(ImageProxy frame) {
        ImageProxy.PlaneProxy plane = frame.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = frame.getWidth(), height = frame.getHeight();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        int[] pixels = new int[width * height];
        int start = buffer.position();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = start + y * rowStride + x * pixelStride;
                int red = buffer.get(offset) & 0xff;
                int green = buffer.get(offset + 1) & 0xff;
                int blue = buffer.get(offset + 2) & 0xff;
                int alpha = buffer.get(offset + 3) & 0xff;
                pixels[y * width + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        raw.setPixels(pixels, 0, width, 0, 0, width, height);
        int rotation = frame.getImageInfo().getRotationDegrees();
        if (rotation == 0) return raw;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap upright = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, true);
        if (upright != raw) raw.recycle();
        return upright;
    }

    private Ratios measureFace(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> points,
                               int imageWidth, int imageHeight) {
        if (points.size() < 264) return null;
        // Rotate landmark coordinates to the eye line, then measure horizontal widths
        // in forehead, cheek and jaw bands. This avoids unreliable fixed-index pairs.
        double eyeDx = (x(points, 263) - x(points, 33)) * imageWidth;
        double eyeDy = (y(points, 263) - y(points, 33)) * imageHeight;
        double angle = Math.atan2(eyeDy, eyeDx);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double[] px = new double[points.size()], py = new double[points.size()];
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            double originalX = x(points, i) * imageWidth;
            double originalY = y(points, i) * imageHeight;
            px[i] = originalX * cos + originalY * sin;
            py[i] = -originalX * sin + originalY * cos;
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
        }
        double faceHeight = maxY - minY;
        double foreheadWidth = widthAtBand(px, py, minY, faceHeight, 0.25);
        double cheekWidth = widthAtBand(px, py, minY, faceHeight, 0.53);
        double jawWidth = widthAtBand(px, py, minY, faceHeight, 0.80);
        if (faceHeight <= 0 || foreheadWidth <= 1 || cheekWidth <= 1 || jawWidth <= 1) return null;
        double aspect = faceHeight / cheekWidth;
        double foreheadCheek = foreheadWidth / cheekWidth;
        double jawCheek = jawWidth / cheekWidth;
        double foreheadJaw = foreheadWidth / jawWidth;
        if (aspect <= 0.5 || aspect > 3.0 || foreheadCheek <= 0.3 || foreheadCheek > 1.5
                || jawCheek <= 0.3 || jawCheek > 1.5 || foreheadJaw <= 0.3 || foreheadJaw > 2.5) return null;
        return new Ratios(aspect, foreheadCheek, jawCheek, foreheadJaw);
    }

    private static double widthAtBand(double[] px, double[] py, double minY, double faceHeight, double fraction) {
        double[] bounds = bandBounds(px, py, minY, faceHeight, fraction, 0.055);
        if (bounds[1] - bounds[0] <= 1) bounds = bandBounds(px, py, minY, faceHeight, fraction, 0.09);
        return bounds[1] - bounds[0];
    }

    private static double[] bandBounds(double[] px, double[] py, double minY, double faceHeight, double fraction, double halfBand) {
        double low = minY + (fraction - halfBand) * faceHeight;
        double high = minY + (fraction + halfBand) * faceHeight;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < py.length; i++) {
            if (py[i] >= low && py[i] <= high) { minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]); }
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)) return new double[]{0, 0};
        return new double[]{minX, maxX};
    }

    /** Returns 0 when centered and a usable size; 1 when off-center; 2 when too small; 3 when too large. */
    private int faceGuideResult(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> points,
                                int imageWidth, int imageHeight) {
        int viewWidth = previewView.getWidth(), viewHeight = previewView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return 1;
        double scale = Math.max((double) viewWidth / imageWidth, (double) viewHeight / imageHeight);
        double offsetX = (viewWidth - imageWidth * scale) / 2.0;
        double offsetY = (viewHeight - imageHeight * scale) / 2.0;
        double centerX = viewWidth * 0.5, centerY = viewHeight * 0.49;
        double radiusX = viewWidth * 0.42, radiusY = Math.min(radiusX * 1.5, viewHeight * 0.30);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (com.google.mediapipe.tasks.components.containers.NormalizedLandmark point : points) {
            double viewX = point.x() * imageWidth * scale + offsetX;
            double viewY = point.y() * imageHeight * scale + offsetY;
            minX = Math.min(minX, viewX); maxX = Math.max(maxX, viewX);
            minY = Math.min(minY, viewY); maxY = Math.max(maxY, viewY);
        }
        double faceWidth = maxX - minX, faceHeight = maxY - minY;
        // MediaPipe's mesh includes forehead and jaw contour points. Testing every
        // point against the oval curve rejects normal faces near the oval's narrow ends.
        // Use the mesh bounding-box center and dimensions instead, with a small visual margin.
        double normalizedCenterX = (0.5 * (minX + maxX) - centerX) / radiusX;
        double normalizedCenterY = (0.5 * (minY + maxY) - centerY) / radiusY;
        if (normalizedCenterX * normalizedCenterX + normalizedCenterY * normalizedCenterY > 0.45) return 1;
        if (faceWidth < radiusX * 0.34 || faceHeight < radiusY * 0.34) return 2;
        if (faceWidth > radiusX * 2.10 || faceHeight > radiusY * 2.10) return 3;
        return 0;
    }

    private void finishScan(Ratios ratios) {
        if (cameraProvider != null) cameraProvider.unbindAll();
        status.setText("Đã nhận diện khuôn mặt. Đang mở phần tư vấn…");
        Intent result = new Intent()
                .putExtra(EXTRA_FACE_ASPECT, ratios.aspect)
                .putExtra(EXTRA_FOREHEAD_CHEEK, ratios.foreheadToCheek)
                .putExtra(EXTRA_JAW_CHEEK, ratios.jawToCheek)
                .putExtra(EXTRA_FOREHEAD_JAW, ratios.foreheadToJaw);
        setResult(RESULT_OK, result);
        finish();
    }

    private static float x(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> points, int i) { return points.get(i).x(); }
    private static float y(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> points, int i) { return points.get(i).y(); }

    private void showFailure(String message) {
        if (status != null) status.setText(message);
        else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            FaceLandmarker task = landmarker;
            scanExecutor.execute(() -> { if (task != null) task.close(); });
            scanExecutor.shutdown();
        } else if (landmarker != null) {
            landmarker.close();
        }
        super.onDestroy();
    }

    private static final class Ratios {
        final double aspect, foreheadToCheek, jawToCheek, foreheadToJaw;
        Ratios(double aspect, double foreheadToCheek, double jawToCheek, double foreheadToJaw) {
            this.aspect = aspect; this.foreheadToCheek = foreheadToCheek;
            this.jawToCheek = jawToCheek; this.foreheadToJaw = foreheadToJaw;
        }
    }

    /** A compact face-shaped guide with proportions based on face height, not screen height. */
    private static final class OvalGuideView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        OvalGuideView(android.content.Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(Color.WHITE);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.49f;
            float rx = getWidth() * 0.42f;
            float ry = Math.min(rx * 1.5f, getHeight() * 0.30f);
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint);
        }
    }
}
