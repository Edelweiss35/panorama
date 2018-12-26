package com.google.android.apps.lightcycle.panorama;

import java.io.File;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;

import com.google.android.apps.lightcycle.panorama.StitchingServiceManager.StitchSession;
import com.google.android.apps.lightcycle.storage.LocalSessionStorage;
import com.google.android.apps.lightcycle.storage.StorageManager;
import com.google.android.apps.lightcycle.storage.StorageManagerFactory;
import com.google.android.apps.lightcycle.util.AnalyticsHelper;
import com.google.android.apps.lightcycle.util.LG;
import com.google.android.apps.lightcycle.util.MetadataUtils;
import com.google.android.apps.lightcycle.util.Utils;
import com.jingz.app.pano.R;

public class StitchingService extends Service {

	private static final String TAG = StitchingService.class.getSimpleName();
	private AnalyticsHelper analyticsHelper;
	private final IBinder binder = new StitchingBinder();
	private StitchTask currentTask = null;
	private Notification inProgressNotification;
	private NotificationManager notificationManager;
	private boolean paused = false;
	private final ServiceController serviceController = new ServiceController();
	private StitchingServiceManager stitchingServiceManager;
	private PowerManager.WakeLock wakeLock;
	
	public static ContentValues createImageContentValues(String imagePath) {
		ContentValues contentValues = new ContentValues();
		File imageFile = new File(imagePath);
		String filename = imageFile.getName();
		contentValues.put("title", filename.substring(0, filename.indexOf('.')));
		contentValues.put("_display_name", filename);
		contentValues.put("datetaken", Long.valueOf(System.currentTimeMillis()));
		contentValues.put("mime_type", "image/jpeg");
		contentValues.put("_size", Long.valueOf(imageFile.length()));
		contentValues.put("_data", imageFile.getAbsolutePath());
		
		File imageParent = imageFile.getParentFile();
		String lower = imageParent.toString().toLowerCase();
		contentValues.put("bucket_id", Integer.valueOf(lower.hashCode()));
		contentValues.put("bucket_display_name", imageParent.getName().toLowerCase());

		return contentValues;
	}
	
	private Uri createImageURI(String imagePath, Uri uri) {
		ContentValues contentValues = createImageContentValues(imagePath);
		ContentResolver resolver = getContentResolver();
		if (uri == null) {
			return resolver
					.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
							contentValues);
		}
		
