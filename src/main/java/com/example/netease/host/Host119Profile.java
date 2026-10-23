package com.example.netease.host;

import java.lang.reflect.Modifier;

/** Names verified against installed 1.19.0 / 1.19.1 / 1.19.2 runtimes.
 * Resolve without initializing host classes and validate signatures before use.
 */
final class Host119Profile {
    record Profile(Class<?> databaseHolder, Class<?> trackDao, Class<?> scanRepository, Class<?> refreshController) { }

    private static final String[][] NAMES = {
            {"com.xuncorp.voxzen.data.\u058f", "androidx.compose.ui.\u0573", "androidx.compose.ui.\u04ee", "androidx.compose.ui.\u0baf"},
            {"com.xuncorp.voxzen.data.\u0781", "androidx.compose.ui.\u0573", "androidx.compose.ui.\u04ee", "androidx.compose.ui.\u0baf"},
            {"com.xuncorp.voxzen.data.\u0560", "androidx.compose.ui.\u060b", "androidx.compose.ui.\u021a", "androidx.compose.ui.\u01aa"},
    };

    static Profile resolve() {
        ClassLoader loader = Host119Profile.class.getClassLoader();
        for (String[] names : NAMES) {
            try {
                Class<?> holder = Class.forName(names[0], false, loader);
                var getter = holder.getMethod("\u037f");
                if (!Modifier.isStatic(getter.getModifiers())
                        || !getter.getReturnType().getName().equals("com.xuncorp.voxzen.data.AppDatabase")) continue;
                Class<?> dao = Class.forName(names[1], false, loader);
                if (!dao.isInterface() || !dao.getMethod("\u0528").getReturnType().getName().equals("kotlinx.coroutines.flow.Flow")) continue;
                Class<?> scan = Class.forName(names[2], false, loader);
                var scanDao = scan.getDeclaredField("\u0528");
                if (!Modifier.isStatic(scanDao.getModifiers()) || scanDao.getType() != dao) continue;
                Class<?> refresh = Class.forName(names[3], false, loader);
                var state = refresh.getDeclaredField("\u052c");
                if (!Modifier.isStatic(state.getModifiers())
                        || !state.getType().getName().equals("kotlinx.coroutines.flow.MutableStateFlow")) continue;
                return new Profile(holder, dao, scan, refresh);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // A same-named class with a different shape is never a valid profile.
            }
        }
        return null;
    }
}
