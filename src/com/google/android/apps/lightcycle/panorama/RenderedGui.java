package com.google.android.apps.lightcycle.panorama;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.view.MotionEvent;

import com.google.android.apps.lightcycle.opengl.Shader;
import com.google.android.apps.lightcycle.sensor.DeviceOrientationDetector;
import com.google.android.apps.lightcycle.util.Callback;
import com.jingz.app.pano.R;

public class RenderedGui extends MessageSender {
	private Button doneButton = null;
	private Callback<Boolean> doneButtonVisibilityListener = null;
	private boolean enabledUndoButton = true;
	private GuiManager guiManager = new GuiManager();
	private DeviceOrientationDetector orientationDetector;
	private boolean showOwnDoneButton = true;
	private boolean showOwnUndoButton = true;
	private Button undoButton = null;
	private Callback<Boolean> undoButtonStatusListener = null;
	private Callback<Boolean> undoButtonVisibilityListener = null;
	
	private void notifyDone() {
		notifyAll(1, 0.0f, "");
	}
	
	public void draw(float[] point) {
		guiManager.draw(point);
	}
	
	public boolean handleEvent(MotionEvent event) {
		return guiManager.handleEvent(event);
	}
	
	public void init(Context context, Shader shader, int width,
			int height, DeviceOrientationDetector detector) {
		orientationDetector = detector;
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		Bitmap bmDoneButton = BitmapFactory.decodeResource(context.getResources(),
				R.drawable.donebuttonglow, options);
		float doneScale = 0.129f * width / bmDoneButton.getWidth();
		bmDoneButton.recycle();
		
		doneButton = new Button(orientationDetector);
		doneButton.init(context, R.drawable.donebuttonglow,
				R.drawable.donebuttonglowselect, new PointF(0.0f, 0.0f),
				doneScale, shader, width, height);
		doneButton.setPosition(new PointF(0.85f, 0.1125f));
		doneButton.setVisible(false);
		guiManager.addElement(doneButton);
		
		doneButton.subscribe(new MessageSubscriber() {
			
			@Override
			public void message(int arg0, float arg1, String arg2) {
				doneButton.setVisible(false);
				notifyDone();
			}
		});
		
		Bitmap bmUndoButton = BitmapFactory.decodeResource(
				context.getResources(), R.drawable.undobuttonglow, options);
		float undoScale = 0.129f * width / bmUndoButton.getWidth();
		bmUndoButton.recycle();
		
		undoButton = new Button(orientationDetector);
		undoButton.init(context, R.drawable.undobuttonglow,
				R.drawable.undobuttonglowselect, new PointF(0.0f, 0.0f),
				undoScale, shader, width, height);
		undoButton.setPosition(new PointF(0.87f, 0.94f));
		undoButton.setVisible(false);
		guiManager.addElement(undoButton);
		undoButton.subscribe(new MessageSubscriber() {
			
			@Override
			public void message(int arg0, float arg1, String arg2) {
				notifyUndo();
			};
		});
	}
	
	public void notifyUndo() {
		notifyAll(2, 0.0F, "");
	}
	
	public void setDoneButtonVisibilityListener(Callback<Boolean> callback) {
		doneButtonVisibilityListener = callback;
	}
	
	public void setDoneButtonVisible(boolean visible) {
		if (doneButton != null) {
			if (visible && showOwnDoneButton) {
				// :goto_0
				doneButton.setVisible(true);
			} else {
				// :cond_2
				doneButton.setVisible(false);
			}
		}

		// :cond_0
		if (doneButtonVisibilityListener != null) {
			doneButtonVisibilityListener.onCallback(visible);
		}
	}
	
	public void setShowOwnDoneButton(boolean show) {
		showOwnDoneButton = show;
		if (doneButton == null || show) {
			return;
		}
		
		doneButton.setVisible(false);
	}
	
	public void setShowOwnUndoButton(boolean show) {
		showOwnUndoButton = show;
		if (undoButton == null || show) {
			return;
		}
		
		undoButton.setVisible(false);
	}
	
	public void setUndoButtonEnabled(boolean enabled) {
		if (enabledUndoButton == enabled) {
			return;
		}
		
		enabledUndoButton = enabled;
		if (undoButton != null) {
			undoButton.setEnabled(enabled);
		}
		
		if (undoButtonStatusListener != null) {
			undoButtonStatusListener.onCallback(enabled);
		}
	}
	
	public void setUndoButtonStatusListener(Callback<Boolean> callback) {
		undoButtonStatusListener = callback;
	}

	public void setUndoButtonVisibilityListener(Callback<Boolean> callback) {
		undoButtonVisibilityListener = callback;
	}
	
	public void setUndoButtonVisible(boolean visible) {
		if (undoButton != null) {
			if (visible && showOwnUndoButton) {
				undoButton.setVisible(true);
			} else {
				undoButton.setVisible(false);
			}
		}

		// :cond_0
		if (undoButtonVisibilityListener != null) {
			undoButtonVisibilityListener.onCallback(visible);
		}
	}
}
