#define _POSIX_C_SOURCE 200809L
#include <gtk/gtk.h>
#include <gtk/gtkx.h>
#include <gdk/gdkkeysyms.h>
#include <webkit2/webkit2.h>
#include <json-glib/json-glib.h>
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <glib-unix.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static GtkWidget *plug;
static WebKitWebView *view;
static WebKitCookieManager *cookies;
static Display *display;
static Window parent;
static char *session, *uri;
static int pending_cookies, xerror;
static gboolean test_local, started;
static Window child;
static gboolean navigation_failed;
static gboolean visible_desired;
static gboolean shown_once;
static int target_width=1, target_height=1;
static guint restore_size_source;
static double page_scale=1.0;

static void say(const char *line) { printf("verify-host: %s\n", line); fflush(stdout); }
static void fail(const char *reason) { char *line=g_strconcat("fail ",reason,NULL); say(line); g_free(line); }
static gboolean quit(gpointer unused) { (void)unused; gtk_main_quit(); return G_SOURCE_REMOVE; }
static int x_error(Display *d, XErrorEvent *event) { (void)d; (void)event; xerror=1; return 0; }
static void xembed(long message,long detail) {
    XEvent event={0}; event.xclient.type=ClientMessage; event.xclient.window=child;
    event.xclient.message_type=XInternAtom(display,"_XEMBED",False); event.xclient.format=32;
    event.xclient.data.l[0]=CurrentTime; event.xclient.data.l[1]=message; event.xclient.data.l[2]=detail;
    if (message==0) event.xclient.data.l[3]=(long)parent;
    XSendEvent(display,child,False,NoEventMask,&event); XFlush(display);
}
static gboolean focus_click(GtkWidget *widget,GdkEventButton *event,gpointer unused) {
    (void)widget; (void)event; (void)unused;
    XSetInputFocus(display,child,RevertToParent,CurrentTime); xembed(1,0); xembed(4,0); return FALSE;
}
static gboolean key_press(GtkWidget *widget,GdkEventKey *event,gpointer unused) {
    (void)widget; (void)unused;
    if (event->keyval==GDK_KEY_Escape) { say("escape-requested"); return TRUE; }
    return FALSE;
}
static gboolean allowed(const char *address) {
    GUri *parsed=g_uri_parse(address,G_URI_FLAGS_NONE,NULL);
    if (!parsed) return FALSE;
    const char *scheme=g_uri_get_scheme(parsed), *host=g_uri_get_host(parsed);
    gboolean ok=scheme && host && (!strcmp(scheme,"https") || (test_local && !strcmp(scheme,"http") && !strcmp(host,"127.0.0.1")));
    g_uri_unref(parsed); return ok;
}
static gboolean owns_parent(unsigned long pid) {
    Window cursor=parent;
    Atom atom=XInternAtom(display,"_NET_WM_PID",False);
    for (int depth=0; cursor && depth<32; depth++) {
        Atom type; int format; unsigned long count, after; unsigned char *data=NULL;
        xerror=0;
        int rc=XGetWindowProperty(display,cursor,atom,0,1,False,XA_CARDINAL,&type,&format,&count,&after,&data);
        XSync(display,False);
        gboolean same=rc==Success && !xerror && data && format==32 && count==1 && *((unsigned long*)data)==pid;
        if (data) XFree(data);
        if (same) return TRUE;
        Window root, up, *children=NULL; unsigned int n=0;
        if (!XQueryTree(display,cursor,&root,&up,&children,&n)) return FALSE;
        if (children) XFree(children);
        if (cursor==up) break;
        cursor=up;
    }
    return FALSE;
}
static gboolean parent_watch(gpointer unused) {
    (void)unused; XWindowAttributes attr; xerror=0;
    Status status=XGetWindowAttributes(display,parent,&attr); XSync(display,False);
    if (!status || xerror) { say("parent-gone exit"); gtk_main_quit(); return G_SOURCE_REMOVE; }
    return G_SOURCE_CONTINUE;
}
static void dump_finished(GObject *object, GAsyncResult *result, gpointer unused) {
    (void)unused; GError *error=NULL;
    GList *list=webkit_cookie_manager_get_cookies_finish(WEBKIT_COOKIE_MANAGER(object),result,&error);
    if (error) { say("cookie-dump-failed WebKitCookieManager"); g_error_free(error); return; }
    GString *line=g_string_new("cookies-session "); g_string_append(line,session); g_string_append_c(line,' ');
    gboolean first=TRUE;
    for (GList *it=list; it; it=it->next) {
        SoupCookie *cookie=it->data;
        const char *name=soup_cookie_get_name(cookie), *value=soup_cookie_get_value(cookie);
        if (!name || !value || strpbrk(name,"\r\n;") || strpbrk(value,"\r\n;")) continue;
        if (!first) g_string_append(line,"; ");
        g_string_append_printf(line,"%s=%s",name,value); first=FALSE;
    }
    if (!first) say(line->str);
    g_string_free(line,TRUE); g_list_free_full(list,(GDestroyNotify)soup_cookie_free);
}
static void dump(void) { webkit_cookie_manager_get_cookies(cookies,"https://music.163.com",NULL,dump_finished,NULL); }
static void load_changed(WebKitWebView *web, WebKitLoadEvent event, gpointer unused) {
    (void)web; (void)unused;
    if (event==WEBKIT_LOAD_STARTED) { navigation_failed=FALSE; say("nav-start"); }
    if (event==WEBKIT_LOAD_FINISHED) { if (!navigation_failed) say("nav-done true None"); dump(); }
}
static gboolean load_failed(WebKitWebView *web, WebKitLoadEvent event, const char *address, GError *error, gpointer unused) {
    (void)web; (void)event; (void)address; (void)error; (void)unused;
    navigation_failed=TRUE; say("nav-done false WebKitLoadFailed"); return TRUE;
}
static gboolean policy(WebKitWebView *web, WebKitPolicyDecision *decision, WebKitPolicyDecisionType type, gpointer unused) {
    (void)web; (void)unused;
    if (type==WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION || type==WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
        WebKitNavigationAction *action=webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(decision));
        const char *target=webkit_uri_request_get_uri(webkit_navigation_action_get_request(action));
        if (!allowed(target) || type==WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
            webkit_policy_decision_ignore(decision);
            if (allowed(target)) webkit_web_view_load_uri(view,target);
            return TRUE;
        }
    }
    return FALSE;
}
static void terminated(WebKitWebView *web, WebKitWebProcessTerminationReason reason, gpointer unused) {
    (void)web; (void)reason; (void)unused; fail("web-process-terminated"); gtk_main_quit();
}
static gboolean close_view(WebKitWebView *web, gpointer unused) { (void)web; (void)unused; say("window-close-requested"); gtk_main_quit(); return TRUE; }
static void navigate(void) {
    if (started) return;
    started=TRUE; webkit_web_view_load_uri(view,uri); say("ready");
}
static void seeded(GObject *object, GAsyncResult *result, gpointer unused) {
    (void)unused; GError *error=NULL;
    webkit_cookie_manager_add_cookie_finish(WEBKIT_COOKIE_MANAGER(object),result,&error);
    if (error) { say("cookie-seed-failed WebKitCookieManager"); g_error_free(error); }
    if (--pending_cookies==0) navigate();
}
static void resize_surface(int width,int height) {
    gtk_widget_set_size_request(plug,width,height);
    gtk_window_resize(GTK_WINDOW(plug),width,height);
    if (gtk_widget_get_window(plug)) gdk_window_move_resize(gtk_widget_get_window(plug),0,0,width,height);
    XMoveResizeWindow(display,child,0,0,(unsigned int)width,(unsigned int)height); XFlush(display);
}
static gboolean restore_size(gpointer unused) {
    (void)unused; restore_size_source=0;
    resize_surface(target_width,target_height); return G_SOURCE_REMOVE;
}
static gboolean command(GIOChannel *channel, GIOCondition condition, gpointer unused) {
    (void)unused;
    if (condition & (G_IO_HUP|G_IO_ERR)) { say("stdin-eof exit"); gtk_main_quit(); return G_SOURCE_REMOVE; }
    char *line=NULL; gsize length;
    GIOStatus status=g_io_channel_read_line(channel,&line,&length,NULL,NULL);
    if (status==G_IO_STATUS_EOF || status==G_IO_STATUS_ERROR) { g_free(line); gtk_main_quit(); return G_SOURCE_REMOVE; }
    if (!line) return G_SOURCE_CONTINUE;
    g_strchomp(line);
    if (length>131072) { g_free(line); fail("command-too-long"); gtk_main_quit(); return G_SOURCE_REMOVE; }
    int x,y,w,h; char zoom_text[32]={0};
    int fields=sscanf(line,"rect %d %d %d %d %31s",&x,&y,&w,&h,zoom_text);
    if (fields>=4 && w>0 && h>0 && w<=16384 && h<=16384) {
        if (fields==5) {
            char *end; double zoom=g_ascii_strtod(zoom_text,&end);
            if (!*end && zoom>=0.5 && zoom<=4 && zoom!=page_scale) { page_scale=zoom; webkit_web_view_set_zoom_level(view,zoom); }
        }
        target_width=w; target_height=h; resize_surface(w,h);
        char *ack=g_strdup_printf("rect-ack w=%d h=%d",w,h); say(ack); g_free(ack);
    } else if (!strcmp(line,"show")) {
        gboolean restoring=!visible_desired && shown_once;
        visible_desired=TRUE; shown_once=TRUE;
        gtk_widget_show_all(plug); gdk_window_show_unraised(gtk_widget_get_window(plug)); XMapWindow(display,child);
        if (restoring && target_width>1) {
            /* A foreign HiDPI socket can expose an empty backing store after
               remapping. Two actual allocations refresh WebKit's drawing area
               without navigation, preserving captcha and form state. */
            if (restore_size_source) g_source_remove(restore_size_source);
            resize_surface(target_width-1,target_height);
            restore_size_source=g_timeout_add(50,restore_size,NULL);
        }
        gtk_widget_queue_draw(GTK_WIDGET(view));
        xembed(1,0); say("shown");
    } else if (!strcmp(line,"hide")) {
        visible_desired=FALSE; xembed(5,0); xembed(2,0);
        gtk_widget_hide(plug);
        Window focus; int revert; XGetInputFocus(display,&focus,&revert);
        if (focus==child) XSetInputFocus(display,parent,RevertToParent,CurrentTime);
        XFlush(display); say("hidden");
    }
    else if (!strcmp(line,"close")) { say("closing"); gtk_main_quit(); }
    else if (!strcmp(line,"info")) {
        XWindowAttributes attr; xerror=0; XGetWindowAttributes(display,child,&attr); XSync(display,False);
        char *line_info=g_strdup_printf("info viewable=%d w=%d h=%d",!xerror && attr.map_state==IsViewable,attr.width,attr.height);
        say(line_info); g_free(line_info);
    }
    else if (!strcmp(line,"dump")) dump();
    else if (!strcmp(line,"focus") && visible_desired) { XSetInputFocus(display,child,RevertToParent,CurrentTime); xembed(1,0); xembed(4,1); gtk_widget_grab_focus(GTK_WIDGET(view)); }
    g_free(line); return G_SOURCE_CONTINUE;
}
int main(int argc,char **argv) {
    unsigned long pid=0; const char *expected="";
    for (int i=1;i<argc;i++) {
        if (!strcmp(argv[i],"--selftest")) { printf("verify-host: selftest ok webkitgtk=%u.%u.%u\n",webkit_get_major_version(),webkit_get_minor_version(),webkit_get_micro_version()); return 0; }
        if (!strcmp(argv[i],"--parent-xid") && i+1<argc) parent=strtoul(argv[++i],NULL,10);
        else if (!strcmp(argv[i],"--pid") && i+1<argc) pid=strtoul(argv[++i],NULL,10);
        else if (!strcmp(argv[i],"--session") && i+1<argc) expected=argv[++i];
        else if (!strcmp(argv[i],"--test-local")) test_local=TRUE;
    }
    struct pollfd input={.fd=STDIN_FILENO,.events=POLLIN};
    if (poll(&input,1,15000)<=0) { fail("init-timeout"); return 2; }
    char *first=NULL; size_t capacity=0;
    ssize_t size=getline(&first,&capacity,stdin);
    if (size<6 || size>131072 || strncmp(first,"init ",5)) { free(first); fail("invalid-init"); return 2; }
    gsize decoded_size; guchar *decoded=g_base64_decode(first+5,&decoded_size); free(first);
    JsonParser *parser=json_parser_new(); GError *error=NULL;
    if (!json_parser_load_from_data(parser,(char*)decoded,decoded_size,&error)) { g_free(decoded); fail("invalid-init-json"); return 2; }
    g_free(decoded);
    JsonNode *root=json_parser_get_root(parser);
    if (!JSON_NODE_HOLDS_OBJECT(root)) { fail("invalid-init-object"); return 2; }
    JsonObject *object=json_node_get_object(root);
    if (!json_object_has_member(object,"session") || !json_object_has_member(object,"url") || !json_object_has_member(object,"cookies")) { fail("invalid-init-fields"); return 2; }
    session=g_strdup(json_object_get_string_member(object,"session")); uri=g_strdup(json_object_get_string_member(object,"url"));
    const char *header=json_object_get_string_member(object,"cookies");
    if (json_object_has_member(object,"scale")) {
        double zoom=json_object_get_double_member(object,"scale");
        if (zoom>=0.5 && zoom<=4) page_scale=zoom;
    }
    if (!session || strcmp(session,expected) || !uri || !allowed(uri) || !parent || !pid) { fail("invalid-session-or-parent"); return 2; }
    g_setenv("GDK_BACKEND","x11",TRUE);
    /* The foreign parent uses AWT physical pixels. Keep GTK at unit scale and
       apply the host density as page zoom, including fractional densities. */
    g_setenv("GDK_SCALE","1",TRUE); g_setenv("GDK_DPI_SCALE","1",TRUE);
    /* Cross-process XEmbed cannot rely on a Wayland compositor importing the
       web process's DMA-BUF surface (notably VMware and several GPU drivers). */
    g_setenv("WEBKIT_DISABLE_DMABUF_RENDERER","1",FALSE);
    if (!gtk_init_check(NULL,NULL)) { fail("x11-display-unavailable"); return 2; }
    display=GDK_DISPLAY_XDISPLAY(gdk_display_get_default()); if (!display) { fail("x11-display-unavailable"); return 2; }
    XSetErrorHandler(x_error);
    if (!owns_parent(pid)) { say("parent-invalid pid-mismatch"); return 2; }
    char *start=g_strdup_printf("start pid=%ld session=%s",(long)getpid(),session); say(start); g_free(start);
    WebKitWebContext *context=webkit_web_context_new_ephemeral();
    cookies=webkit_web_context_get_cookie_manager(context);
    plug=gtk_plug_new(parent); view=WEBKIT_WEB_VIEW(webkit_web_view_new_with_context(context));
    webkit_web_view_set_zoom_level(view,page_scale);
    g_signal_connect(plug,"destroy",G_CALLBACK(gtk_main_quit),NULL);
    gtk_container_add(GTK_CONTAINER(plug),GTK_WIDGET(view));
    webkit_settings_set_enable_developer_extras(webkit_web_view_get_settings(view),FALSE);
    g_signal_connect(view,"load-changed",G_CALLBACK(load_changed),NULL);
    g_signal_connect(view,"load-failed",G_CALLBACK(load_failed),NULL);
    g_signal_connect(view,"decide-policy",G_CALLBACK(policy),NULL);
    g_signal_connect(view,"web-process-terminated",G_CALLBACK(terminated),NULL);
    g_signal_connect(view,"close",G_CALLBACK(close_view),NULL);
    g_signal_connect(view,"button-press-event",G_CALLBACK(focus_click),NULL);
    g_signal_connect(view,"key-press-event",G_CALLBACK(key_press),NULL);
    gtk_widget_realize(plug);
    child=GDK_WINDOW_XID(gtk_widget_get_window(plug));
    xembed(0,0); say("adopted rect=0,0,1,1");
    GIOChannel *channel=g_io_channel_unix_new(STDIN_FILENO);
    g_io_add_watch(channel,G_IO_IN|G_IO_HUP|G_IO_ERR,command,NULL);
    g_timeout_add(200,parent_watch,NULL); g_unix_signal_add(SIGTERM,quit,NULL);
    gchar **pairs=g_strsplit(header ? header : "",";",256);
    for (int i=0;pairs[i];i++) {
        char *pair=g_strstrip(pairs[i]), *eq=strchr(pair,'=');
        if (!eq || eq==pair) continue;
        *eq=0; SoupCookie *cookie=soup_cookie_new(pair,eq+1,".music.163.com","/",-1);
        if (!cookie) continue;
        soup_cookie_set_secure(cookie,TRUE);
        soup_cookie_set_http_only(cookie,!strcmp(pair,"MUSIC_U") || !strcmp(pair,"MUSIC_A"));
        pending_cookies++; webkit_cookie_manager_add_cookie(cookies,cookie,NULL,seeded,NULL); soup_cookie_free(cookie);
    }
    g_strfreev(pairs); g_object_unref(parser);
    if (!pending_cookies) navigate();
    gtk_main();
    if (restore_size_source) g_source_remove(restore_size_source);
    g_signal_handlers_disconnect_by_func(plug,G_CALLBACK(gtk_main_quit),NULL);
    gtk_widget_destroy(plug); g_io_channel_unref(channel); g_object_unref(context);
    g_free(session); g_free(uri); return 0;
}
