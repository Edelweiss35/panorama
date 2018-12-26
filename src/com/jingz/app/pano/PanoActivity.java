package com.jingz.app.pano;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.google.android.apps.lightcycle.LightCycleApp;
import com.google.android.apps.lightcycle.PanoramaViewActivity;
import com.google.android.apps.lightcycle.camera.CameraApiProxy;
import com.google.android.apps.lightcycle.camera.CameraApiProxyAndroidImpl;
import com.google.android.apps.lightcycle.camera.CameraPreview;
import com.google.android.apps.lightcycle.camera.CameraUtility;
import com.google.android.apps.lightcycle.camera.NullSurfaceCameraPreview;
import com.google.android.apps.lightcycle.camera.TextureCameraPreview;
import com.google.android.apps.lightcycle.glass.FullScreenProgressNotification;
import com.google.android.apps.lightcycle.panorama.DeviceManager;
import com.google.android.apps.lightcycle.panorama.IncrementalAligner;
import com.google.android.apps.lightcycle.panorama.LightCycleNative;
import com.google.android.apps.lightcycle.panorama.LightCycleRenderer;
import com.google.android.apps.lightcycle.panorama.LightCycleView;
import com.google.android.apps.lightcycle.panorama.MessageSender.MessageSubscriber;
import com.google.android.apps.lightcycle.panorama.RenderedGui;
import com.google.android.apps.lightcycle.panorama.StitchingServiceManager;
import com.google.android.apps.lightcycle.panorama.StitchingServiceManager.StitchingResultCallback;
import com.google.android.apps.lightcycle.sensor.SensorReader;
import com.google.android.apps.lightcycle.storage.LocalSessionStorage;
import com.google.android.apps.lightcycle.storage.StorageManager;
import com.google.android.apps.lightcycle.storage.StorageManagerFactory;
import com.google.android.apps.lightcycle.util.AnalyticsHelper;
import com.google.android.apps.lightcycle.util.Callback;
import com.google.android.apps.lightcycle.util.LG;
import com.google.android.apps.lightcycle.util.LightCycleCaptureEventListener;
import com.google.android.apps.lightcycle.util.Size;
import com.google.android.apps.lightcycle.util.UiUtil;

public class PanoActivity extends Activity {

	private AnalyticsHelper analyticsHelper;
	
	private LightCycleCaptureEventListener captureEventListener;
	long captureStartTimeMs;
	
	// Storage
	private LocalSessionStorage localStorage;
	private StorageManager storageManager = StorageManagerFactory
			.getStorageManager();

	private SensorReader sensorReader = new SensorReader();

	private IncrementalAligner aligner;
	private RenderedGui renderedGui;
	private LightCycleView mainView = null;
	private boolean showOwnDoneButton = true;
	private boolean showOwnUndoButton = true;
	
	private PowerManager.WakeLock wakeLock = null;
	
	static {
		CameraApiProxy.setActiveProxy(new CameraApiProxyAndroidImpl());
		LightCycleApp.initLightCycleNative();
	}

	private void applyPreferences() {
		SharedPreferences sharedPrefs = PreferenceManager
				.getDefaultSharedPreferences(getBaseContext());
		boolean useFastShutter = sharedPrefs
				.getBoolean("useFastShutter", false);
		if (useFastShutter) {
			mainView.getCameraPreview().setFastShutter(useFastShutter);
		}

		sensorReader.enableEkf(sharedPrefs.getBoolean("useGyro", true));
		mainView.setLiveImageDisplay(sharedPrefs.getBoolean("displayLiveImage",
				false));
		LightCycleNative.AllowFastMotion(sharedPrefs.getBoolean(
				"allowFastMotion", false));
		boolean enabled = sharedPrefs
				.getBoolean("enableLocationProvider", true);
		mainView.setLocationProviderEnabled(enabled);
	}
	
