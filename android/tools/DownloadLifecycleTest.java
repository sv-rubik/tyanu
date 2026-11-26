package ru.sveta.tyanu;

import java.util.*;

/** Checks observable service effects across pause, polling, resume and UI unbind. */
public final class DownloadLifecycleTest {
    static final class Host implements DownloadLifecycle.Host {
        final List<String> events=new ArrayList<>();
        boolean notification,started;
        int updates;
        public void startForeground(String text){notification=true;started=true;events.add("start");}
        public void updateNotification(String text){if(!notification)throw new AssertionError("Update resurrected an idle notification");updates++;events.add("update");}
        public void removeNotification(){notification=false;events.add("remove");}
        public void releaseStartedService(){started=false;events.add("release");}
    }
    static int checks;
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
    public static void main(String[] args){
        Host host=new Host();DownloadLifecycle runtime=new DownloadLifecycle(host);
        runtime.begin("Starting");
        runtime.update(false,"Empty list");
        check(!host.notification&&!host.started,"empty startup removes the notification and releases the service");
        check(host.events.equals(Arrays.asList("start","remove","release")),"startup ends foreground ownership before releasing service lifetime");
        for(int i=0;i<10;i++)runtime.update(false,"All paused");
        check(!host.notification&&host.updates==0,"repeated idle polls never recreate the notification");
        runtime.begin("Resume from visible Activity");
        check(host.notification&&host.started,"resume starts foreground before work may continue after UI unbind");
        runtime.update(false,1,"Old idle poll while resume is queued");
        check(host.notification&&host.started,"queued resume cannot be interrupted by an older empty snapshot");
        runtime.update(true,"Downloading");
        check(host.notification&&host.started&&host.updates==1,"active download keeps foreground ownership");
        runtime.update(true,"Waiting for Wi-Fi");
        check(host.notification&&host.started,"unpaused task can wait for Wi-Fi without losing its automatic continuation");
        runtime.update(false,"Pause all action");
        check(!host.notification&&!host.started,"pause-all action removes notification and releases the started service");
        for(int i=0;i<3;i++)runtime.update(false,"Paused UI detail read");
        check(!host.notification&&host.updates==2,"viewing paused task details does not repost progress notification");
        runtime.begin("Resume again");runtime.update(true,"Seeding");
        check(host.notification&&host.started,"explicit resume also preserves background seeding");
        runtime.update(false,"Last task removed");
        check(!host.notification&&!host.started,"removing the final task releases foreground and service ownership");
        runtime.update(false,1,"Queued detail read while already idle");
        check(!host.notification&&!host.started,"queued reads do not create a new foreground notification");
        runtime.begin("Started transfer");runtime.destroy();
        check(!host.notification,"service destruction removes the foreground notification");
        System.out.println("ALL "+checks+" DOWNLOAD LIFECYCLE CHECKS PASSED");
    }
}
