#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include <dlfcn.h>

JNIEXPORT jlong JNICALL Java_com_example_netease_ui_LinuxVerifySurface_windowId
  (JNIEnv *env, jclass type, jobject canvas, jstring library) {
    (void)type;
    const char *name = (*env)->GetStringUTFChars(env, library, NULL);
    void *module = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, library, name);
    if (!module) return 0;
    jboolean (*get_awt)(JNIEnv*, JAWT*) = dlsym(module, "JAWT_GetAWT");
    JAWT awt = { .version = JAWT_VERSION_1_4 };
    jlong result = 0;
    if (get_awt && get_awt(env, &awt)) {
        JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, canvas);
        if (surface) {
            jint lock = surface->Lock(surface);
            if (!(lock & JAWT_LOCK_ERROR)) {
                JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
                if (info && info->platformInfo) result = (jlong)((JAWT_X11DrawingSurfaceInfo*)info->platformInfo)->drawable;
                if (info) surface->FreeDrawingSurfaceInfo(info);
                surface->Unlock(surface);
            }
            awt.FreeDrawingSurface(surface);
        }
    }
    dlclose(module);
    return result;
}
