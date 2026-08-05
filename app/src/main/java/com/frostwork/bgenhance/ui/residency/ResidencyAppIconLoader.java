package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.widget.ImageView;
import com.frostwork.bgenhance.R;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads application icons asynchronously and keeps a bounded in-memory cache. */
public final class ResidencyAppIconLoader implements AutoCloseable {
    private static final int CACHE_ENTRIES = 64;

    private final PackageManager packageManager;
    private final LruCache<String, Drawable.ConstantState> cache =
            new LruCache<>(CACHE_ENTRIES);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ResidencyIconLoader");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    public ResidencyAppIconLoader(Context context) {
        packageManager = Objects.requireNonNull(context, "context")
                .getApplicationContext().getPackageManager();
    }

    public void bind(ImageView imageView, String packageName) {
        Objects.requireNonNull(imageView, "imageView");
        Objects.requireNonNull(packageName, "packageName");
        imageView.setTag(R.id.tag_residency_icon_request, packageName);
        Drawable.ConstantState cached = cache.get(packageName);
        if (cached != null) {
            imageView.setImageDrawable(cached.newDrawable(imageView.getResources()));
            return;
        }
        imageView.setImageResource(R.drawable.ic_residency_app);
        if (closed) return;
        executor.execute(() -> {
            Drawable icon;
            try {
                icon = packageManager.getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
                return;
            }
            Drawable.ConstantState state = icon.getConstantState();
            if (state == null || closed) return;
            cache.put(packageName, state);
            imageView.post(() -> {
                if (closed) return;
                Object request = imageView.getTag(R.id.tag_residency_icon_request);
                if (packageName.equals(request)) {
                    imageView.setImageDrawable(state.newDrawable(imageView.getResources()));
                }
            });
        });
    }

    public void cancel(ImageView imageView) {
        imageView.setTag(R.id.tag_residency_icon_request, null);
        imageView.setImageResource(R.drawable.ic_residency_app);
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        cache.evictAll();
    }
}
