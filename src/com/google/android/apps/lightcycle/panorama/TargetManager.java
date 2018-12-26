package com.google.android.apps.lightcycle.panorama;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import android.R.integer;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.FloatMath;

import com.google.android.apps.lightcycle.math.Vector3;
import com.google.android.apps.lightcycle.opengl.OpenGLException;
import com.google.android.apps.lightcycle.opengl.Sprite;
import com.google.android.apps.lightcycle.sensor.DeviceOrientationDetector;
import com.google.android.apps.lightcycle.sensor.SensorReader;
import com.google.android.apps.lightcycle.shaders.ScaledTransparencyShader;
import com.google.android.apps.lightcycle.shaders.TargetShader;
import com.google.android.apps.lightcycle.util.LG;
import com.jingz.app.pano.R;

public class TargetManager {
	private static final float MAX_ANGLE_THRESHOLD_RAD = degreesToRadians(22.0F);
	private static final float MIN_ANGLE_THRESHOLD_RAD = degreesToRadians(12.0F);
	private float activeTargetAlpha = 0.0F;
	private int activeTargetIndex = -1;
	private AlphaScalePair alphaScalePair = new AlphaScalePair();
	private float[] currentDeviceTransform = null;
	private DeviceOrientationDetector deviceOrientationDetector = null;
	private float halfSurfaceHeight;
	private float halfSurfaceWidth;
	private float hitTargetAlpha = 0.0F;
	private float[] hitTargetTransform = null;
	private Context mContext;
	private boolean mTargetInRange = false;
	private Map<Integer, float[]> mTargets = Collections
			.synchronizedMap(new TreeMap<Integer, float[]>());
	private Sprite nearestSpriteOrtho;
	private float[] projected = new float[4];
	private SensorReader sensorReader = null;
	private float targetHitAngleDeg = 2.0F;
	private TargetShader targetShader;
	private Sprite targetSpriteOrtho;
	private float[] tempTransform = new float[16];
	private ScaledTransparencyShader transparencyShader;
	private float[] unitVector = { 0.0F, 0.0F, -1.0F, 1.0F };
	private Sprite viewFinderSprite;
	private Sprite viewfinderActivatedSprite;
	private Point viewfinderCoord;
	
	public TargetManager(Context context) {
		mContext = context;
	}
	
	private void computeProximityAlphaAndScale(float[] f, Vector3 v,
			AlphaScalePair as) {
		float f1 = (float) Math.acos(new Vector3(-f[8], -f[9], -f[10]).dot(v));
		if (f1 < MIN_ANGLE_THRESHOLD_RAD) {
			as.alpha = 1.0f;
			as.scale = 1.0f;
			return;
		}
		
		if (f1 < MAX_ANGLE_THRESHOLD_RAD) {
			float f2 = MAX_ANGLE_THRESHOLD_RAD - MIN_ANGLE_THRESHOLD_RAD;
			float f3 = 1.0f - (f1 - MIN_ANGLE_THRESHOLD_RAD) / f2;
			as.alpha = 0.0f + f3 * 1.0f;
			as.scale = 0.4f + 0.6f * f3;
			return;
		}
		
		as.alpha = 0.0f;
		as.scale = 0.4f;
	}
	
	private static float degreesToRadians(float degree) {
		return 0.01745329F * degree;
	}
	
	private void drawHitTarget(float[] f1, float[] f2) throws OpenGLException {
		if (hitTargetTransform == null) {
			return;
		}
		
		targetShader.bind();
		targetShader.setAlpha(hitTargetAlpha);
		drawTarget(f1, f2, hitTargetTransform, nearestSpriteOrtho);
		hitTargetAlpha *= 0.9f;
		
		if (hitTargetAlpha < 0.05f) {
			hitTargetAlpha = 0.0f;
			hitTargetTransform = null;
		}
	}
	
	private void drawTarget(float[] paramArrayOfFloat1,
			float[] paramArrayOfFloat2, float[] paramArrayOfFloat3,
			Sprite paramSprite) throws OpenGLException {
		Matrix.multiplyMM(this.tempTransform, 0, paramArrayOfFloat1, 0,
				paramArrayOfFloat3, 0);
		Matrix.multiplyMV(this.projected, 0, this.tempTransform, 0,
				this.unitVector, 0);
		normalize(this.projected);
		paramSprite.drawRotated(paramArrayOfFloat2, this.projected[0]
				* this.halfSurfaceWidth + this.halfSurfaceWidth,
				this.projected[1] * this.halfSurfaceHeight
						+ this.halfSurfaceHeight, 0.0F, 1.0F);
	}
	
