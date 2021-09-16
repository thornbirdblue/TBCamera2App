/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thornbird.tbcamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;


/**
 * A minimum camera app.
 * To keep it simple: portrait mode only.
 */
public class TBCameraActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "TBCamera_UI";

    private static final boolean START_WITH_FRONT_CAMERA = false;    

    private static final int PERMISSIONS_REQUEST_CAMERA = 1;
    private boolean mPermissionCheckActive = false;

    private SurfaceView mPreviewView;
    private SurfaceHolder mPreviewHolder;

    private Handler mMainHandler;
    private CameraInterface mCamera;

    // Used for saving JPEGs.
    private HandlerThread mUtilityThread;
    private Handler mUtilityHandler;

    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate");

	CameraTimer.t0 = SystemClock.elapsedRealtime();

        if (checkPermissions()) {
            // Go speed racer.
            openCamera(START_WITH_FRONT_CAMERA);
        }

        // Initialize UI.
        setContentView(R.layout.activity_main);
        mPreviewView = (SurfaceView) findViewById(R.id.preview_view);
        mPreviewHolder = mPreviewView.getHolder();
        mPreviewHolder.addCallback(this);

        mMainHandler = new Handler(this.getApplicationContext().getMainLooper());

        // General utility thread for e.g. saving JPEGs.
        mUtilityThread = new HandlerThread("UtilityThread");
        mUtilityThread.start();
        mUtilityHandler = new Handler(mUtilityThread.getLooper());

        // --- PRINT REPORT ---
        super.onCreate(savedInstanceState);
    }

    // Open camera. No UI required.
    private void openCamera(boolean frontCamera) {
        // Close previous camera if required.
        if (mCamera != null) {
            mCamera.closeCamera();
        }
        // --- SET UP CAMERA ---
        mCamera = new Api2Camera(this, frontCamera);
        //mCamera.setCallback(this);
        mCamera.openCamera();
    }

    // Initialize camera related UI and start camera; call openCamera first.
    private void startCamera() {

            mCamera.startPreview(mPreviewHolder.getSurface());
    }

    boolean mPreviewSurfaceValid = false;

    @Override
    public synchronized void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v(TAG, String.format("surfaceChanged: format=%x w=%d h=%d", format, width, height));
        if (checkPermissions()) {
            mPreviewSurfaceValid = true;
            mCamera.startPreview(mPreviewHolder.getSurface());
        }
    }


    @Override
    public void onStart() {
        Log.v(TAG, "onStart");
        super.onStart();
        // Leave screen on.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!checkPermissions()) return;

        // Can start camera now that we have the above initialized.
        if (mCamera == null) {
            openCamera(START_WITH_FRONT_CAMERA);
        }
        startCamera();
    }

    private boolean checkPermissions() {
        if (mPermissionCheckActive) return false;

        // Check for all runtime permissions
        if ((checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED )
            || (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            || (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)) {
            Log.i(TAG, "Requested camera/video permissions");
            requestPermissions(new String[] {
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_CAMERA);
            mPermissionCheckActive = true;
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        mPermissionCheckActive = false;
        if (requestCode == PERMISSIONS_REQUEST_CAMERA) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Log.i(TAG, "At least one permission denied, can't continue: " + permissions[i]);
                    finish();
                    return;
                }
            }

            Log.i(TAG, "All permissions granted");
            openCamera(START_WITH_FRONT_CAMERA);
            startCamera();
        }
    }

    @Override
    public void onStop() {
        Log.v(TAG, "onStop");
        if (mCamera != null) {
            mCamera.closeCamera();
            mCamera = null;
        }

        super.onStop();
    }


    long mJpegMillis = 0;

    public void jpegAvailable(final byte[] jpegData, final int x, final int y) {
        Log.v(TAG, "JPEG returned, size = " + jpegData.length);
        long now = SystemClock.elapsedRealtime();
        final long dt = mJpegMillis > 0 ? now - mJpegMillis : 0;
        mJpegMillis = now;

    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v(TAG, "surfaceDestroyed");
    }

}
