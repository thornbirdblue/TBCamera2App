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

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.InputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaActionSound;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.media.Image.Plane;

import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.lang.IndexOutOfBoundsException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.microedition.khronos.opengles.GL10;


/**
 * Api2Camera : a camera2 implementation
 *
 * The goal here is to make the simplest possible API2 camera,
 * where individual streams and capture options (e.g. edge enhancement,
 * noise reduction, face detection) can be toggled on and off.
 *
 */

public class Api2Camera implements CameraInterface {
    private static final String TAG = "TBCamera_API2";

    // ImageReader/Writer buffer sizes.
    private static final int IMAGEWRITER_SIZE = 2;

    private CameraInfoCache mCameraInfoCache;
    private CameraManager mCameraManager;
    private CameraCaptureSession mCurrentCaptureSession;
    private MediaActionSound mMediaActionSound = new MediaActionSound();

    //MyCameraCallback mMyCameraCallback;

    // Generally everything running on this thread & this module is *not thread safe*.
    private HandlerThread mOpsThread;
    private Handler mOpsHandler;
    private HandlerThread mInitThread;
    private Handler mInitHandler;
    private HandlerThread mJpegListenerThread;
    private Handler mJpegListenerHandler;

    Context mContext;
    boolean mCameraIsFront;

    private boolean mFirstFrameArrived;

    private ImageReader mJpegImageReader;

    // Starting the preview requires each of these 3 to be true/non-null:
    volatile private Surface mPreviewSurface;
    volatile private CameraDevice mCameraDevice;
    volatile boolean mAllThingsInitialized = false;

    /**
     * Constructor.
     */
    public Api2Camera(Context context, boolean useFrontCamera) {
        mContext = context;
        mCameraIsFront = useFrontCamera;
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        mCameraInfoCache = new CameraInfoCache(mCameraManager, useFrontCamera);

        // Create thread and handler for camera operations.
        mOpsThread = new HandlerThread("CameraOpsThread");
        mOpsThread.start();
        mOpsHandler = new Handler(mOpsThread.getLooper());

        // Create thread and handler for slow initialization operations.
        // Don't want to use camera operations thread because we want to time camera open carefully.
        mInitThread = new HandlerThread("CameraInitThread");
        mInitThread.start();
        mInitHandler = new Handler(mInitThread.getLooper());
        mInitHandler.post(new Runnable() {
            @Override
            public void run() {
                InitializeAllTheThings();
                mAllThingsInitialized = true;
                Log.v(TAG, "STARTUP_REQUIREMENT ImageReader initialization done.");
                tryToStartCaptureSession();
            }
        });

    }

    // Ugh, why is this stuff so slow?
    private void InitializeAllTheThings() {

        // Thread to handle returned JPEGs.
        mJpegListenerThread = new HandlerThread("CameraJpegThread");
        mJpegListenerThread.start();
        mJpegListenerHandler = new Handler(mJpegListenerThread.getLooper());

        // Create ImageReader to receive JPEG image buffers via reprocessing.
        mJpegImageReader = ImageReader.newInstance(
                mCameraInfoCache.getYuvStream1Size().getWidth(),
                mCameraInfoCache.getYuvStream1Size().getHeight(),
                ImageFormat.JPEG,
                2);
        mJpegImageReader.setOnImageAvailableListener(mJpegImageListener, mJpegListenerHandler);


        // Load click sound.
        mMediaActionSound.load(MediaActionSound.SHUTTER_CLICK);

    }


    @Override
    public void openCamera() {
        Log.v(TAG, "Opening camera " + mCameraInfoCache.getCameraId());
        mOpsHandler.post(new Runnable() {
            @Override
            public void run() {
                CameraTimer.t_open_start = SystemClock.elapsedRealtime();
                try {
                    mCameraManager.openCamera(mCameraInfoCache.getCameraId(), mCameraStateCallback, null);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Unable to openCamera().");
                }
            }
        });
    }

    @Override
    public void closeCamera() {
        // TODO: We are stalling main thread now which is bad.
        Log.v(TAG, "Closing camera " + mCameraInfoCache.getCameraId());
        if (mCameraDevice != null) {
            try {
                mCurrentCaptureSession.abortCaptures();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Could not abortCaptures().");
            }
            mCameraDevice.close();
        }
        mCurrentCaptureSession = null;
        Log.v(TAG, "Done closing camera " + mCameraInfoCache.getCameraId());
    }

    public void startPreview(final Surface surface) {
        Log.v(TAG, "STARTUP_REQUIREMENT preview Surface ready.");
        mPreviewSurface = surface;
        tryToStartCaptureSession();
    }

    private CameraDevice.StateCallback mCameraStateCallback = new LoggingCallbacks.DeviceStateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            CameraTimer.t_open_end = SystemClock.elapsedRealtime();
            mCameraDevice = camera;
            Log.v(TAG, "STARTUP_REQUIREMENT Done opening camera " + mCameraInfoCache.getCameraId() +
                    ". HAL open took: (" + (CameraTimer.t_open_end - CameraTimer.t_open_start) + " ms)");

