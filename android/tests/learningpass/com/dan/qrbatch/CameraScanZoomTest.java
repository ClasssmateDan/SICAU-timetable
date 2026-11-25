package com.dan.qrbatch;

import android.Manifest;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.time.Duration;
import java.util.Locale;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowSurfaceView;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Exercises CameraScanActivity with a simulated preview and a synthetic QR code. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = CameraScanZoomTest.PreviewSurfaceShadow.class)
@SuppressWarnings("deprecation")
public class CameraScanZoomTest {
    private ActivityController<CameraScanActivity> controller;
    private Camera camera;
    private SeekBar slider;
    private TextView ratio;

    @Implements(SurfaceView.class)
    public static class PreviewSurfaceShadow extends ShadowSurfaceView {
        private final FakeSurfaceHolder holder = new FakeSurfaceHolder() {
            private final Surface surface = new Surface(new SurfaceTexture(0));
            @Override public Surface getSurface() { return surface; }
        };

        @Implementation @Override protected SurfaceHolder getHolder() { return holder; }
    }

    private void openScanner(boolean supportsZoom, String ratios) {
        ShadowCamera.clearCameraInfo();
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        ShadowCamera.addCameraInfo(0, info);
        Camera seed = Camera.open(0);
        Camera.Parameters parameters = seed.getParameters();
        parameters.set("zoom-supported", Boolean.toString(supportsZoom));
        parameters.set("max-zoom", 2);
        parameters.set("zoom-ratios", ratios);
        parameters.setZoom(0);
        seed.setParameters(parameters);
        seed.release();

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        controller = Robolectric.buildActivity(CameraScanActivity.class).setup();
        CameraScanActivity activity = controller.get();
        SurfaceView preview = ReflectionHelpers.getField(activity, "preview");
        activity.surfaceCreated(preview.getHolder());
        camera = ReflectionHelpers.getField(activity, "camera");
        slider = ReflectionHelpers.getField(activity, "zoomSlider");
        ratio = ReflectionHelpers.getField(activity, "zoomRatio");
        assertNotNull(camera);
        assertTrue(shadowOf(camera).isPreviewing());
    }

    @After public void closeScanner() {
        if (controller != null) controller.pause().stop().destroy();
        ShadowCamera.clearCameraInfo();
    }

    private void selectZoom(int step) {
        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, step);
        assertTrue(slider.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments));
    }

    private void assertZoom(int step, double multiplier) {
        assertEquals(step, slider.getProgress());
        assertEquals(step, camera.getParameters().getZoom());
        assertEquals(String.format(Locale.getDefault(), "%.2f×", multiplier), ratio.getText().toString());
    }

    @Test public void sliderChangesCameraZoomAndScannerReturnsQrAtMaximumZoom() throws Exception {
        openScanner(true, "100,155,240");
        assertEquals(View.VISIBLE, ((View) slider.getParent()).getVisibility());
        assertEquals(2, slider.getMax());
        assertZoom(0, 1.0);
        selectZoom(1);
        assertZoom(1, 1.55);
        selectZoom(0);
        assertZoom(0, 1.0);
        selectZoom(2);
        assertZoom(2, 2.40);

        String payload = "synthetic-zoom-test";
        Camera.Size size = camera.getParameters().getPreviewSize();
        BitMatrix qr = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size.width, size.height);
        byte[] frame = new byte[size.width * size.height * 3 / 2];
        for (int y = 0; y < size.height; y++) {
            for (int x = 0; x < size.width; x++) {
                frame[y * size.width + x] = (byte) (qr.get(x, y) ? 0 : 255);
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        shadowOf(camera).invokePreviewCallback(frame);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!controller.get().isFinishing() && System.nanoTime() < deadline) {
            Thread.sleep(5);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).getResultCode());
        assertEquals(payload, shadowOf(controller.get()).getResultIntent().getStringExtra("qr_text"));
    }

    @Test public void unsupportedCameraKeepsPreviewAndHidesZoom() {
        openScanner(false, "100,155,240");
        assertEquals(View.GONE, ((View) slider.getParent()).getVisibility());
    }

    @Test public void invalidZoomRatiosKeepPreviewAndHideZoom() {
        openScanner(true, "100,155");
        assertEquals(View.GONE, ((View) slider.getParent()).getVisibility());
    }

    @Test public void pausingHidesZoomAndReopeningTheCameraRestoresControls() {
        openScanner(true, "100,155,240");
        controller.pause();
        assertTrue(shadowOf(camera).isReleased());
        assertEquals(View.GONE, ((View) slider.getParent()).getVisibility());
        controller.resume();
        assertEquals(View.VISIBLE, ((View) slider.getParent()).getVisibility());
    }
}
