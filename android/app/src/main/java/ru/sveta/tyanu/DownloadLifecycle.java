package ru.sveta.tyanu;

/** Foreground ownership, called only on the Android main thread. */
final class DownloadLifecycle {
    interface Host {
        void startForeground(String text);
        void updateNotification(String text);
        void removeNotification();
        void releaseStartedService();
    }
    private final Host host;
    private boolean foreground;
    DownloadLifecycle(Host host) { this.host=host; }
    void begin(String text) {
        if(!foreground) {
            host.startForeground(text);
            foreground=true;
        }
    }
    void update(boolean hasRunnableTasks,String text) {
        update(hasRunnableTasks,0,text);
    }
    void update(boolean hasRunnableTasks,int queuedCommands,String text) {
        // A queued resume/add must run before an older idle poll can tear down foreground.
        if(!hasRunnableTasks&&queuedCommands>0)return;
        if(hasRunnableTasks) {
            if(foreground)host.updateNotification(text);
            else begin(text);
        } else idle();
    }
    void idle() {
        if(foreground) {
            host.removeNotification();
            foreground=false;
        }
        // A binding may keep the in-app view alive; an idle service must not outlive it.
        host.releaseStartedService();
    }
    void destroy() {
        if(foreground)host.removeNotification();
        foreground=false;
    }
}
