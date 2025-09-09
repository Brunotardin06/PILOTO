package com.blankj.debug;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;

/**
 * A floating debug icon that can be dragged, snapped to screen edges,
 * and opens the debug menu on click.
 */
public class DebugIcon extends RelativeLayout {
    private static final Object INSTANCE_LOCK = new Object();
    private static WeakReference<DebugIcon> instanceRef = new WeakReference<>(null);

    private final OverlayOpener overlayOpener = new OverlayOpener();
    private final PositionStore positionStore = new PositionStore();
    private final EdgeSnapper edgeSnapper = new EdgeSnapper();

    private int iconResId;

    private DebugIcon(@NonNull Context context) {
        super(context);
        init();
    }

    private DebugIcon(@NonNull Context context, @DrawableRes int resId) {
        super(context);
        init();
        setIcon(resId);
    }

    private void init() {
        inflate(getContext(), R.layout.du_debug_icon, this);
        ShadowHelper.applyDebugIcon(this);

        // Touch handling
        TouchHandler touchHandler = new TouchHandler();
        setOnTouchListener(touchHandler);

        // Click opens debug menu
        setOnClickListener(v -> overlayOpener.open(getContext()));
    }

    /**
     * Obtain (or create) the singleton DebugIcon.
     */
    public static DebugIcon getInstance(@NonNull Context context) {
        DebugIcon inst = instanceRef.get();
        if (inst == null) {
            synchronized (INSTANCE_LOCK) {
                inst = instanceRef.get();
                if (inst == null) {
                    inst = new DebugIcon(context.getApplicationContext());
                    instanceRef = new WeakReference<>(inst);
                }
            }
        }
        return inst;
    }

    /**
     * Set icon resource ID and update view.
     */
    public void setIcon(@DrawableRes int resId) {
        if (resId == iconResId) return;
        iconResId = resId;
        ImageView iv = findViewById(R.id.debugIconIv);
        if (iv != null) {
            iv.setImageResource(resId);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restorePosition();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        savePosition();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        snapToEdge();
    }

    private void restorePosition() {
        int x = positionStore.loadX();
        int y = positionStore.loadY(getContext());
        setX(x);
        setY(y);
        snapToEdge();
    }

    private void savePosition() {
        positionStore.save((int) getX(), (int) getY());
    }

    private void snapToEdge() {
        edgeSnapper.snap(this);
    }
