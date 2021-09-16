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

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;

/**
 * Caches (static) information about the first/main camera.
 * Convenience functions represent data from CameraCharacteristics.
 */

public class CameraInfoCache {
    private static final String TAG = "TBCamera_CAMINFO";

    private CameraCharacteristics mCameraCharacteristics;
    private String mCameraId;
    private Size mLargestYuvSize;
    private Size mLargestJpegSize;
    private int mHardwareLevel;

    /**
     * Constructor.
     */
    public CameraInfoCache(CameraManager cameraMgr, boolean useFrontCamera) {
        String[] cameralist;
        try {
            cameralist = cameraMgr.getCameraIdList();
            for (String id : cameralist) {
                mCameraCharacteristics = cameraMgr.getCameraCharacteristics(id);
                Integer facing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == (useFrontCamera ? CameraMetadata.LENS_FACING_FRONT : CameraMetadata.LENS_FACING_BACK)) {
                    mCameraId = id;
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ERROR: Could not get camera ID list / no camera information is available: " + e);
            return;
        }
        // Should have mCameraId as this point.
        if (mCameraId == null) {
            Log.e(TAG, "ERROR: Could not find a suitable rear or front camera.");
            return;
        }

        // Store YUV_420_888, JPEG, Raw info
        StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int[] formats = map.getOutputFormats();
        long lowestStall = Long.MAX_VALUE;
        for (int i = 0; i < formats.length; i++) {
            if (formats[i] == ImageFormat.YUV_420_888) {
                mLargestYuvSize = returnLargestSize(map.getOutputSizes(formats[i]));
            }


            if (formats[i] == ImageFormat.JPEG) {
                mLargestJpegSize = returnLargestSize(map.getOutputSizes(formats[i]));
            }
        }

        // Misc stuff.
        mHardwareLevel = mCameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

    }


    public boolean isHardwareLevelAtLeast(int level) {
        // Special-case LEGACY since it has numerical value 2
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            // All devices are at least LEGACY
            return true;
        }
        if (mHardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            // Since level isn't LEGACY
            return false;
        }
        // All other levels can be compared numerically
        return mHardwareLevel >= level;
    }


    /**
     * Private utility function.
     */
    private Size returnLargestSize(Size[] sizes) {
        Size largestSize = null;
        int area = 0;
        for (int j = 0; j < sizes.length; j++) {
            if (sizes[j].getHeight() * sizes[j].getWidth() > area) {
                area = sizes[j].getHeight() * sizes[j].getWidth();
                largestSize = sizes[j];
            }
        }
        return largestSize;
    }



    public String getCameraId() {
        return mCameraId;
    }

    public Size getPreviewSize() {
        if (isHardwareLevelAtLeast(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3)) {
            // Bigger preview size for more advanced devices
            return new Size(1440, 1080);
        }
        return new Size(1280, 960); // TODO: Check available resolutions.
    }

	
    public Size getYuvStream1Size() {
        return mLargestYuvSize;
    }

}
