import ru.sveta.tyanu.TorrentEngine;
import org.libtorrent4j.*;
import org.libtorrent4j.swig.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the shipped engine against native libtorrent; requires no remote peers. */
public final class CoreStateTest {
    static int count;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    static TorrentHandle handle(TorrentEngine engine,String hash)throws Exception{
        java.lang.reflect.Field f=TorrentEngine.class.getDeclaredField("manager");f.setAccessible(true);return ((SessionManager)f.get(engine)).find(Sha1Hash.parseHex(hash));
    }
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);root.mkdirs();File seed=new File(root,"seed/bundle");seed.mkdirs();
        byte[] bytes=new byte[262144];new Random(42).nextBytes(bytes);
        Files.write(new File(seed,"keep.bin").toPath(),bytes);Files.write(new File(seed,"skip.bin").toPath(),new byte[65536]);
        byte[] torrent=new TorrentBuilder().path(seed).pieceSize(16384).flags(TorrentBuilder.V1_ONLY).generate().entry().bencode();
        File dest=new File(root,"download"),state=new File(root,"state");new File(dest,"bundle").mkdirs();Files.write(new File(dest,"bundle/keep.bin").toPath(),bytes);
        String hash;
        try(TorrentEngine e=new TorrentEngine(state,0,0,false)){
            hash=e.addTorrent(torrent,dest,true,new boolean[]{true,false});
            check(e.snapshot().size()==1,"valid torrent is imported");
            check(e.snapshot().get(0).name.equals("bundle"),"metadata name is read");
            check(handle(e,hash).getFlags().and_(TorrentFlags.PAUSED).non_zero(),"pause reaches the native engine");
            check(!handle(e,hash).getFlags().and_(TorrentFlags.AUTO_MANAGED).non_zero(),"automatic resume is disabled");
            check(e.content(hash).size()==2&&!e.content(hash).get(1).wanted,"file selection reaches native priorities");
            try{e.addTorrent(torrent,dest,false,null);throw new AssertionError("duplicate accepted");}catch(IllegalArgumentException expected){check(true,"duplicate is rejected");}
            try{e.addMagnet("not-a-magnet",dest,false);throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){check(true,"invalid magnet is rejected");}
            try{e.addTorrent(new byte[]{1,2,3},dest,false,null);throw new AssertionError("corrupt accepted");}catch(RuntimeException expected){check(true,"corrupt torrent is rejected");}
            try{e.addTorrent(torrent,new File(root,"zero"),false,new boolean[]{false,false});throw new AssertionError("no-selection accepted");}catch(IllegalArgumentException expected){check(true,"empty selection is rejected");}
            e.pause(hash,false);check(!handle(e,hash).getFlags().and_(TorrentFlags.PAUSED).non_zero(),"resume reaches native engine");
            e.pause(hash,true);e.limits(123456,234567);e.checkpoint();
        }
        try(TorrentEngine e=new TorrentEngine(state,0,0,false)){
            check(e.snapshot().size()==1&&e.snapshot().get(0).paused,"session and pause restore after process restart");
            check(!e.content(hash).get(1).wanted,"file selection restores after restart");
            check(Files.size(new File(state,hash+".resume").toPath())>0,"native resume data is persisted");
            e.network(true);check(handle(e,hash).getFlags().and_(TorrentFlags.PAUSED).non_zero(),"network reconnect preserves manual pause");
            e.pause(hash,false);e.recheck(hash);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(!e.snapshot().get(0).finished&&System.nanoTime()<deadline)Thread.sleep(100);
            check(e.snapshot().get(0).finished&&e.snapshot().get(0).done==bytes.length,"native hash recheck validates existing selected bytes");
            String magnet=e.magnet(hash);check(magnet.startsWith("magnet:?")&&magnet.contains(hash),"magnet export includes correct infohash");
            e.remove(hash);check(e.snapshot().isEmpty(),"remove clears session");
            check(Arrays.equals(bytes,Files.readAllBytes(new File(dest,"bundle/keep.bin").toPath())),"remove leaves downloaded bytes intact");
            check(!new File(state,hash+".properties").exists(),"remove clears saved entry");
            String added=e.addMagnet(magnet,new File(root,"magnet"),true);check(added.equals(hash),"valid magnet is imported");
            check(e.snapshot().get(0).paused,"magnet honors initial pause");
        }
        System.out.println("ALL "+count+" NATIVE STATE CHECKS PASSED; libtorrent "+LibTorrent.version());
    }
}