	private void displayErrorAndExit(String error) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(error).setCancelable(false)
				.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						PanoActivity.this.finish();
					}
				});
		builder.create().show();
	}
	
	private void endCapture() {
		logEndCaptureToAnalytics();
		mainView.stopCamera();
		sensorReader.stop();
		
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
		}
	}

	private void logEndCaptureToAnalytics() {
		analyticsHelper.trackPage(AnalyticsHelper.Page.END_CAPTURE);
		analyticsHelper.trackEvent("Capture", "Session", "NumPhotos",
				mainView.getTotalPhotos());
		long time = SystemClock.uptimeMillis() - captureStartTimeMs;
		analyticsHelper.trackEvent("Capture", "Session", "CaptureTime",
				(int) (time / 1000L));
		int i = Build.VERSION.SDK_INT;
		analyticsHelper.trackEvent("Capture", "Session", "AndroidVersion",
				i);
	}
	
	private void startStitchService(LocalSessionStorage localStorage) {
		LightCycleNative.CleanUp();
		storageManager.addSessionData(localStorage);
		StitchingServiceManager stitchingServiceManager = StitchingServiceManager
				.getStitchingServiceManager(this);
		if (DeviceManager.isWingman()) {
			stitchingServiceManager
					.addStitchingResultCallback(new StitchingResultCallback() {

						@Override
						public void onResult(String filename, Uri data) {
							Intent intent = new Intent(PanoActivity.this,
									PanoramaViewActivity.class);
							intent.putExtra("filename", filename);
							PanoActivity.this.startActivity(intent);
						}
					});
		}
		
		stitchingServiceManager.newTask(localStorage);
		if (DeviceManager.isWingman()) {
			startActivity(new Intent(this, FullScreenProgressNotification.class));
		}
		
		Toast.makeText(this, "Start stitching", Toast.LENGTH_LONG).show();
		
		finish();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		analyticsHelper = AnalyticsHelper.getInstance(this);
	}
	
	public void onDoneButtonPressed(Callback<Void> stitchingStartedCallback) {
		mainView.clearRendering();
		endCapture();
		final Callback<Void> callback = stitchingStartedCallback;

		aligner.shutdown(new Callback<Void>() {

			@Override
			public void onCallback(Void arg0) {
				if (aligner.isRealtimeAlignmentEnabled()
						|| aligner.isExtractFeaturesAndThumbnailEnabled()) {
					String mosaicPath = localStorage.mosaicFilePath;
					LG.d("Creating preview stitch into file: " + mosaicPath);
					
					LightCycleNative.PreviewStitch(mosaicPath);
					MediaScannerConnection.scanFile(PanoActivity.this,
							new String[] { mosaicPath },
							new String[] { "image/jpeg" }, null);
				}
				
				runOnUiThread(new Runnable() {
					
					@Override
					public void run() {
						startStitchService(localStorage);
					}
				});
				
				if (callback != null) {
					callback.onCallback(null);
				}
			}
		});	// ~aligner.shutdown()
		
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER) && DeviceManager.isWingman()) {
			endCapture();
			startStitchService(localStorage);
			return true;
		}
		
		return super.onKeyDown(keyCode, event);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (localStorage != null) {
			storageManager.addSessionData(localStorage);
		}
		
		if (mainView != null) {
			mainView.stopCamera();
		}
		
		mainView = null;
		
		if (sensorReader != null) {
			sensorReader.stop();
		}
		
		if (aligner != null && !aligner.isInterrupted()) {
			aligner.interrupt();
		}
		
		if (wakeLock != null) {
			wakeLock.release();
		}
		
		LocalBroadcastManager
				.getInstance(this)
				.sendBroadcast(
						new Intent(
								"com.google.android.apps.lightcycle.panorama.RESUME"));
		
	}

	@Override
	protected void onResume() {
		super.onResume();
		
		UiUtil.switchSystemUiToLightsOut(getWindow());
		sensorReader.start(this);
		
		String deviceInfo = Build.MODEL + " (" + Build.MANUFACTURER + ")";
		LG.d("Model is: " + deviceInfo);
		
		if (!DeviceManager.isDeviceSupported()) {
			analyticsHelper.trackEvent("Capture", "UnsupportedDevice",
					deviceInfo, 1);
			return;
		}
		
		Process.setThreadPriority(-19);
		wakeLock = ((PowerManager) getSystemService("power")).newWakeLock(
				0x2000000a, "LightCycle");
		wakeLock.acquire();
		storageManager.init(this);
		String outputDir = getIntent().getStringExtra("output_dir");
		if (outputDir != null) {
			LG.d("Setting the panorama destination to : " + outputDir);
			if (!storageManager.setPanoramaDestination(outputDir)) {
				Log.e("LightCycle",
						"Unable to set the panorama destination directory : "
								+ outputDir);
			}
		}
		
		localStorage = storageManager.getLocalSessionStorage();
		LG.d("storage : " + localStorage.metadataFilePath + " "
				+ localStorage.mosaicFilePath + " "
				+ localStorage.orientationFilePath + " "
				+ localStorage.sessionDir + " "
				+ localStorage.sessionId + " "
				+ localStorage.thumbnailFilePath);
		
		CameraUtility cameraUtility = LightCycleApp.getCameraUtility();
		if (!cameraUtility.hasBackFacingCamera()) {
			displayErrorAndExit("Sorry, your device does not have a back facing camera");
			return;
		}
		
		LocalBroadcastManager
				.getInstance(this)
				.sendBroadcast(
						new Intent(
								"com.google.android.apps.lightcycle.panorama.PAUSE"));
		
		CameraPreview cameraPreview = null; 
		if (Build.VERSION.SDK_INT < 11) {
			cameraPreview = new NullSurfaceCameraPreview(cameraUtility);
		} else {
			cameraPreview = new TextureCameraPreview(cameraUtility);
		}
		
		renderedGui = new RenderedGui();
		renderedGui.setShowOwnDoneButton(showOwnDoneButton);
		
		renderedGui.setDoneButtonVisibilityListener(new Callback<Boolean>() {
			
			@Override
			public void onCallback(Boolean visible) {
				if (captureEventListener == null) {
					return;
				}
				
				captureEventListener.onDoneButtonVisibilityChanged(visible);
			}
		});
		
		renderedGui.setShowOwnUndoButton(showOwnUndoButton);
		renderedGui.setUndoButtonVisibilityListener(new Callback<Boolean>() {

			@Override
			public void onCallback(Boolean visible) {
				if (captureEventListener == null) {
					return;
				}
				
				captureEventListener.onUndoButtonVisibilityChanged(visible);
			}
		});
		
		renderedGui.setUndoButtonStatusListener(new Callback<Boolean>() {

			@Override
			public void onCallback(Boolean visible) {
				if (captureEventListener == null) {
					return;
				}
				
				captureEventListener.onUndoButtonStatusChanged(visible);
			}
		});
		
		boolean useRealtimeAlignment = PreferenceManager
				.getDefaultSharedPreferences(getBaseContext()).getBoolean(
						"useRealtimeAlignment", false);
		
		LightCycleRenderer renderer = null;
		try {
			renderer = new LightCycleRenderer(this, renderedGui, useRealtimeAlignment);
		} catch (Exception e) {
			Log.e("LightCycle", "Error creating PanoRenderer.", e);
		}
		
		aligner = new IncrementalAligner(useRealtimeAlignment);
		
		mainView = new LightCycleView(this, cameraPreview, sensorReader,
				localStorage, aligner, renderer);
		mainView.setZOrderOnTop(true);
		setContentView(mainView);
		
		mainView.registerMessageSink(new MessageSubscriber() {
			
			@Override
			public void message(int arg0, float arg1, String arg2) {
				if (arg0 != 1) {
					return;
				}
				
				onDoneButtonPressed(null);
			}
		});
		
		if (Build.VERSION.SDK_INT < 11) {
			
		}
		
		Size size = cameraPreview.initCamera(mainView.getPreviewCallback(),
				320, 240, true);
		int w = size.width;
		int h = size.height;
		mainView.setFrameDimensions(w, h);
		mainView.startCamera();
		
		Camera.Size photoSize = cameraPreview.getPhotoSize();
		aligner.start(new Size(photoSize.width, photoSize.height));
		
		applyPreferences();
		
		captureStartTimeMs = SystemClock.uptimeMillis();
		
		UiUtil.lockCurrentScreenOrientation(this);
		
		mainView.setOnPhotoTakenCallback(new Callback<Void>() {

			@Override
			public void onCallback(Void arg0) {
				if (captureEventListener == null) {
					return;
				}
				
				captureEventListener.onPhotoTaken();
			}
		});
		
	}
}
