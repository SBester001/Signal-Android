/*
 * Copyright (C) 2016 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.scribbles.widget;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.scribbles.widget.entity.MotionEntity;
import org.thoughtcrime.securesms.scribbles.widget.entity.TextEntity;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.SettableFuture;

import java.util.LinkedHashSet;
import java.util.Set;

public class ScribbleView extends FrameLayout {

  private static final String TAG = ScribbleView.class.getSimpleName();

  public static final int DEFAULT_BRUSH_WIDTH = CanvasView.DEFAULT_STROKE_WIDTH;

  private ImageView imageView;
  private MotionView motionView;
  private CanvasView canvasView;

  private @Nullable Uri imageUri;

  public ScribbleView(Context context) {
    super(context);
    initialize(context);
  }

  public ScribbleView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(context);
  }

  public ScribbleView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize(context);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public ScribbleView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize(context);
  }

  public void setImage(@NonNull GlideRequests glideRequests, @NonNull Uri uri) {
    this.imageUri = uri;

    glideRequests.load(new DecryptableUri(uri))
                 .diskCacheStrategy(DiskCacheStrategy.NONE)
                 .fitCenter()
                 .into(imageView);
  }

  public @NonNull ListenableFuture<Bitmap> getRenderedImage(@NonNull GlideRequests glideRequests) {
    final SettableFuture<Bitmap> future      = new SettableFuture<>();
    final Context                context     = getContext();
    final boolean                isLowMemory = Util.isLowMemory(context);

    if (imageUri == null) {
      future.setException(new IllegalStateException("No image URI."));
      return future;
    }

    int width  = Target.SIZE_ORIGINAL;
    int height = Target.SIZE_ORIGINAL;

    if (isLowMemory) {
      width  = 768;
      height = 768;
    }

    glideRequests.asBitmap()
                 .load(new DecryptableUri(imageUri))
                 .diskCacheStrategy(DiskCacheStrategy.NONE)
                 .skipMemoryCache(true)
                 .override(width, height)
                 .into(new SimpleTarget<Bitmap>() {
                   @Override
                   public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                     Canvas canvas = new Canvas(bitmap);
                     motionView.render(canvas);
                     canvasView.render(canvas);
                     future.set(bitmap);
                   }

                   @Override
                   public void onLoadFailed(@Nullable Drawable errorDrawable) {
                     future.setException(new Throwable("Failed to load image."));
                   }
               });

    return future;
  }

  private void initialize(@NonNull Context context) {
    inflate(context, R.layout.scribble_view, this);

    this.imageView  = findViewById(R.id.image_view);
    this.motionView = findViewById(R.id.motion_view);
    this.canvasView = findViewById(R.id.canvas_view);
  }

  public void setMotionViewCallback(MotionView.MotionViewCallback callback) {
    this.motionView.setMotionViewCallback(callback);
  }

  @SuppressLint("ClickableViewAccessibility")
  public void setDrawingChangedListener(@Nullable DrawingChangedListener listener) {
    this.canvasView.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        if (listener != null) {
          listener.onDrawingChanged();
        }
      }
      return false;
    });
  }

  public void setDrawingMode(boolean enabled) {
    this.canvasView.setActive(enabled);
    if (enabled) this.motionView.unselectEntity();
  }

  public void setDrawingBrushColor(int color) {
    this.canvasView.setPaintFillColor(color);
    this.canvasView.setPaintStrokeColor(color);
    this.canvasView.setOpacity(Color.alpha(color));
  }

  public void setDrawingBrushWidth(int width) {
    this.canvasView.setPaintStrokeWidth(width);
  }

  public void addEntityAndPosition(MotionEntity entity) {
    this.motionView.addEntityAndPosition(entity);
  }

  public MotionEntity getSelectedEntity() {
    return this.motionView.getSelectedEntity();
  }

  public void deleteSelected() {
    this.motionView.deletedSelectedEntity();
  }

  public void clearSelection() {
    this.motionView.unselectEntity();
  }

  public void undoDrawing() {
    this.canvasView.undo();
  }

  public void startEditing(TextEntity entity) {
    this.motionView.startEditing(entity);
  }

  public @NonNull Set<Integer> getUniqueColors() {
    Set<Integer> colors = new LinkedHashSet<>();

    colors.addAll(motionView.getUniqueColors());
    colors.addAll(canvasView.getUniqueColors());

    return colors;
  }

  @Override
  public void onMeasure(int width, int height) {
    super.onMeasure(width, height);

    setMeasuredDimension(imageView.getMeasuredWidth(), imageView.getMeasuredHeight());

    canvasView.measure(MeasureSpec.makeMeasureSpec(imageView.getMeasuredWidth(), MeasureSpec.EXACTLY),
                       MeasureSpec.makeMeasureSpec(imageView.getMeasuredHeight(), MeasureSpec.EXACTLY));

    motionView.measure(MeasureSpec.makeMeasureSpec(imageView.getMeasuredWidth(), MeasureSpec.EXACTLY),
                       MeasureSpec.makeMeasureSpec(imageView.getMeasuredHeight(), MeasureSpec.EXACTLY));
  }

  public interface DrawingChangedListener {
    void onDrawingChanged();
  }
}