            super.onOpened(camera);
            tryToStartCaptureSession();
        }
    };

    private void tryToStartCaptureSession() {
        if (mCameraDevice != null && mAllThingsInitialized && mPreviewSurface != null) {
            mOpsHandler.post(new Runnable() {
                @Override
                public void run() {
                    // It used to be: this needed to be posted on a Handler.
                    startCaptureSession();
                }
            });
        }
    }

    // Create CameraCaptureSession. Callback will start repeating request with current parameters.
    private void startCaptureSession() {
        CameraTimer.t_session_go = SystemClock.elapsedRealtime();

        Log.v(TAG, "Configuring session..");
        List<Surface> outputSurfaces = new ArrayList<Surface>(4);

        outputSurfaces.add(mPreviewSurface);
        Log.v(TAG, "  .. added SurfaceView " + mCameraInfoCache.getPreviewSize().getWidth() +
                " x " + mCameraInfoCache.getPreviewSize().getHeight());

        try {
                mCameraDevice.createCaptureSession(outputSurfaces, mSessionStateCallback, null);
                Log.v(TAG, "  Call to createCaptureSession complete.");
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error configuring ISP.");
        }
    }

    ImageWriter mImageWriter;

    private CameraCaptureSession.StateCallback mSessionStateCallback = new LoggingCallbacks.SessionStateCallback() {
        @Override
        public void onReady(CameraCaptureSession session) {
            Log.v(TAG, "capture session onReady().  HAL capture session took: (" + (SystemClock.elapsedRealtime() - CameraTimer.t_session_go) + " ms)");
            mCurrentCaptureSession = session;
            issuePreviewCaptureRequest(false);

            if (session.isReprocessable()) {
                mImageWriter = ImageWriter.newInstance(session.getInputSurface(), IMAGEWRITER_SIZE);
                mImageWriter.setOnImageReleasedListener(
                        new ImageWriter.OnImageReleasedListener() {
                            @Override
                            public void onImageReleased(ImageWriter writer) {
                                Log.v(TAG, "ImageWriter.OnImageReleasedListener onImageReleased()");
                            }
                        }, null);
                Log.v(TAG, "Created ImageWriter.");
            }
            super.onReady(session);
        }
    };


    public void issuePreviewCaptureRequest(boolean AFtrigger) {
        CameraTimer.t_burst = SystemClock.elapsedRealtime();
        Log.v(TAG, "issuePreviewCaptureRequest...");
        try {
            CaptureRequest.Builder b1 = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b1.addTarget(mPreviewSurface);

            mCurrentCaptureSession.setRepeatingRequest(b1.build(), mCaptureCallback, mOpsHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not access camera for issuePreviewCaptureRequest.");
        }
    }


    /*********************************
     * onImageAvailable() processing *
     *********************************/
    ImageReader.OnImageAvailableListener mJpegImageListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image img = reader.acquireLatestImage();
                    if (img == null) {
                        Log.e(TAG, "Null image returned JPEG");
                        return;
                    }
                    Image.Plane plane0 = img.getPlanes()[0];
                    final ByteBuffer buffer = plane0.getBuffer();
                    Log.v(TAG, String.format("JPEG buffer available, w=%d h=%d time=%d size=%d ",
                            img.getWidth(), img.getHeight(), img.getTimestamp(), buffer.capacity()));
                    // Save JPEG on the utility thread,
                    final byte[] jpegBuf;
                    if (buffer.hasArray()) {
                        jpegBuf = buffer.array();
                    } else {
                        jpegBuf = new byte[buffer.capacity()];
                        buffer.get(jpegBuf);
                    }
                    //mMyCameraCallback.jpegAvailable(jpegBuf, img.getWidth(), img.getHeight());
                    img.close();

                    // take (reprocess) another picture right away if bursting.
                }
            };

    /*************************************
     * CaptureResult metadata processing *
     *************************************/

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new LoggingCallbacks.SessionCaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            if (!mFirstFrameArrived) {
                mFirstFrameArrived = true;
                long now = SystemClock.elapsedRealtime();
                long dt = now - CameraTimer.t0;
                long camera_dt = now - CameraTimer.t_session_go + CameraTimer.t_open_end - CameraTimer.t_open_start;
                long repeating_req_dt = now - CameraTimer.t_burst;
                Log.v(TAG, "App control to first frame: (" + dt + " ms)");
                Log.v(TAG, "HAL request to first frame: (" + repeating_req_dt + " ms) " + " Total HAL wait: (" + camera_dt + " ms)");
                //mMyCameraCallback.receivedFirstFrame();
                //mMyCameraCallback.performanceDataAvailable((int) dt, (int) camera_dt, null);
            }
            // Used for reprocessing.
            super.onCaptureCompleted(session, request, result);
        }
    };


}
