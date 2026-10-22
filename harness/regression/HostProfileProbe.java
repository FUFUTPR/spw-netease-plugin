package com.xuncorp.voxzen.data;

/** A same-named unrelated host class must not be initialized or select the 1.19 path. */
public final class HostProfileProbe {
    public static void main(String[] args) throws Exception {
        Class<?> gate=Class.forName("com.example.netease.host.HostTrackListGate");
        var method=gate.getDeclaredMethod("install119"); method.setAccessible(true);
        if (!Boolean.FALSE.equals(method.invoke(null))) { throw new AssertionError("wrong host profile selected"); }
        System.out.println("HOST_PROFILE_RESULT=1 PASS");
    }
}

class ՠ {
    static { if (System.getProperty("netease.fixture.allowInitialization")==null) { throw new AssertionError("unrelated class initialized"); } }
    public static String Ϳ() { return "unrelated"; }
}
