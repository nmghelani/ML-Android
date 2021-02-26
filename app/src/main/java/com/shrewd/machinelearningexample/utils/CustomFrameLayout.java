package com.shrewd.machinelearningexample.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class CustomFrameLayout extends FrameLayout {
  private Canvas canvas;
  private Paint paint = new Paint();

  public CustomFrameLayout(@NonNull Context context) {
    super(context);
  }

  public CustomFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  public CustomFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public CustomFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    this.canvas = canvas;
    super.onDraw(canvas);
  }

  public void displayRect(Rect rect, int color) {
    if (canvas != null) {
      clear();
      paint.setColor(color);
      paint.setStrokeWidth(5);
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawRect(rect, paint);
      invalidate();
      Log.d(this.getClass().getName(), "displayRect: " + rect);
//      Log.d(this.getClass().getName(), "displayRect: " + rect);
    }
  }

  private void clear() {
    if (canvas != null)
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
  }
}
