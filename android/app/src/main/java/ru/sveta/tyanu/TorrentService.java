package ru.sveta.tyanu;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public final class TorrentService extends Service {
    private static final String CHANNEL_ID="download_progress";
    private static CompletableFuture<Void> lastShutdown=CompletableFuture.completedFuture(null);
    private final CompletableFuture<Void> shutdownComplete=new CompletableFuture<>();
    public final class LocalBinder extends Binder { TorrentService service() { return TorrentService.this; } }
    public interface Work { void run(TorrentEngine engine) throws Exception; }
    private final LocalBinder binder=new LocalBinder();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TorrentEngine engine;
    private PowerManager.WakeLock wake;
    public volatile List<TorrentEngine.Item> items=Collections.emptyList();
    public volatile String error="";
    public volatile boolean ready;
    private volatile Runnable observer;
    private volatile boolean stopping;
    private boolean timedOut;
    private volatile long generation;
    private final java.util.concurrent.atomic.AtomicInteger queuedCommands=new java.util.concurrent.atomic.AtomicInteger();
    private DownloadLifecycle lifecycle;
    public void observe(Runnable r){observer=r;}
    public void execute(Work work, Runnable done, java.util.function.Consumer<String> fail) {
        generation++;
        queuedCommands.incrementAndGet();
        worker.execute(()->{
            boolean success=false;
            try {
                if(engine==null)throw new IllegalStateException(error.isEmpty()?"Движок ещё запускается":error);
                work.run(engine);success=true;
            } catch(Exception e){
                String message=e.getMessage()==null?e.toString():e.getMessage();
                if(engine==null)main.post(()->{if(!stopping)lifecycle.idle();});
                main.post(()->{if(fail!=null)fail.accept(message);});
            } finally {
                queuedCommands.decrementAndGet();
                refreshState();
            }
            if(success)main.post(()->{if(done!=null)done.run();});
        });
    }
    // Called synchronously by a visible Activity before enqueueing an add/resume action.
    public void beginTransfer() {
        generation++;
        timedOut=false;
        lifecycle.begin("Подготовка загрузки…");
    }
    private void changed(){Runnable r=observer;if(r!=null)r.run();}
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        // A new channel also fixes devices that already registered the original badged channel.
        NotificationChannel previous=nm.getNotificationChannel("downloads");
        int importance=previous==null?NotificationManager.IMPORTANCE_LOW:previous.getImportance();
        NotificationChannel progress=new NotificationChannel(CHANNEL_ID,"Ход загрузок",importance);
        progress.setDescription("Скорость загрузки и отдачи, управление паузой. Без счётчика на иконке.");
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);
        if(previous!=null)nm.deleteNotificationChannel("downloads");
        lifecycle=new DownloadLifecycle(new DownloadLifecycle.Host(){
            public void startForeground(String text){
                // Keep the service started when the Activity later unbinds.
                startService(new Intent(TorrentService.this,TorrentService.class).setAction("keep_running"));
                Notification n=notification(text);
                if(Build.VERSION.SDK_INT>=29)TorrentService.this.startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else TorrentService.this.startForeground(1,n);
            }
            public void updateNotification(String text){nm.notify(1,notification(text));}
            public void removeNotification(){
                stopForeground(STOP_FOREGROUND_REMOVE);
                nm.cancel(1);
                if(wake!=null&&wake.isHeld())wake.release();
            }
            public void releaseStartedService(){stopSelf();}
        });
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"tyanu:transfer");
        final CompletableFuture<Void> previousShutdown;
        synchronized(TorrentService.class){previousShutdown=lastShutdown;lastShutdown=shutdownComplete;}
        worker.execute(()->{
            try {
                // A quick reopen must wait for the old native session to finish saving the same files.
                previousShutdown.join();
                SharedPreferences p=getSharedPreferences("settings",0);
                engine=new TorrentEngine(new File(getFilesDir(),"session"),p.getInt("down",0),p.getInt("up",0),networkAllowed());
                ready=true;refreshState();
            } catch(Throwable t){error="Не удалось запустить движок: "+t.getMessage();main.post(()->{if(!stopping)lifecycle.idle();});}
            main.post(this::changed);
        });
        worker.scheduleWithFixedDelay(this::refreshState,1,2,TimeUnit.SECONDS);
    }
    private void refreshState(){
        if(engine==null||stopping)return;
        final long revision=generation;
        try {
            boolean allowed=networkAllowed();
            engine.network(allowed);items=engine.snapshot();error=engine.sessionError().isEmpty()?"":"Ошибка сети: "+engine.sessionError();
            int runnable=0;long down=0,up=0;
            for(TorrentEngine.Item i:items){if(!i.paused&&i.error.isEmpty())runnable++;down+=i.down;up+=i.up;}
            final boolean pending=runnable>0&&engine.sessionError().isEmpty();
            final boolean transferring=pending&&allowed;
            final String text=!allowed?"Ожидание Wi-Fi — загрузка продолжится при подключении":"↓ "+MainActivity.bytes(down)+"/с    ↑ "+MainActivity.bytes(up)+"/с";
            main.post(()->{
                if(stopping||revision!=generation)return;
                if(transferring&&!timedOut){if(!wake.isHeld())wake.acquire(10*60*1000L);}
                else if(wake.isHeld())wake.release();
                lifecycle.update(pending&&!timedOut,timedOut?0:queuedCommands.get(),text);
                changed();
            });
        }catch(Exception e){error=e.getMessage();main.post(()->{if(!stopping&&revision==generation){lifecycle.idle();changed();}});}
    }
    private boolean networkAllowed(){
        if(!getSharedPreferences("settings",0).getBoolean("wifi",true))return true;
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        NetworkCapabilities c=cm.getNetworkCapabilities(cm.getActiveNetwork());
        return c!=null&&(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,TorrentService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle("Тяну").setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Пауза всем",pause).build())
            .build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||!"keep_running".equals(intent.getAction()))beginTransfer();
        if(intent!=null&&"pause".equals(intent.getAction()))execute(e->{for(TorrentEngine.Item i:e.snapshot())e.pause(i.hash,true);},null,null);
        else if(intent!=null&&"stop".equals(intent.getAction()))execute(e->{for(TorrentEngine.Item i:e.snapshot())e.pause(i.hash,true);},null,null);
        else if(ready)worker.execute(this::refreshState);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return binder;}
    @Override public void onTimeout(int startId,int fgsType){
        timedOut=true;
        lifecycle.idle();
        execute(e->{for(TorrentEngine.Item i:e.snapshot())e.pause(i.hash,true);},null,null);
    }
    @Override public void onDestroy(){
        stopping=true;observer=null;
        lifecycle.destroy();
        worker.execute(()->{try{if(engine!=null)engine.close();}catch(Exception ignored){}finally{try{if(wake.isHeld())wake.release();}finally{shutdownComplete.complete(null);}}});
        worker.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
