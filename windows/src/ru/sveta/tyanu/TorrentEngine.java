package ru.sveta.tyanu;

import org.libtorrent4j.*;
import org.libtorrent4j.alerts.*;
import org.libtorrent4j.swig.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Native engine with independent, atomic per-torrent persistence. No Android dependencies. */
public final class TorrentEngine implements AutoCloseable {
    public static final class Item {
        public final String hash;
        public String name, path, source, error = "";
        public boolean paused;
        public long size, done, uploaded;
        public int down, up, peers, seeds;
        public float progress;
        public boolean metadata, finished;
        public String state = "Подключение…";
        Item(String hash) { this.hash = hash; }
    }
    public static final class Content {
        public int index;
        public String path;
        public long size, done;
        public boolean wanted;
    }
    private final File stateDir;
    private final SessionManager manager = new SessionManager();
    private SessionHandle session;
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile String sessionError = "";
    private boolean networkAllowed = true;

    public TorrentEngine(File stateDir, int downLimit, int upLimit) throws IOException {
        this(stateDir, downLimit, upLimit, true);
    }
    public TorrentEngine(File stateDir, int downLimit, int upLimit, boolean allowedNetwork) throws IOException {
        this.stateDir = stateDir;
        if (!stateDir.isDirectory() && !stateDir.mkdirs()) throw new IOException("Не удалось создать папку состояния");
        SettingsPack settings = new SettingsPack();
        settings.connectionsLimit(120).downloadRateLimit(downLimit).uploadRateLimit(upLimit);
        settings.setString(settings_pack.string_types.user_agent.swigValue(), "Tyanu/0.1.0 libtorrent/" + LibTorrent.version());
        settings.setEnableDht(true);
        settings.setEnableLsd(true);
        manager.addListener(new AlertListener() {
            public int[] types() { return null; }
            public void alert(Alert<?> alert) {
                if (alert instanceof SessionErrorAlert) sessionError = ((SessionErrorAlert) alert).error().getMessage();
                if (alert instanceof SaveResumeDataAlert) {
                    SaveResumeDataAlert a = (SaveResumeDataAlert) alert;
                    try {
                        String hash = a.handle().infoHash().toHex();
                        synchronized (TorrentEngine.this) {
                            if (items.containsKey(hash)) atomic(new File(stateDir, hash + ".resume"), AddTorrentParams.writeResumeDataBuf(a.params()));
                        }
                    } catch (Exception ignored) { /* Original source remains available for a disk recheck. */ }
                }
            }
        });
        SessionParams params = new SessionParams(settings);
        params.setPosixDiskIO();
        manager.start(params);
        session = new SessionHandle(manager.swig());
        networkAllowed = allowedNetwork;
        if (!networkAllowed) session.pause(); else session.resume();
        restore();
        timer.scheduleWithFixedDelay(() -> { try { checkpoint(); } catch (Exception ignored) {} }, 15, 15, TimeUnit.SECONDS);
    }

    public synchronized String addMagnet(String magnet, File folder, boolean paused) throws IOException {
        magnet = magnet.trim();
        if (magnet.length() > 65536 || !magnet.startsWith("magnet:?")) throw new IllegalArgumentException("Нужна magnet-ссылка");
        AddTorrentParams p = AddTorrentParams.parseMagnetUri(magnet);
        String hash = p.getInfoHashes().getBest().toHex();
        if (items.containsKey(hash)) throw new IllegalArgumentException("Этот торрент уже в списке");
        Item item = new Item(hash);
        item.name = p.getName().isEmpty() ? "Получение названия…" : p.getName();
        item.source = magnet;
        item.path = folder.getAbsolutePath();
        item.paused = paused;
        add(item, p);
        return hash;
    }

