package uk.co.dotlogics.nativeshell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.PathParser;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A light-weight bottom tab bar (no Material theme needed). Icons are Material Design paths. */
class ShellTabBar extends LinearLayout {

    interface OnSelect {
        void onSelect(int index);
    }

    private static final int INACTIVE = Color.parseColor("#8E8E93");
    private final List<ImageView> icons = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final List<TextView> badges = new ArrayList<>();
    private final LinearLayout row;
    private int accent = Color.parseColor("#2563EB");
    private int selected = 0;
    private OnSelect onSelect;

    ShellTabBar(Context context) {
        super(context);
        setOrientation(VERTICAL);

        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#1F000000"));
        addView(divider, new LayoutParams(LayoutParams.MATCH_PARENT, 1));

        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));

        // Sit above the gesture / navigation bar when the web view is edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });
    }

    void setTabs(List<ShellSettings.Tab> tabs, Integer accentColor, Integer background, OnSelect onSelect) {
        this.onSelect = onSelect;
        if (accentColor != null) accent = accentColor;
        setBackgroundColor(background != null ? background : Color.WHITE);
        row.removeAllViews();
        icons.clear();
        labels.clear();
        badges.clear();

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            ShellSettings.Tab tab = tabs.get(i);

            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setContentDescription(tab.title);
            TypedValue ripple = new TypedValue();
            getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            item.setBackgroundResource(ripple.resourceId);
            item.setOnClickListener(v -> {
                select(index);
                if (this.onSelect != null) this.onSelect.onSelect(index);
            });

            FrameLayout iconWrap = new FrameLayout(getContext());
            ImageView icon = new ImageView(getContext());
            icon.setImageDrawable(new PathIcon(iconPath(tab.icon)));
            iconWrap.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));

            TextView badge = new TextView(getContext());
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(4), 0, dp(4), 0);
            badge.setMinWidth(dp(16));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Color.parseColor("#FF3B30"));
            pill.setCornerRadius(dp(8));
            badge.setBackground(pill);
            badge.setVisibility(GONE);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP | Gravity.END);
            bl.setMargins(0, 0, -dp(10), 0);
            iconWrap.addView(badge, bl);
            iconWrap.setClipChildren(false);
            item.setClipChildren(false);

            TextView label = new TextView(getContext());
            label.setText(tab.title);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setSingleLine(true);
            label.setPadding(0, dp(2), 0, 0);

            item.addView(iconWrap, new LinearLayout.LayoutParams(dp(40), dp(26)));
            item.addView(label);
            row.addView(item, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

            icons.add(icon);
            labels.add(label);
            badges.add(badge);
        }
        select(selected < tabs.size() ? selected : 0);
        setVisibility(tabs.isEmpty() ? GONE : VISIBLE);
    }

    boolean hasTabs() {
        return !icons.isEmpty();
    }

    void select(int index) {
        if (index < 0 || index >= icons.size()) return;
        selected = index;
        for (int i = 0; i < icons.size(); i++) {
            int color = i == index ? accent : INACTIVE;
            ((PathIcon) icons.get(i).getDrawable()).setColor(color);
            labels.get(i).setTextColor(color);
        }
    }

    void setBadge(int index, String value) {
        if (index < 0 || index >= badges.size()) return;
        TextView b = badges.get(index);
        b.setText(value);
        b.setVisibility(value == null || value.isEmpty() ? GONE : VISIBLE);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // Material Design icon paths (Apache 2.0), 24x24 viewport. Names shared with iOS.
    private static final Map<String, String> PATHS = new HashMap<>();

    static {
        PATHS.put("home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z");
        PATHS.put(
            "search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
        );
        PATHS.put(
            "bell",
            "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
        );
        PATHS.put(
            "user",
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
        );
        PATHS.put(
            "account",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,5c1.66,0 3,1.34 3,3s-1.34,3 -3,3 -3,-1.34 -3,-3 1.34,-3 3,-3zM12,19.2c-2.5,0 -4.71,-1.28 -6,-3.22 0.03,-1.99 4,-3.08 6,-3.08 1.99,0 5.97,1.09 6,3.08 -1.29,1.94 -3.5,3.22 -6,3.22z"
        );
        PATHS.put(
            "settings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
        );
        PATHS.put(
            "cart",
            "M7,18c-1.1,0 -1.99,0.9 -1.99,2S5.9,22 7,22s2,-0.9 2,-2 -0.9,-2 -2,-2zM1,2v2h2l3.6,7.59 -1.35,2.45c-0.16,0.28 -0.25,0.61 -0.25,0.96 0,1.1 0.9,2 2,2h12v-2H7.42c-0.14,0 -0.25,-0.11 -0.25,-0.25l0.03,-0.12 0.9,-1.63h7.45c0.75,0 1.41,-0.41 1.75,-1.03l3.58,-6.49C21.25,4.82 20.76,4 20.12,4H5.21l-0.94,-2H1zM17,18c-1.1,0 -1.99,0.9 -1.99,2s0.89,2 1.99,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
        );
        PATHS.put(
            "bag",
            "M18,6h-2c0,-2.21 -1.79,-4 -4,-4S8,3.79 8,6H6c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2zM12,4c1.1,0 2,0.9 2,2h-4c0,-1.1 0.9,-2 2,-2z"
        );
        PATHS.put(
            "heart",
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        );
        PATHS.put("star", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z");
        PATHS.put(
            "calendar",
            "M19,4h-1V2h-2v2H8V2H6v2H5C3.89,4 3.01,4.9 3.01,6L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6C21,4.9 20.1,4 19,4zM19,20H5V10h14V20z"
        );
        PATHS.put("chat", "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z");
        PATHS.put("menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z");
        PATHS.put(
            "grid",
            "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z"
        );
        PATHS.put(
            "info",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
        );
        PATHS.put("list", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z");
        PATHS.put(
            "map",
            "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5c0,-0.28 -0.22,-0.5 -0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z"
        );
        PATHS.put(
            "camera",
            "M12,15.2c1.77,0 3.2,-1.43 3.2,-3.2S13.77,8.8 12,8.8 8.8,10.23 8.8,12s1.43,3.2 3.2,3.2zM9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z"
        );
        PATHS.put(
            "plus",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13h-4v4h-2v-4H7v-2h4V7h2v4h4v2z"
        );
        PATHS.put("bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z");
        PATHS.put(
            "play",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,16.5v-9l6,4.5 -6,4.5z"
        );
        PATHS.put("notifications", PATHS.get("bell"));
        PATHS.put("profile", PATHS.get("user"));
        PATHS.put("messages", PATHS.get("chat"));
    }

    static String iconPath(String name) {
        String p = PATHS.get(name);
        return p != null ? p : "M12,12m-6,0a6,6 0,1 1,12 0a6,6 0,1 1,-12 0";
    }

    /** Draws a 24x24 SVG path scaled to its bounds. */
    static class PathIcon extends Drawable {

        private final Path source;
        private final Path scaled = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PathIcon(String data) {
            Path p;
            try {
                p = PathParser.createPathFromPathData(data);
            } catch (RuntimeException e) {
                p = new Path();
            }
            source = p;
            paint.setStyle(Paint.Style.FILL);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            Matrix m = new Matrix();
            m.setScale(bounds.width() / 24f, bounds.height() / 24f);
            m.postTranslate(bounds.left, bounds.top);
            source.transform(m, scaled);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawPath(scaled, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 72;
        }

        @Override
        public int getIntrinsicHeight() {
            return 72;
        }
    }
}