		contentValues.remove("datetaken");
		resolver.update(uri, contentValues, null, null);
		return uri;
	}
	
	private Notification createInProgressNotification() {
		CharSequence title = getText(R.string.stitching_notification);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
		builder.setSmallIcon(R.drawable.z05_ic_switch_photosphere)
		       .setContentIntent(PendingIntent.getActivity(this, 1, new Intent(), 0))
		       .setTicker(title)
		       .setContentTitle(title)
		       .setProgress(100, 0, false);
		return builder.build();
	}
	
	private void stitchNextSession() {
		LG.d("Stitching next session.");
		StitchSession session = stitchingServiceManager.popNextSession();
		if (session == null) {
			stopSelf();
			return;
		}
		
		LocalSessionStorage storage = session.storage;
		currentTask = new StitchTask(session, storage.sessionDir,
				storage.mosaicFilePath, storage.thumbnailFilePath,
				storage.imageUri);
		if (paused) {
			currentTask.suspend();
		}
		
		if (Build.VERSION.SDK_INT >= 11) {
			currentTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
					new Void[0]);
			return;
		}
		
		currentTask.execute(new Void[0]);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}
	
	

	@Override
	public void onCreate() {
		notificationManager = (NotificationManager) getSystemService("notification");
		stitchingServiceManager = stitchingServiceManager
				.getStitchingServiceManager(this);
		wakeLock = ((PowerManager) getSystemService("power")).newWakeLock(
				PowerManager.PARTIAL_WAKE_LOCK, TAG);
		wakeLock.acquire();
		IntentFilter intentFilter = new IntentFilter();
		intentFilter
				.addAction("com.google.android.apps.lightcycle.panorama.PAUSE");
		intentFilter
				.addAction("com.google.android.apps.lightcycle.panorama.RESUME");
		LocalBroadcastManager.getInstance(this).registerReceiver(
				serviceController, intentFilter);
	}

	@Override
	public void onDestroy() {
		notificationManager.cancel(1);
		stitchingServiceManager.stitchingFinished();
		wakeLock.release();
		LocalBroadcastManager.getInstance(this).unregisterReceiver(
				serviceController);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		inProgressNotification = createInProgressNotification();
		startForeground(1, inProgressNotification);
		Notification notification = inProgressNotification;
		notification.flags = (0xffffffbf & notification.flags);
		notificationManager.notify(1, inProgressNotification);
		stitchNextSession();
		return Service.START_STICKY;
	}

	public void pause() {
		paused = true;
		if (currentTask != null) {
			currentTask.suspend();
		}
	}
	
	public void resume() {
		paused = false;
		if (currentTask != null && currentTask.isSuspended()) {
			currentTask.resume();
		}
	}
	
	public class ServiceController extends BroadcastReceiver {

		public ServiceController() {}
		
		@Override
		public void onReceive(Context context, Intent intent) {
			if ("com.google.android.apps.lightcycle.panorama.PAUSE".equals(intent.getAction())) {
				pause();
			} else if ("com.google.android.apps.lightcycle.panorama.RESUME".equals(intent.getAction())) {
				resume();
			}
		}
		
	}

	class StitchTask extends AsyncTask<Void, Void, Integer> {
		private final Uri imageUri;
	    private Object lock = new Object();
	    private final String outputFile;
	    private final String sessionPath;
	    private final StitchingServiceManager.StitchSession stitchSession;
	    private volatile boolean suspend = false;
	    private final String thumbnailFile;
	    
		public StitchTask(StitchingServiceManager.StitchSession stitchSession,
				String sessionPath, String outputFile, String thumbnailFile,
				Uri imageUri) {
			this.stitchSession = stitchSession;
			this.sessionPath = sessionPath;
			this.outputFile = outputFile;
			this.thumbnailFile = thumbnailFile;
			this.imageUri = imageUri;
		}
		
		private void waitIfSuspended() {
			if (!suspend) {
				return;
			}
			
			try {
				synchronized (lock) {
					lock.wait();
					return;
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		@Override
		protected Integer doInBackground(Void... params) {
			Process.setThreadPriority(-4);
			waitIfSuspended();
			
			int index = LightCycleNative.CreateNewStitchingSession();
			LightCycleNative.setProgressCallback(index,
					new LightCycleView.ProgressCallback() {

						@Override
						public void progress(int progress) {
							stitchingServiceManager.onStitchingProgress(
									outputFile, imageUri, progress);
							if (inProgressNotification != null) {
								inProgressNotification.contentView.setProgressBar(16908301, 100, progress, false);
								notificationManager.notify(1, inProgressNotification);
							}
							waitIfSuspended();
						}
					});

			long start = SystemClock.uptimeMillis();
			LG.d("Rendering panorama from source images at " + sessionPath);
			boolean useRealtimeAlignment = PreferenceManager.getDefaultSharedPreferences(
					getBaseContext()).getBoolean(
					"useRealtimeAlignment", false);
			boolean isDogfoodApp = Utils.isDogfoodApp(getApplicationContext());
			int result = LightCycleNative.StitchPanorama(sessionPath, outputFile,
					isDogfoodApp, thumbnailFile, 1000, 4.0f, index,
					useRealtimeAlignment);
			long end = SystemClock.uptimeMillis() - start;
			analyticsHelper = AnalyticsHelper.getInstance(StitchingService.this);
			analyticsHelper.trackPage(AnalyticsHelper.Page.STITCH_COMPLETE);
			analyticsHelper.trackEvent("Stitching", "Stitching", "Stitch time",
					(int) (end / 1000L));
			
			boolean usePanoramaViewer = true;
			if (result != 1) {
				usePanoramaViewer = false;
			}
			
			MetadataUtils.writeMetadataIntoJpegFile(outputFile,
					stitchSession.storage.metadataFilePath, sessionPath,
					usePanoramaViewer);
			
			return result;
		}
		
		public boolean isSuspended() {
			return suspend;
		}

		@Override
		protected void onPostExecute(Integer result) {
			int v0 = 1;
			if (result != 1) {

			}
			StorageManager storageManager = StorageManagerFactory.getStorageManager();
			storageManager.init(StitchingService.this);
			storageManager.addSessionData(stitchSession.storage);
			Uri uri = createImageURI(outputFile, imageUri);
			stitchingServiceManager.onStitchingResult(outputFile, uri);
			notificationManager.cancel(1);
			stitchNextSession();
		}

		@Override
		protected void onPreExecute() {
			System.gc();
		}
		
		public void resume() {
			suspend = false;
			synchronized (lock) {
				lock.notifyAll();
			}
		}
		
		public void suspend() {
			suspend = true;
		}
	}
	
	public class StitchingBinder extends Binder {
		public StitchingBinder() {}
	}
}
