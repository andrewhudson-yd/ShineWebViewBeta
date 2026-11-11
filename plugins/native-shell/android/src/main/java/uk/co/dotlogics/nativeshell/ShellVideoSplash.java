package uk.co.dotlogics.nativeshell;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Full-screen, muted intro video shown over the web view while the site loads.
 *
 * It fades away once the video has finished AND the page has loaded (or after maxWait),
 * holding the last frame if the page is slow. Tap to skip if skippable. Skipped entirely
 * when the user has turned animations off (Settings → Accessibility → Remove animations).
 */
class ShellVideoSplash implements TextureView.SurfaceTextureListener {

    interface OnDone {
        void onSplashDone();
    }

    private final Activity activity;
    private final String assetPath;
    private final int background;
    private final boolean skippable;
    private final long minMs;
    private final long maxWaitMs;
    private final OnDone onDone;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout overlay;
    private TextureView texture;
    private MediaPlayer player;
    private Surface surface;
    private Drawable originalWindowBackground;
    private long startedAt;
    private boolean videoEnded = false;
    private boolean pageReady = false;
    private boolean finished = false;
    private final boolean lightIcons;
    private boolean appLightIcons = false;

    /** statusBarStyle: "light" (white icons), "dark" (dark icons) or "auto" (from the background colour). */
    ShellVideoSplash(Activity activity, String assetPath, int background, String statusBarStyle, boolean skippable, double minSeconds, double maxWaitSeconds, OnDone onDone) {
        this.activity = activity;
        this.assetPath = assetPath;
        this.background = background;
        this.skippable = skippable;
        this.minMs = Math.round(minSeconds * 1000);
        this.maxWaitMs = Math.round(Math.max(maxWaitSeconds, 1) * 1000);
        this.onDone = onDone;
        if ("light".equals(statusBarStyle)) lightIcons = true;
        else if ("dark".equals(statusBarStyle)) lightIcons = false;
        else lightIcons = ColorUtils.calculateLuminance(background) < 0.5;
    }

    /** "everyLaunch" or "firstLaunch". Returns false (nothing shown) if the video can't or shouldn't play. */
    boolean show(String playMode) {
        if (!assetExists() || animationsDisabled()) return false;
        if ("firstLaunch".equals(playMode)) {
            SharedPreferences prefs = activity.getSharedPreferences("NativeShell.splash", Activity.MODE_PRIVATE);
            if (prefs.getBoolean("played", false)) return false;
            prefs.edit().putBoolean("played", true).apply();
        }

        startedAt = SystemClock.uptimeMillis();

        // Paint the status / navigation bar areas too, so the intro looks full-bleed.
        originalWindowBackground = activity.getWindow().getDecorView().getBackground();
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(background));

        // Status / navigation bar icons that read on the splash colour.
        applyBarIcons();
        main.post(this::applyBarIcons); // re-apply after Capacitor's own status bar setup

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(background);
        overlay.setClickable(true); // block touches to the page underneath
        overlay.setFocusable(true);
        if (skippable) overlay.setOnClickListener(v -> finish());

        texture = new TextureView(activity);
        texture.setAlpha(0f); // revealed on the first rendered frame (no black flash)
        texture.setSurfaceTextureListener(this);
        overlay.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        main.postDelayed(this::finish, maxWaitMs); // hard cap, whatever happens
        return true;
    }

    /** The app's own status bar style, restored when the intro ends. */
    void setAppLightIcons(boolean light) {
        appLightIcons = light;
    }

    /** Call when the page (or the offline page) has loaded. */
    void pageReady() {
        main.post(() -> {
            pageReady = true;
            maybeFinish();
        });
    }

    boolean isActive() {
        return overlay != null && !finished;
    }

    // region Playback

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        try {
            player = new MediaPlayer();
            setDataSource(player);
            surface = new Surface(st);
            player.setSurface(surface);
            player.setVolume(0f, 0f);
            player.setOnVideoSizeChangedListener((mp, w, h) -> centerCrop(w, h));
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    texture.animate().alpha(1f).setDuration(120).start();
                    applyBarIcons();
                }
                return false;
            });
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                videoEnded = true; // the last frame stays on screen
                maybeFinish();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                videoEnded = true;
                maybeFinish();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            videoEnded = true;
            maybeFinish();
        }
    }

    private void setDataSource(MediaPlayer mp) throws Exception {
        String path = "public/" + assetPath;
        try (AssetFileDescriptor afd = activity.getAssets().openFd(path)) {
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } catch (Exception compressed) {
            // Asset was compressed in the APK: copy it out once and play the file.
            File file = new File(activity.getCacheDir(), "splash-" + new File(assetPath).getName());
            if (!file.exists()) {
                try (InputStream in = activity.getAssets().open(path); OutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            mp.setDataSource(file.getAbsolutePath());
        }
    }

    /** Fill the screen like CSS object-fit: cover. */
    private void centerCrop(int videoW, int videoH) {
        if (texture == null || videoW == 0 || videoH == 0) return;
        float viewW = texture.getWidth(), viewH = texture.getHeight();
        if (viewW == 0 || viewH == 0) return;
        float scale = Math.max(viewW / videoW, viewH / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / viewW, videoH * scale / viewH, viewW / 2f, viewH / 2f);
        texture.setTransform(m);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        if (player != null) centerCrop(player.getVideoWidth(), player.getVideoHeight());
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // endregion

    private void maybeFinish() {
        if (finished || !videoEnded) return;
        long elapsed = SystemClock.uptimeMillis() - startedAt;
        if (!pageReady && elapsed < maxWaitMs) return; // hold the last frame until the page is ready
        if (elapsed < minMs) {
            main.postDelayed(this::maybeFinish, minMs - elapsed);
            return;
        }
        finish();
    }

    private void finish() {
        if (finished || overlay == null) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        overlay
            .animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction(() -> {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                if (parent != null) parent.removeView(overlay);
                release();
                activity.getWindow().setBackgroundDrawable(originalWindowBackground);
                // Back to the app's own style (theme.statusBarStyle), not the system default.
                WindowInsetsControllerCompat bars = barsController();
                bars.setAppearanceLightStatusBars(!appLightIcons);
                bars.setAppearanceLightNavigationBars(!appLightIcons);
                onDone.onSplashDone();
            })
            .start();
    }

    private void release() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
                // already released
            }
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private WindowInsetsControllerCompat barsController() {
        return WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    }

    /** "Light appearance" in Android terms means dark icons, so it's the opposite of lightIcons. */
    private void applyBarIcons() {
        if (finished) return;
        WindowInsetsControllerCompat bars = barsController();
        bars.setAppearanceLightStatusBars(!lightIcons);
        bars.setAppearanceLightNavigationBars(!lightIcons);
    }

    private boolean assetExists() {
        if (assetPath == null || assetPath.isEmpty()) return false;
        try (InputStream ignored = activity.getAssets().open("public/" + assetPath)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean animationsDisabled() {
        return Settings.Global.getFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }
}
