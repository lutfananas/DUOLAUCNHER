package com.jake.duolauncher.applib;

import android.content.Context;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Entry point called from the patched Compose pager (G1/s5) in place of the
 * original "All apps" page. Hosts {@link AppLibraryView} through Compose's
 * AndroidView and pushes the latest app list into it on every recomposition.
 */
public final class AppLibrary implements U1.c {

    private static final AppLibrary FACTORY = new AppLibrary();
    private static volatile List<?> latestApps;
    private static WeakReference<AppLibraryView> current = new WeakReference<>(null);

    private AppLibrary() {
    }

    /** Called from smali: replaces I1.y.b(...) for the library page. */
    public static void content(G1.L5 state, a0.r modifier, O.o composer) {
        final List<?> apps = state != null ? state.a : null;
        latestApps = apps;
        androidx.compose.ui.viewinterop.a.b(FACTORY, modifier, null, composer, 48);
        final AppLibraryView view = current.get();
        if (view != null && apps != null) {
            view.post(() -> view.setApps(apps));
        }
    }

    /** AndroidView factory: (Context) -> View. */
    @Override
    public Object h(Object context) {
        AppLibraryView view = new AppLibraryView((Context) context);
        current = new WeakReference<>(view);
        List<?> apps = latestApps;
        if (apps != null) {
            view.setApps(apps);
        }
        return (View) view;
    }
}