	private void drawViewfinder(float[] paramArrayOfFloat)
			throws OpenGLException {
		float angle = 90.0f;
		float transAlpha = 0.4f;
		float nearestDegrees = deviceOrientationDetector.getOrientation().nearestOrthoAngleDegrees;
		int v1 = 0;
		if (nearestDegrees == angle || nearestDegrees == -90.0f) {
			v1 = 1;
		}
		
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(770, 771);
		if (v1 == 0) {
			angle = 0.0f;
		}
		
		if (hitTargetAlpha > 0) {
			transAlpha += hitTargetAlpha; 
		}
		
		transparencyShader.bind();
		transparencyShader.setAlpha(transAlpha);
		viewFinderSprite.drawRotatedCentered(paramArrayOfFloat,
				viewfinderCoord.x, viewfinderCoord.y, angle);
		
	}
	
	private void normalize(float[] paramArrayOfFloat) {
		paramArrayOfFloat[0] /= paramArrayOfFloat[3];
		paramArrayOfFloat[1] /= paramArrayOfFloat[3];
		paramArrayOfFloat[2] /= paramArrayOfFloat[3];
		paramArrayOfFloat[3] = 1.0F;
	}
	
	private void setRotationTranspose(float[] paramArrayOfFloat1, int paramInt,
			float[] paramArrayOfFloat2) {
		paramArrayOfFloat2[0] = paramArrayOfFloat1[paramInt];
		paramArrayOfFloat2[1] = paramArrayOfFloat1[(paramInt + 1)];
		paramArrayOfFloat2[2] = paramArrayOfFloat1[(paramInt + 2)];
		paramArrayOfFloat2[3] = 0.0F;
		paramArrayOfFloat2[4] = paramArrayOfFloat1[(paramInt + 3)];
		paramArrayOfFloat2[5] = paramArrayOfFloat1[(paramInt + 4)];
		paramArrayOfFloat2[6] = paramArrayOfFloat1[(paramInt + 5)];
		paramArrayOfFloat2[7] = 0.0F;
		paramArrayOfFloat2[8] = paramArrayOfFloat1[(paramInt + 6)];
		paramArrayOfFloat2[9] = paramArrayOfFloat1[(paramInt + 7)];
		paramArrayOfFloat2[10] = paramArrayOfFloat1[(paramInt + 8)];
		paramArrayOfFloat2[11] = 0.0F;
		paramArrayOfFloat2[12] = 0.0F;
		paramArrayOfFloat2[13] = 0.0F;
		paramArrayOfFloat2[14] = 0.0F;
		paramArrayOfFloat2[15] = 1.0F;
	}
	
	private void setTargetHitAngle() {
		LightCycleNative
				.SetTargetHitAngleRadians(0.01745329F * (2.0F + 1.5F * ((Math
						.max(Math.min(FloatMath.sqrt(this.sensorReader
								.getAngularVelocitySquaredRad()), 0.6981317F),
								0.1745329F) - 0.1745329F) / 0.5235988F)));
	}
	
	public void drawTargetsOrthographic(float[] f1, float[] f2) {
		activeTargetIndex = LightCycleNative.GetTargetInRange();
		if (activeTargetIndex < 0) {
			mTargetInRange = false;
			activeTargetAlpha = 0;
		} else {
			mTargetInRange = true;
			activeTargetAlpha += 0.1f * (1.0f - activeTargetAlpha);
		}

		setTargetHitAngle();
		//
		Vector3 vector = new Vector3(-1.0f * currentDeviceTransform[2], -1.0f
				* currentDeviceTransform[6], -1.0f * currentDeviceTransform[10]);

		GLES20.glBlendFunc(1, 771);

		targetShader.bind();
		targetShader.setContrastFactor(1.0f);
		targetShader.setAlpha(1.0f);

		try {
			float v17 = -currentDeviceTransform[6];
			synchronized (mTargets) {
				Set<Entry<Integer, float[]>> entries = mTargets.entrySet();
				Iterator<Entry<Integer, float[]>> iterator = entries.iterator();
				while (iterator.hasNext()) {
					Entry<Integer, float[]> entry = iterator.next();
					Matrix.multiplyMM(tempTransform, 0, f1, 0,
							entry.getValue(), 0);
					Matrix.multiplyMV(projected, 0, tempTransform, 0,
							unitVector, 0);
					computeProximityAlphaAndScale(entry.getValue(), vector,
							alphaScalePair);
					float v23 = alphaScalePair.alpha;
					float v12 = alphaScalePair.scale;
					if (mTargets.size() == 1) {
						v23 = Math.max(0.75f, v23);
						v12 = 1.0f;
					}

					if (projected[3] < 0.0f) {
						continue;
					}

					normalize(projected);

					float w = projected[0] * halfSurfaceWidth
							+ halfSurfaceWidth;
					float h = projected[1] * halfSurfaceHeight
							+ halfSurfaceHeight;
					if (entry.getKey() != activeTargetIndex) {
						targetShader.setAlpha(v23);
						targetSpriteOrtho.drawRotated(f2, w, h, 0, v12);
						continue;
					}
					
					targetShader.setAlpha(activeTargetAlpha * v23);
					nearestSpriteOrtho.drawRotated(f2, w, h, 0, v12);

					targetShader.setAlpha((1.0f - activeTargetAlpha) * v23);
					targetSpriteOrtho.drawRotated(f2, w, h, 0, v12);
					
					targetShader.setAlpha(1.0f);
					
				}
			}
			
			drawHitTarget(f1, f2);
			drawViewfinder(f2);
		} catch (OpenGLException e) {
			e.printStackTrace();
		}
		
		GLES20.glBlendFunc(770, 771);
	}
	
