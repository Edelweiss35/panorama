package com.google.android.apps.lightcycle.camera;

import java.util.Iterator;
import java.util.List;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.util.Log;

import com.google.android.apps.lightcycle.camera.CameraApiProxy.CameraProxy;
import com.google.android.apps.lightcycle.panorama.DeviceManager;
import com.google.android.apps.lightcycle.util.LG;
import com.google.android.apps.lightcycle.util.Size;

public class CameraUtility {

	private static final String TAG = CameraUtility.class.getSimpleName();
	private final float fieldOfView;
	private boolean hasBackFacingCamera = false;
	private Camera.Size photoSize;
	private final Size previewSize;
	
	public CameraUtility(int width, int height) {
		CameraApiProxy.CameraProxy cameraProxy = CameraApiProxy.instance().openBackCamera();
		if (cameraProxy == null) {
			hasBackFacingCamera = false;
			previewSize = new Size(0, 0);
			fieldOfView = 0.0f;
			return;
		}
		
		hasBackFacingCamera = true;
		Camera.Size size = getClosetPreviewSize(cameraProxy, width, height);
		previewSize = new Size(size.width, size.height);
		fieldOfView = DeviceManager.getCameraFieldOfViewDegrees(cameraProxy
				.getParameters().getHorizontalViewAngle());
		cameraProxy.release();
	}

	private android.hardware.Camera.Size getClosetPreviewSize(
			CameraProxy cameraProxy, int width, int height) {
		List<Camera.Size> sizes = cameraProxy.getParameters().getSupportedPictureSizes();
		int i = width * height;
		int j = Integer.MAX_VALUE;
		
		Camera.Size cameraSize = sizes.get(0);
		Iterator<Camera.Size> iterator = sizes.iterator();  
		while (iterator.hasNext()) {
			Camera.Size size = iterator.next();
			int k = Math.abs(size.width * size.height - i);
			if (k >= j) {
				continue;
			}
			
			j = k;
			
			cameraSize = size;
		}
		 
		return cameraSize;
	}

	public void allocateBuffers(CameraProxy cameraProxy, Size size, int count,
			PreviewCallback callback) {
		cameraProxy.setPreviewCallbackWithBuffer(null);
		int i = (int) Math.ceil(ImageFormat.getBitsPerPixel(cameraProxy
				.getParameters().getPreviewFormat())
				/ 8.0F
				* (size.width * size.height));
		for (int j = 0; j < count; j++) {
			cameraProxy.addCallbackBuffer(new byte[i]);
		}
		
		cameraProxy.setPreviewCallbackWithBuffer(callback);
	}
	
	public float getFieldOfView() {
		return fieldOfView;
	}
	
	public String getFlashMode(CameraProxy cameraProxy) {
		List<String> modes = cameraProxy.getParameters().getSupportedFlashModes();
		if (modes != null && modes.contains("off")) {
			return "off";
		}
		
		return "auto";
	}
	
	public String getFocusMode(CameraProxy cameraProxy) {
		List<String> modes = cameraProxy.getParameters().getSupportedFocusModes();
		if (modes != null) {
			if (modes.contains("infinity")) {
				return "infinity";
			}
			
			if (modes.contains("fixed")) {
				return "fixed";
			}
		}
		
		return "auto";
	}
	
	public Camera.Size getPhotoSize() {
		return photoSize;
	}

	public Size getPreviewSize() {
		return previewSize;
	}

	public boolean hasBackFacingCamera() {
		return hasBackFacingCamera;
	}
	
	public void setFrameRate(Camera.Parameters params) {
		// V7: 0
		// V6: 1
		List<int[]> fpsRanges = params.getSupportedPreviewFpsRange();
		if (fpsRanges.size() == 0) {
			LG.d("No suppoted frame rates returned!");
			return;
		}
		
//		:array_0
//	    .array-data 0x4
//	        0xfft 0xfft 0xfft 0xfft
//	        0xfft 0xfft 0xfft 0xfft
//	    .end array-data
		int[] range = new int[] { -1, -1 };
		
		Iterator<int[]> iterator = fpsRanges.iterator();
		while (iterator.hasNext()) {
			int[] fpsRange = iterator.next();
			
			if (fpsRange[1] <= range[1] || fpsRange[1] > 0x9c40) {
				// :cond_3
				if (fpsRange[1] == range[1]) {
					if (fpsRange[0] > range[0]) {
						range = fpsRange;
					}
				}
			} else {
				range = fpsRange;
			}
			
			// :cond_2
			// :goto_2
			LG.d("Available rates : " + fpsRange[0] + " to " + fpsRange[1]);
		}
		
		// :cond_4
		if (range[0] > 0) {
			LG.d("Setting frame rate : " + range[0] + " to " + range[1]);
			params.setPreviewFpsRange(range[0], range[1]);
		}
	}
	
	public void setPictureSize(Camera.Parameters params, int base) {
		List<Camera.Size> sizes = params.getSupportedPictureSizes();
		
		int index = 0;
		int lastIndex = 0;
		int curWidth = 1000000000;
		
		while (index < sizes.size()) {
			Camera.Size cameraSize = sizes.get(index);
			int w = Math.abs(cameraSize.width - base);
			if (w < curWidth) {
				curWidth = w;
				lastIndex = index;
			}
			++index;
		}
		
		photoSize = sizes.get(lastIndex);
		params.setPictureSize(photoSize.width, photoSize.height);
		Log.e(TAG, "Photo size: " + photoSize.width + ", " + photoSize.height);
	}
	
}
