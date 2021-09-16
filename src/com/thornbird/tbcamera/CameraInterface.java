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

import android.util.Size;
import android.view.Surface;

/**
 * This is a simple camera interface not specific to API1 or API2.
 */
public interface CameraInterface {
    /**
     * Open the camera. Call startPreview() to actually see something.
     */
    void openCamera();

    /**
     * Start preview to a surface. Also need to call openCamera().
     * @param surface
     */
    void startPreview(Surface surface);

    /**
     * Close the camera.
     */
    void closeCamera();

    /**
     * Take a picture and return data with provided callback.
     * Preview must be started.
     */
//    void takePicture();

}
