package org.vosk.demo;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

public class RoundedCornerHelper {

    // Константы для настройки радиуса скругления (в dp)
    public static final float DEFAULT_CORNER_RADIUS = 20f;
    public static final float MIN_CORNER_RADIUS = 0f;
    public static final float MAX_CORNER_RADIUS = 50f;

    // Текущие значения радиуса
    private static float topCornerRadius = DEFAULT_CORNER_RADIUS;
    private static float bottomCornerRadius = DEFAULT_CORNER_RADIUS;

    // Метод для установки радиуса скругления для верхних углов
    public static void setTopCornerRadius(float radiusInDp, Context context) {
        topCornerRadius = Math.max(MIN_CORNER_RADIUS, Math.min(MAX_CORNER_RADIUS, radiusInDp));
        updateCornerRadius(context);
    }

    // Метод для установки радиуса скругления для нижних углов
    public static void setBottomCornerRadius(float radiusInDp, Context context) {
        bottomCornerRadius = Math.max(MIN_CORNER_RADIUS, Math.min(MAX_CORNER_RADIUS, radiusInDp));
        updateCornerRadius(context);
    }

    // Метод для получения текущего радиуса верхних углов
    public static float getTopCornerRadius() {
        return topCornerRadius;
    }

    // Метод для получения текущего радиуса нижних углов
    public static float getBottomCornerRadius() {
        return bottomCornerRadius;
    }

    // Метод для обновления всех радиусов
    private static void updateCornerRadius(Context context) {
        // Здесь можно добавить логику для динамического обновления всех View
        // которые используют эти радиусы, если потребуется
    }

    // Метод для применения скругленных углов к View
    public static void applyRoundedCorners(View view, boolean isTop) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(0xFF000000); // Черный цвет

        float radius = isTop ? topCornerRadius : bottomCornerRadius;
        float[] radii;

        if (isTop) {
            // Верхние углы скруглены, нижние - нет
            radii = new float[] {
                    radius, radius,     // top-left
                    radius, radius,     // top-right
                    0, 0,               // bottom-right
                    0, 0                // bottom-left
            };
        } else {
            // Нижние углы скруглены, верхние - нет
            radii = new float[] {
                    0, 0,               // top-left
                    0, 0,               // top-right
                    radius, radius,     // bottom-right
                    radius, radius      // bottom-left
            };
        }

        shape.setCornerRadii(radii);
        view.setBackground(shape);
        view.setClipToOutline(true);
    }
}