	public void init(int width, int height) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		BitmapFactory.decodeResource(mContext.getResources(),
				R.drawable.z01_pano_target_default, options).recycle();
		targetSpriteOrtho = new Sprite();
		targetSpriteOrtho.init2D(mContext,
				R.drawable.z01_pano_target_default, -1.0f, 1.0f);
		nearestSpriteOrtho = new Sprite();
		nearestSpriteOrtho.init2D(mContext,
				R.drawable.z02_pano_target_activated, -1.0f, 1.0f);
		
		try {
			targetShader = new TargetShader();
			transparencyShader = new ScaledTransparencyShader();
			if (targetShader == null) {
				LG.d("Failed to create target shader");
			}
			
			if (transparencyShader == null) {
				LG.d("Failed to create texture shader");
			}
			
			targetSpriteOrtho.setShader(targetShader);
			nearestSpriteOrtho.setShader(targetShader);
			halfSurfaceWidth = width / 2.0f;
			halfSurfaceHeight = height / 2.0f;
			float[] identityMatrix = new float[16];
			Matrix.setIdentityM(identityMatrix, 0);
			mTargets.put(0, identityMatrix);
			viewFinderSprite = new Sprite();
			viewFinderSprite.init2D(mContext,
					R.drawable.z03_pano_reticule_default, 4.0f, 1.0f);
			viewFinderSprite.setShader(transparencyShader);
			
			viewfinderActivatedSprite = new Sprite();
			viewfinderActivatedSprite.init2D(mContext,
					R.drawable.z04_pano_reticule_activated, 4.0f, 1.0f);
			viewfinderActivatedSprite.setShader(transparencyShader);
			viewfinderCoord = new Point(width / 2 - viewFinderSprite.getWidth() / 2, 
					height / 2 - viewFinderSprite.getHeight() / 2);
		} catch (OpenGLException e) {
			e.printStackTrace();
		}
	}
	
	public void reset() {
		mTargets.clear();
		float[] arrayOfFloat = new float[16];
		Matrix.setIdentityM(arrayOfFloat, 0);
		mTargets.put(Integer.valueOf(0), arrayOfFloat);
	}
	
	public void setCurrentOrientation(float[] paramArrayOfFloat) {
		currentDeviceTransform = paramArrayOfFloat;
	}

	public void setDeviceOrientationDetector(DeviceOrientationDetector detector) {
		deviceOrientationDetector = detector;
	}

	public void setSensorReader(SensorReader sensorReader) {
		this.sensorReader = sensorReader;
	}
	
	public void updateTargets() {
		int[] deletedTargets = LightCycleNative.GetDeletedTargets();
		NewTarget[] newTargets = LightCycleNative.GetNewTargets();
		
		if (deletedTargets != null) {
			for (int i = (deletedTargets.length - 1); i >= 0; i--) {
				if (deletedTargets[i] == activeTargetIndex) {
					float[] transform = mTargets.get(deletedTargets[i]);
					if (transform != null) {
						hitTargetTransform = transform.clone();
						hitTargetAlpha = 1.0f;
					} else {
						// :cond_1
						hitTargetTransform = null;
						hitTargetAlpha = 0;
					}
					
					// :goto_1, :cond_0
					mTargets.remove(deletedTargets[i]);
				}
			}
		}
		
		// :cond_2
		if (newTargets == null) {
			return;
		}
		
		for (int j = 0; j < newTargets.length; j++) {
			float[] transpose = new float[16];
			setRotationTranspose(newTargets[j].orientation, 0, transpose);
			mTargets.put(newTargets[j].key, transpose);
		}
		
		// :cond_3
		LG.d("Number of targets " + mTargets.size());
	}

	private class AlphaScalePair {
		float alpha;
		float scale;
		
		private AlphaScalePair() {}
	}
}
