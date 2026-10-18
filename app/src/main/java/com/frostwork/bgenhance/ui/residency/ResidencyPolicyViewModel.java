package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates loading and serialized rule publication for one application. */
public final class ResidencyPolicyViewModel implements AutoCloseable {
    public enum Status {
        LOADING,
        READY,
        SAVING,
        SAVED,
        DELETED,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        private final Status status;
        private final ResidencyAppItem app;
        private final ResidencyPolicyDraft draft;
        private final boolean existingRule;
        private final ResidencyRuleStore.Failure failure;

        private State(
                Status status,
                ResidencyAppItem app,
                ResidencyPolicyDraft draft,
                boolean existingRule,
                ResidencyRuleStore.Failure failure) {
            this.status = status;
            this.app = app;
            this.draft = draft;
            this.existingRule = existingRule;
            this.failure = failure;
        }

        public Status status() { return status; }
        public ResidencyAppItem app() { return app; }
        public ResidencyPolicyDraft draft() { return draft; }
        public boolean existingRule() { return existingRule; }
        public ResidencyRuleStore.Failure failure() { return failure; }
    }

    private final Context context;
    private final ResidencyAppCatalog catalog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ResidencyPolicyWriter");
        thread.setDaemon(true);
        return thread;
    });
    private final String packageName;
    private final int userId;
    private Listener listener;
    private State current;
    private int generation;
    private boolean closed;

    public ResidencyPolicyViewModel(Context context, String packageName) {
        this(context, packageName, AppResidencyPolicy.PRIMARY_USER_ID);
    }

    public ResidencyPolicyViewModel(Context context, String packageName, int userId) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.catalog = new ResidencyAppCatalog(this.context);
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.userId = userId;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void load() {
        if (closed) return;
        int requestGeneration = ++generation;
        publish(new State(Status.LOADING, null, null, false, null));
        executor.execute(() -> {
            try {
                ResidencyPolicyDocument document = ResidencyRuleStore.read(context);
                ResidencyAppItem app = catalog.loadSingle(packageName, userId);
                AppInstanceKey key = new AppInstanceKey(packageName, userId);
                AppResidencyPolicy policy = ResidencyRuleMutation.find(document, key);
                boolean existing = policy != null;
                if (policy == null) {
                    ResidencyMode defaultMode = document.defaultNewRuleMode();
                    policy = ResidencyPresets.create(packageName, userId, defaultMode);
                }
                State state = new State(
                        Status.READY, app, ResidencyPolicyDraft.from(policy), existing, null);
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(state);
                });
            } catch (RuntimeException exception) {
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) {
                        publish(new State(Status.ERROR, null, null, false,
                                ResidencyRuleStore.Failure.SAVE_FAILED));
                    }
                });
            }
        });
    }

    public void save(ResidencyPolicyDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (closed || current == null || current.app() == null) return;
        State previous = current;
        int requestGeneration = ++generation;
        publish(new State(
                Status.SAVING,
                previous.app(),
                draft,
                previous.existingRule(),
                null));
        executor.execute(() -> {
            try {
                ResidencyRuleStore.save(context, draft.toPolicy(packageName, userId));
                State saved = new State(Status.SAVED, previous.app(), draft, true, null);
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(saved);
                });
            } catch (ResidencyRuleStore.StoreException exception) {
                State error = new State(
                        Status.ERROR,
                        previous.app(),
                        draft,
                        previous.existingRule(),
                        exception.failure());
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(error);
                });
            } catch (RuntimeException exception) {
                State error = new State(
                        Status.ERROR,
                        previous.app(),
                        draft,
                        previous.existingRule(),
                        ResidencyRuleStore.Failure.INVALID_POLICY);
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(error);
                });
            }
        });
    }

    public void delete() {
        if (closed || current == null || current.app() == null || !current.existingRule()) return;
        State previous = current;
        int requestGeneration = ++generation;
        publish(new State(
                Status.SAVING,
                previous.app(),
                previous.draft(),
                true,
                null));
        executor.execute(() -> {
            try {
                ResidencyRuleStore.delete(context, new AppInstanceKey(packageName, userId));
                State deleted = new State(
                        Status.DELETED, previous.app(), previous.draft(), false, null);
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(deleted);
                });
            } catch (ResidencyRuleStore.StoreException exception) {
                State error = new State(
                        Status.ERROR,
                        previous.app(),
                        previous.draft(),
                        true,
                        exception.failure());
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(error);
                });
            } catch (RuntimeException exception) {
                State error = new State(
                        Status.ERROR,
                        previous.app(),
                        previous.draft(),
                        true,
                        ResidencyRuleStore.Failure.SAVE_FAILED);
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation) publish(error);
                });
            }
        });
    }

    private void publish(State state) {
        current = state;
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onStateChanged(state);
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        listener = null;
        executor.shutdownNow();
    }
}
