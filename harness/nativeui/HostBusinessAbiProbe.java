import java.lang.reflect.*;
import java.util.Arrays;

/** Real host signature validation without initializing the host DB or writing data. */
public final class HostBusinessAbiProbe {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        Class<?> profiles = Class.forName("com.example.netease.host.Host119Profile");
        Method resolve = profiles.getDeclaredMethod("resolve"); resolve.setAccessible(true);
        Object profile = resolve.invoke(null);
        if (profile == null) throw new AssertionError("No verified host business profile");
        checks++;
        String[] methods = {"databaseHolder", "trackDao", "scanRepository", "refreshController"};
        Class<?>[] types = new Class<?>[4];
        for (int i = 0; i < methods.length; i++) {
            Method accessor = profile.getClass().getDeclaredMethod(methods[i]); accessor.setAccessible(true);
            types[i] = (Class<?>) accessor.invoke(profile); checks++;
        }
        boolean current = !args[0].equals("1.19.0");
        char holder = args[0].equals("1.19.2") ? '\u058f' : current ? '\u0781' : '\u0560';
        if (!types[0].getName().equals("com.xuncorp.voxzen.data." + holder)) {
            throw new AssertionError("Wrong profile for " + args[0]);
        }
        checks++;
        Field holders = Class.forName("com.example.netease.host.HostSqlBridge").getDeclaredField("DB_HOLDER_CANDIDATES");
        holders.setAccessible(true);
        if (!Arrays.asList((String[]) holders.get(null)).contains(types[0].getName())) throw new AssertionError("DB holder not wired");
        checks++;
        Class<?> mark = Class.forName("androidx.compose.ui." + (current ? '\u0b71' : '\u1016'), false, types[0].getClassLoader());
        mark.getConstructor(String.class); checks++;
        Method predicate = Class.forName("com.example.netease.host.HostTrackListGate").getMethod("isReadableMarkClass", String.class);
        if (!Boolean.TRUE.equals(predicate.invoke(null, mark.getName()))) throw new AssertionError("Readable protection not wired");
        checks++;
        if (!types[1].getMethod("\u0528").getGenericReturnType().getTypeName().contains("java.util.List<com.xuncorp.voxzen.data.entity.Track>")) {
            throw new AssertionError("Track flow no longer contains List<Track>");
        }
        checks++;
        System.out.println("HOST_BUSINESS_ABI_RESULT=" + checks + " PASS " + args[0]);
    }
}
