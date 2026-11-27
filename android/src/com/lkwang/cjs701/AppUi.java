package com.lkwang.cjs701;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.TextView;

final class AppUi {
    static final int INK = 0xFF152D28;
    static final int MUTED = 0xFF526662;
    static final int TEAL = 0xFF176D5C;
    static final int BACKGROUND = 0xFFF5F7F3;
    static final int LINE = 0xFFDCE5DD;
    static final int SELECTED = 0xFFE0EEE7;

    private AppUi() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, float corner, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, corner));
        if (stroke != 0) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    static RippleDrawable action(Context context, int color, float corner, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x19176D5C), shape(context, color, corner, stroke),
                shape(context, 0xFFFFFFFF, corner, 0));
    }

    static TextView text(Context context, String content, float size, int color) {
        TextView text = new TextView(context);
        text.setText(content);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }
}