    public synchronized String addTorrent(byte[] data, File folder, boolean paused, boolean[] selected) throws IOException {
        if (data.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Файл .torrent слишком большой");
        TorrentInfo ti = new TorrentInfo(data);
        if (!ti.isValid()) throw new IllegalArgumentException("Повреждённый файл .torrent");
        String hash = ti.infoHashes().getBest().toHex();
        if (items.containsKey(hash)) throw new IllegalArgumentException("Этот торрент уже в списке");
        AddTorrentParams p = new AddTorrentParams();
        p.setTorrentInfo(ti);
        if (selected != null) {
            if (selected.length != ti.numFiles()) throw new IllegalArgumentException("Изменился список файлов");
            Priority[] priorities = new Priority[selected.length];
            boolean any = false;
            for (int i = 0; i < priorities.length; i++) { priorities[i] = selected[i] ? Priority.DEFAULT : Priority.IGNORE; any |= selected[i]; }
            if (!any) throw new IllegalArgumentException("Выбери хотя бы один файл");
            p.filePriorities(priorities);
        }
        atomic(new File(stateDir, hash + ".torrent"), data);
        Item item = new Item(hash);
        item.name = ti.name(); item.source = "file"; item.path = folder.getAbsolutePath(); item.paused = paused;
        add(item, p);
        return hash;
    }

    private void add(Item item, AddTorrentParams p) throws IOException {
        File folder = new File(item.path);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Не удалось создать папку: " + item.path);
        if (!folder.canWrite()) throw new IOException("Нет доступа к папке: " + item.path);
        p.setSavePath(item.path);
        torrent_flags_t flags = p.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv());
        if (item.paused) flags = flags.or_(TorrentFlags.PAUSED);
        p.setFlags(flags);
        ErrorCode error = new ErrorCode(new error_code());
        TorrentHandle h = session.addTorrent(p, error);
        if (error.isError() || h == null || !h.isValid()) throw new IOException(error.getMessage());
        items.put(item.hash, item);
        try { saveItem(item); } catch (IOException e) { items.remove(item.hash); session.removeTorrent(h); throw e; }
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT);
    }

    private void restore() {
        File[] saved = stateDir.listFiles((dir, name) -> name.endsWith(".properties"));
        if (saved == null) return;
        Arrays.sort(saved, Comparator.comparingLong(File::lastModified));
        for (File f : saved) {
            Item item = null;
            try {
                Properties props = new Properties();
                try (InputStream in = new FileInputStream(f)) { props.load(in); }
                item = new Item(props.getProperty("hash"));
                item.name = props.getProperty("name", "Торрент");
                item.source = props.getProperty("source");
                item.path = props.getProperty("path");
                item.paused = Boolean.parseBoolean(props.getProperty("paused"));
                AddTorrentParams p = null;
                File resume = new File(stateDir, item.hash + ".resume");
                if (resume.isFile()) {
                    error_code ec = new error_code();
                    add_torrent_params raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(Files.readAllBytes(resume.toPath())), ec);
                    if (ec.value() == 0) p = new AddTorrentParams(raw);
                }
                if (p == null) {
                    if ("file".equals(item.source)) { p = new AddTorrentParams(); p.setTorrentInfo(new TorrentInfo(new File(stateDir, item.hash + ".torrent"))); }
                    else p = AddTorrentParams.parseMagnetUri(item.source);
                }
                add(item, p);
                TorrentHandle h = handle(item.hash);
                String choices = props.getProperty("choices", "");
                if (!choices.isEmpty() && h != null && h.torrentFile() != null && h.torrentFile().numFiles() == choices.length()) {
                    for (int i=0; i<choices.length(); i++) h.filePriority(i, choices.charAt(i)=='0' ? Priority.IGNORE : Priority.DEFAULT);
                }
            } catch (Exception e) {
                if (item != null && item.hash != null) { item.error = e.getMessage(); items.put(item.hash, item); }
            }
        }
    }

    private TorrentHandle handle(String hash) { return manager.find(Sha1Hash.parseHex(hash)); }
    public synchronized List<Item> snapshot() {
        for (Item item : items.values()) {
            TorrentHandle h = handle(item.hash);
            if (h == null) continue;
            TorrentStatus s = h.status(TorrentHandle.QUERY_NAME);
            item.name = s.name().isEmpty() ? item.name : s.name();
            item.done = s.totalWantedDone(); item.size = s.totalWanted(); item.uploaded = s.allTimeUpload();
            item.progress = s.progress(); item.down = s.downloadRate(); item.up = s.uploadRate();
            item.peers = s.numPeers(); item.seeds = s.numSeeds(); item.metadata = s.hasMetadata(); item.finished = s.isFinished();
            item.error = s.errorCode().isError() ? s.errorCode().getMessage() : "";
            item.state = !item.error.isEmpty() ? "Ошибка" : item.paused ? "На паузе" : !networkAllowed ? "Ожидание Wi-Fi" :
                !item.metadata ? "Получение списка файлов…" : s.state() == TorrentStatus.State.CHECKING_FILES || s.state() == TorrentStatus.State.CHECKING_RESUME_DATA ? "Проверка файлов…" : item.finished ? "Загружено · раздача" : item.peers == 0 ? "Поиск пиров…" : "Загружается";
        }
        return new ArrayList<>(items.values());
    }
    public synchronized List<Content> content(String hash) {
        List<Content> out = new ArrayList<>(); TorrentHandle h = handle(hash);
        if (h == null || h.torrentFile() == null) return out;
        FileStorage fs = h.torrentFile().files(); long[] progress = h.fileProgress();
        Priority[] priorities = h.filePriorities();
        for (int i=0; i<fs.numFiles(); i++) {
            if (fs.padFileAt(i)) continue;
            Content c = new Content(); c.index=i; c.path=fs.filePath(i); c.size=fs.fileSize(i); c.done=progress[i];
            c.wanted = i < priorities.length && priorities[i] != Priority.IGNORE; out.add(c);
        }
        return out;
    }
    public synchronized void select(String hash, int index, boolean wanted) throws IOException {
        TorrentHandle h = handle(hash); if (h == null) return;
        h.filePriority(index, wanted ? Priority.DEFAULT : Priority.IGNORE); saveItem(items.get(hash)); h.saveResumeData(TorrentHandle.SAVE_INFO_DICT);
    }
    public synchronized void pause(String hash, boolean value) throws IOException {
        Item item = items.get(hash); if (item == null) return;
        item.paused=value; TorrentHandle h = handle(hash);
        if (h != null) { if (value) h.pause(); else { h.unsetFlags(TorrentFlags.UPLOAD_MODE); h.resume(); } h.saveResumeData(TorrentHandle.SAVE_INFO_DICT); }
        saveItem(item);
    }
    public synchronized void remove(String hash) {
        Item removed = items.remove(hash); if (removed == null) return;
        TorrentHandle h = handle(hash); if (h != null) session.removeTorrent(h);
        for (String ext : new String[]{".properties", ".torrent", ".resume"}) new File(stateDir, hash+ext).delete();
    }
    public synchronized String magnet(String hash) { TorrentHandle h=handle(hash); if(h==null)throw new IllegalStateException("Торрент недоступен"); return h.makeMagnetUri(); }
    public synchronized void recheck(String hash) { TorrentHandle h=handle(hash); if(h!=null)h.forceRecheck(); }
    public synchronized void limits(int down, int up) { session.applySettings(new SettingsPack().downloadRateLimit(down).uploadRateLimit(up)); }
    public synchronized void network(boolean allowed) { if(networkAllowed==allowed)return; networkAllowed=allowed; if(allowed)session.resume();else session.pause(); }
    public String sessionError() { return sessionError; }
    public synchronized void checkpoint() throws IOException {
        if (closed) return;
        for (Item item : items.values()) { saveItem(item); TorrentHandle h=handle(item.hash); if(h!=null)h.saveResumeData(TorrentHandle.SAVE_INFO_DICT); }
    }
    private void saveItem(Item item) throws IOException {
        Properties p=new Properties(); p.setProperty("hash",item.hash); p.setProperty("name",item.name);
        p.setProperty("path",item.path); p.setProperty("source",item.source); p.setProperty("paused",Boolean.toString(item.paused));
        TorrentHandle h=handle(item.hash);
        if(h!=null && h.torrentFile()!=null) { StringBuilder c=new StringBuilder();for(Priority v:h.filePriorities())c.append(v==Priority.IGNORE?'0':'1');p.setProperty("choices",c.toString()); }
        ByteArrayOutputStream out=new ByteArrayOutputStream();p.store(out,"Tyanu torrent");atomic(new File(stateDir,item.hash+".properties"),out.toByteArray());
    }
    private static void atomic(File file,byte[] data)throws IOException {
        File tmp=new File(file.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);out.getFD().sync();}
        Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    public void close() throws IOException {
        synchronized(this){if(closed)return; checkpoint();closed=true;timer.shutdownNow();}
        // Drain the native resume-data queue while the alert thread is still alive.
        try { Thread.sleep(600); } catch(InterruptedException e){Thread.currentThread().interrupt();}
        manager.stop();
    }
}
