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
    private static volatile float latestBottomDp;
    private static WeakReference<AppLibraryView> current = new WeakReference<>(null);

    private AppLibrary() {
    }

    /**
     * Called from smali: replaces I1.y.b(...) for the library page.
     *
     * @param modifier    the page modifier from the launcher (has padding; unused so the
     *                    frosted backdrop can fill the whole page)
     * @param bottomDp    bottom padding the launcher reserves for the dock, in dp
     */
    public static void content(G1.L5 state, a0.r modifier, O.o composer, float bottomDp) {
        final List<?> apps = state != null ? state.a : null;
        latestApps = apps;
        latestBottomDp = bottomDp;
        a0.r fill = androidx.compose.foundation.layout.d.c;
        androidx.compose.ui.viewinterop.a.b(FACTORY, fill != null ? fill : modifier, null, composer, 48);
        final AppLibraryView view = current.get();
        if (view != null) {
            view.post(() -> {
                view.setBottomInset(bottomDp);
                if (apps != null) view.setApps(apps);
            });
        }
    }

    /** AndroidView factory: (Context) -> View. */
    @Override
    public Object h(Object context) {
        AppLibraryView view = new AppLibraryView((Context) context);
        current = new WeakReference<>(view);
        view.setBottomInset(latestBottomDp);
        List<?> apps = latestApps;
        if (apps != null) {
            view.setApps(apps);
        }
        return (View) view;
    }
}
