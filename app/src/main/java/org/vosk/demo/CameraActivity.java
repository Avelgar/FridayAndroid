package org.vosk.demo;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";
    private static final int PERMISSIONS_REQUEST_CAMERA = 100;

    private TextureView textureViewTop;
    private TextureView textureViewBottom;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private Handler backgroundHandler;
    private HandlerThread handlerThread;
    private String cameraId;
    private boolean isCameraActive = false;

    private BroadcastReceiver closeCameraReceiver;
    private void setupCornerRadius(float topRadius, float bottomRadius) {
        RoundedCornerHelper.setTopCornerRadius(topRadius, this);
        RoundedCornerHelper.setBottomCornerRadius(bottomRadius, this);

        // Применяем новые радиусы к View
        FrameLayout topFrame = (FrameLayout) findViewById(R.id.texture_view_top).getParent();
        FrameLayout bottomFrame = (FrameLayout) findViewById(R.id.texture_view_bottom).getParent();

        RoundedCornerHelper.applyRoundedCorners(topFrame, true);
        RoundedCornerHelper.applyRoundedCorners(bottomFrame, false);
    }

    public void changeCornerRadius(float topRadius, float bottomRadius) {
        setupCornerRadius(topRadius, bottomRadius);
    }

    // Метод для изменения отступов во время работы
    public void changeMargins(int left, int top, int right, int bottom) {
        setupMargins(left, top, right, bottom);
    }

    private void setupMargins(int leftMargin, int topMargin, int rightMargin, int bottomMargin) {
        FrameLayout topFrame = (FrameLayout) findViewById(R.id.texture_view_top).getParent();
        FrameLayout bottomFrame = (FrameLayout) findViewById(R.id.texture_view_bottom).getParent();

        // Устанавливаем отступы для верхнего FrameLayout
        LinearLayout.LayoutParams topParams = (LinearLayout.LayoutParams) topFrame.getLayoutParams();
        topParams.setMargins(leftMargin, topMargin, rightMargin, bottomMargin / 2); // Делим нижний отступ пополам

        // Устанавливаем отступы для нижнего FrameLayout
        LinearLayout.LayoutParams bottomParams = (LinearLayout.LayoutParams) bottomFrame.getLayoutParams();
        bottomParams.setMargins(leftMargin, topMargin / 2, rightMargin, bottomMargin); // Делим верхний отступ пополам

        topFrame.setLayoutParams(topParams);
        bottomFrame.setLayoutParams(bottomParams);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Установка полноэкранного режима
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_camera);

        // Настройка радиуса скругления (значения в dp)
        setupCornerRadius(20f, 20f); // 20dp для верхних и нижних углов

        // Настройка отступов (значения в px, можно конвертировать из dp)
        int margin8dp = (int) (8 * getResources().getDisplayMetrics().density);
        int margin4dp = (int) (4 * getResources().getDisplayMetrics().density);
        setupMargins(margin8dp, margin8dp, margin8dp, margin8dp);

        // Инициализация BroadcastReceiver для закрытия камеры
        closeCameraReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("CLOSE_CAMERA_ACTION".equals(intent.getAction())) {
                    finish();
                }
            }
        };

        IntentFilter filter = new IntentFilter("CLOSE_CAMERA_ACTION");
        registerReceiver(closeCameraReceiver, filter);

        // Находим TextureView из макета
        textureViewTop = findViewById(R.id.texture_view_top);
        textureViewBottom = findViewById(R.id.texture_view_bottom);

        // Проверка разрешений
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    PERMISSIONS_REQUEST_CAMERA);
        } else {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (closeCameraReceiver != null) {
            unregisterReceiver(closeCameraReceiver);
        }
        closeCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Для работы камеры需要 разрешение", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        // Запускаем камеру когда TextureView готов
        textureViewTop.setSurfaceTextureListener(textureListener);
        textureViewBottom.setSurfaceTextureListener(textureListener);
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (!isCameraActive) {
                openCamera();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0]; // Используем заднюю камеру

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, stateCallback, null);
            isCameraActive = true;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access the camera", e);
            Toast.makeText(this, "Cannot access the camera", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            Toast.makeText(this, "Error opening camera", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    private void createCameraPreview() {
        try {
            SurfaceTexture textureTop = textureViewTop.getSurfaceTexture();
            SurfaceTexture textureBottom = textureViewBottom.getSurfaceTexture();

            // Устанавливаем размер превью
            Size previewSize = getPreviewSize();
            textureTop.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            textureBottom.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            Surface surfaceTop = new Surface(textureTop);
            Surface surfaceBottom = new Surface(textureBottom);

            final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surfaceTop);
            captureRequestBuilder.addTarget(surfaceBottom);

            cameraDevice.createCaptureSession(Arrays.asList(surfaceTop, surfaceBottom),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;

                            cameraCaptureSession = session;
                            try {
                                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Camera access exception", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(CameraActivity.this,
                                    "Configuration failed", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
        }
    }

    private Size getPreviewSize() {
        // Для простоты возвращаем размер TextureView
        return new Size(textureViewTop.getWidth(), textureViewTop.getHeight());
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        isCameraActive = false;
    }

    private void startBackgroundThread() {
        handlerThread = new HandlerThread("CameraBackground");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
            try {
                handlerThread.join();
                handlerThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Background thread interruption", e);
            }
        }
    }
}