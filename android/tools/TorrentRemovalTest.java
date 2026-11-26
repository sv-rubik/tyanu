package ru.sveta.tyanu;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.libtorrent4j.*;

/** Real native removal with isolated descriptors, payloads and unrelated sentinel files. */
public final class TorrentRemovalTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static final class Sources implements TorrentEngine.SourceAccess {
        int reads,deletes;boolean denyDelete;
        public byte[] read(String ref)throws IOException{reads++;return Files.readAllBytes(Paths.get(URI.create(ref)));}
        public void delete(String ref)throws IOException{deletes++;if(denyDelete)throw new IOException("Source provider denied deletion");Files.delete(Paths.get(URI.create(ref)));}
    }
    static final class Fixture {
        final File root,state,download,source,payload,other;
        final byte[] content,data;
        Fixture(File root)throws Exception{
            this.root=root;root.mkdirs();state=new File(root,"state");download=new File(root,"downloads");source=new File(root,"original.torrent");
            content=new byte[65536];new Random(42).nextBytes(content);
            File seed=new File(root,"seed/bundle");seed.mkdirs();Files.write(new File(seed,"payload.bin").toPath(),content);
            data=new TorrentBuilder().path(seed).pieceSize(16384).flags(TorrentBuilder.V1_ONLY).generate().entry().bencode();Files.write(source.toPath(),data);
            File destination=new File(download,"bundle");destination.mkdirs();payload=new File(destination,"payload.bin");Files.write(payload.toPath(),content);
            other=new File(destination,"unrelated.txt");Files.write(other.toPath(),"keep me".getBytes("UTF-8"));
        }
        String add(TorrentEngine e)throws Exception{return e.addTorrent(data,download,true,null,source.toURI().toString());}
        boolean unchanged()throws IOException{return payload.isFile()&&Arrays.equals(content,Files.readAllBytes(payload.toPath()));}
    }
    interface Checked {void run()throws Exception;}
    static void fails(Checked task,String message)throws Exception{try{task.run();throw new AssertionError(message+" (unexpected success)");}catch(IOException expected){check(true,message);}}
    public static void main(String[] args)throws Exception{
        File base=new File(args[0]);base.mkdirs();
        Sources sources=new Sources();
        Fixture first=new Fixture(new File(base,"source-only"));
        try(TorrentEngine e=new TorrentEngine(first.state,0,0,false)){
            String hash=first.add(e);check(e.snapshot().get(0).sourceReference.equals(first.source.toURI().toString()),"original descriptor reference is retained at import");
            e.remove(hash,TorrentEngine.Removal.SOURCE_FILE,sources);
            check(!first.source.exists(),"default source-file option deletes original .torrent");
            check(first.unchanged(),"default source-file option preserves all downloaded bytes");
            check(first.other.isFile(),"default source-file option preserves unrelated files");
            check(e.snapshot().isEmpty(),"default source-file option removes the row");
        }
        try(TorrentEngine e=new TorrentEngine(first.state,0,0,false)){check(e.snapshot().isEmpty(),"removed row does not return after engine restart");}
        Fixture second=new Fixture(new File(base,"list-only"));
        try(TorrentEngine e=new TorrentEngine(second.state,0,0,false)){
            String hash=second.add(e);Sources access=new Sources();e.remove(hash,TorrentEngine.Removal.LIST_ONLY,access);
            check(second.source.isFile()&&second.unchanged(),"list-only option keeps descriptor and payload");
            check(access.reads==0&&access.deletes==0,"list-only option does not request external source access");
        }
        Fixture third=new Fixture(new File(base,"everything"));
        try(TorrentEngine e=new TorrentEngine(third.state,0,0,false)){
            String hash=third.add(e);e.remove(hash,TorrentEngine.Removal.EVERYTHING,sources);
            check(!third.payload.exists(),"native delete-files operation removes downloaded payload");
            check(!third.source.exists()&&e.snapshot().isEmpty(),"everything option deletes original descriptor and row");
            check(third.other.isFile(),"everything option preserves unrelated file in the same torrent subfolder");
            check(third.download.isDirectory(),"everything option does not recursively delete shared downloads folder");
        }
        Fixture legacy=new Fixture(new File(base,"legacy"));String legacyHash;
        try(TorrentEngine e=new TorrentEngine(legacy.state,0,0,false)){
            legacyHash=e.addTorrent(legacy.data,legacy.download,true,null);
            fails(()->e.remove(legacyHash,TorrentEngine.Removal.SOURCE_FILE,sources),"legacy entry requires explicit source selection");
            check(legacy.source.isFile()&&legacy.unchanged()&&e.snapshot().size()==1,"missing source reference never deletes data or row");
            e.sourceReference(legacyHash,legacy.source.toURI().toString(),legacy.data);
        }
        try(TorrentEngine e=new TorrentEngine(legacy.state,0,0,false)){
            check(e.snapshot().get(0).sourceReference.equals(legacy.source.toURI().toString()),"selected original descriptor reference survives restart");
            e.remove(legacyHash,TorrentEngine.Removal.SOURCE_FILE,sources);check(!legacy.source.exists()&&legacy.unchanged(),"legacy task can safely remove its selected descriptor");
        }
        Fixture changed=new Fixture(new File(base,"changed-source"));Fixture different=new Fixture(new File(base,"different-source"));
        File differentSeed=new File(different.root,"seed/bundle/payload.bin");Files.write(differentSeed.toPath(),new byte[65536]);
        byte[] wrong=new TorrentBuilder().path(differentSeed.getParentFile()).pieceSize(16384).flags(TorrentBuilder.V1_ONLY).generate().entry().bencode();
        try(TorrentEngine e=new TorrentEngine(changed.state,0,0,false)){
            String hash=changed.add(e);Files.write(changed.source.toPath(),wrong);Sources access=new Sources();
            fails(()->e.remove(hash,TorrentEngine.Removal.EVERYTHING,access),"replaced source descriptor with another infohash is rejected");
            check(access.deletes==0&&changed.source.isFile()&&changed.unchanged()&&e.snapshot().size()==1,"wrong-source rejection has no destructive side effects");
            fails(()->e.sourceReference(hash,different.source.toURI().toString(),wrong),"explicit source selection checks the torrent infohash");
        }
        Fixture denied=new Fixture(new File(base,"source-denied"));
        try(TorrentEngine e=new TorrentEngine(denied.state,0,0,false)){
            String hash=denied.add(e);Sources access=new Sources();access.denyDelete=true;
            fails(()->e.remove(hash,TorrentEngine.Removal.SOURCE_FILE,access),"provider deletion failure is surfaced");
            check(denied.source.isFile()&&denied.unchanged()&&e.snapshot().size()==1,"provider failure preserves descriptor, row and downloaded content");
        }
        Fixture overlap=new Fixture(new File(base,"overlap"));File otherSource=new File(overlap.root,"other.torrent");Files.write(otherSource.toPath(),wrong);
        try(TorrentEngine e=new TorrentEngine(overlap.state,0,0,false)){
            String hash=overlap.add(e);e.addTorrent(wrong,overlap.download,true,null,otherSource.toURI().toString());
            fails(()->e.remove(hash,TorrentEngine.Removal.EVERYTHING,sources),"payload paths shared with another listed torrent are protected");
            check(overlap.unchanged()&&overlap.source.isFile()&&e.snapshot().size()==2,"overlap protection keeps both torrents and their shared payload");
        }
        Fixture retry=new Fixture(new File(base,"payload-denied"));
        try(TorrentEngine e=new TorrentEngine(retry.state,0,0,false)){
            String hash=retry.add(e);Path folder=retry.payload.getParentFile().toPath();Files.setPosixFilePermissions(folder,PosixFilePermissions.fromString("r-x------"));
            try{
                fails(()->e.remove(hash,TorrentEngine.Removal.EVERYTHING,sources),"native payload-delete failure is reported");
                check(retry.source.isFile()&&e.snapshot().size()==1,"failed native deletion retains original .torrent and the row");
            }finally{Files.setPosixFilePermissions(folder,PosixFilePermissions.fromString("rwx------"));}
            e.remove(hash,TorrentEngine.Removal.EVERYTHING,sources);
            check(!retry.payload.exists()&&!retry.source.exists()&&e.snapshot().isEmpty(),"native payload deletion can be retried after restoring access");
        }
        Fixture magnet=new Fixture(new File(base,"magnet"));
        try(TorrentEngine e=new TorrentEngine(magnet.state,0,0,false)){
            String hash=new TorrentInfo(magnet.data).infoHashes().getBest().toHex();String added=e.addMagnet("magnet:?xt=urn:btih:"+hash,magnet.download,true);
            Sources access=new Sources();e.remove(added,TorrentEngine.Removal.SOURCE_FILE,access);
            check(access.reads==0&&access.deletes==0&&magnet.unchanged(),"magnet without original .torrent removes only the row in default mode");
        }
        System.out.println("ALL "+checks+" TORRENT REMOVAL CHECKS PASSED");
    }
}
