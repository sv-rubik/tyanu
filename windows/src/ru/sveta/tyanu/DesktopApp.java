package ru.sveta.tyanu;

import com.formdev.flatlaf.FlatDarkLaf;
import org.libtorrent4j.TorrentInfo;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

public final class DesktopApp {
    static final String VERSION="0.1.0";
    static final Color BG=new Color(0x14191D),PANEL=new Color(0x1A2227),CARD=new Color(0x202B31),LINE=new Color(0x344149),TEXT=new Color(0xEFF6F3),MUTED=new Color(0x95A6AB),MINT=new Color(0x8DDFC0),RED=new Color(0xFFA89C);
    final JFrame frame=new JFrame("Тяну · торрент-клиент");
    final Properties settings=new Properties();
    final File appDir;
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    final DefaultListModel<TorrentEngine.Item> model=new DefaultListModel<>();
    final JList<TorrentEngine.Item> torrentList=new JList<>(model);
    final JPanel detail=new FitWidthPanel();
    final JTextField search=new JTextField();
    final JLabel speedDown=new JLabel("0 Б/с"),speedUp=new JLabel("0 Б/с"),count=new JLabel("0"),banner=new JLabel("Запускаю движок…");
    final Map<String,JButton> nav=new LinkedHashMap<>();
    volatile TorrentEngine engine;
    volatile List<TorrentEngine.Item> items=Collections.emptyList();
    volatile boolean closing;
    String filter="Все",selected="",detailKey="";
    boolean refreshing;
    TrayIcon tray;
    FileChannel lockChannel;
    FileLock lock;
    static String previewPath;

    interface Work {void run(TorrentEngine engine)throws Exception;}

    public static void main(String[] args){
        System.setProperty("sun.java2d.uiScale.enabled","true");
        // Resolve JNI beside the JAR, independently of the caller's working directory.
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))try{
            File origin=new File(DesktopApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File nativeDll=new File(origin.isDirectory()?origin:origin.getParentFile(),"libtorrent4j.dll");
            if(nativeDll.isFile())System.setProperty("libtorrent4j.jni.path",nativeDll.getAbsolutePath());
        }catch(Exception ignored){}

        if(args.length>1&&"--preview".equals(args[0])){previewPath=args[1];System.setProperty("tyanu.data",new File(args[1]).getParent()+"/preview-data");}
        FlatDarkLaf.setup();
        UIManager.put("defaultFont",new Font("Segoe UI",Font.PLAIN,14));
        UIManager.put("Panel.background",BG);UIManager.put("Label.foreground",TEXT);UIManager.put("Component.focusColor",MINT);
        UIManager.put("Component.arc",14);UIManager.put("Button.arc",16);UIManager.put("TextComponent.arc",14);
        UIManager.put("Button.background",CARD);UIManager.put("Button.foreground",TEXT);
        UIManager.put("TextField.background",PANEL);UIManager.put("TextField.foreground",TEXT);UIManager.put("TextField.caretForeground",MINT);
        UIManager.put("CheckBox.icon.selectedBackground",MINT);UIManager.put("CheckBox.icon.checkmarkColor",BG);
        UIManager.put("ProgressBar.foreground",MINT);UIManager.put("ProgressBar.background",LINE);
        UIManager.put("ScrollBar.thumb",LINE);UIManager.put("ScrollPane.border",new EmptyBorder(0,0,0,0));
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{e.printStackTrace();if(SwingUtilities.isEventDispatchThread())JOptionPane.showMessageDialog(null,e.toString(),"Тяну: ошибка",JOptionPane.ERROR_MESSAGE);});
        SwingUtilities.invokeLater(()->{
            try{DesktopApp app=new DesktopApp();app.start(args);}catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage(),"Не удалось открыть «Тяну»",JOptionPane.ERROR_MESSAGE);System.exit(1);}
        });
    }
    DesktopApp()throws Exception{
        String userData=System.getProperty("tyanu.data");
        if(userData==null){String local=System.getenv("LOCALAPPDATA");userData=local==null?new File(System.getProperty("user.home"),".tyanu").getAbsolutePath():new File(local,"Tyanu").getAbsolutePath();}
        appDir=new File(userData);if(!appDir.isDirectory()&&!appDir.mkdirs())throw new IOException("Не удалось создать папку настроек");
        lockChannel=FileChannel.open(new File(appDir,"instance.lock").toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockChannel.tryLock();if(lock==null)throw new IOException("«Тяну» уже работает. Проверь окно приложения или значок рядом с часами.");
        File f=new File(appDir,"settings.properties");if(f.isFile())try(InputStream in=new FileInputStream(f)){settings.load(in);}
        build();
    }
    void start(String[] args){
        frame.setVisible(true);if(previewPath==null)setupTray();
        worker.execute(()->{
            try{
                engine=new TorrentEngine(new File(appDir,"session"),integer("down",0),integer("up",0));
                refresh();
                if(previewPath==null)for(String arg:args){if(arg.startsWith("magnet:?"))SwingUtilities.invokeLater(()->magnetDialog(arg));else if(arg.toLowerCase(Locale.ROOT).endsWith(".torrent"))importFile(new File(arg));}
            }catch(Throwable e){SwingUtilities.invokeLater(()->banner.setText("Не удалось запустить движок: "+e.getMessage()));}
        });
        worker.scheduleWithFixedDelay(()->{if(engine!=null&&!closing)try{refresh();}catch(Exception e){SwingUtilities.invokeLater(()->banner.setText("Ошибка: "+e.getMessage()));}},2,2,TimeUnit.SECONDS);
        if(previewPath!=null){javax.swing.Timer preview=new javax.swing.Timer(1600,e->{
            try{BufferedImage img=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();frame.paint(g);g.dispose();javax.imageio.ImageIO.write(img,"png",new File(previewPath));System.out.println("UI_PREVIEW_OK "+previewPath);quit();}catch(Exception ex){ex.printStackTrace();System.exit(2);}
        });preview.setRepeats(false);preview.start();}
    }
    int integer(String key,int fallback){try{return Integer.parseInt(settings.getProperty(key,Integer.toString(fallback)));}catch(Exception e){return fallback;}}
    File folder(){return new File(settings.getProperty("folder",new File(System.getProperty("user.home"),"Downloads/Tyanu").getAbsolutePath()));}
    static Font font(int size,boolean bold){return new Font("Segoe UI",bold?Font.BOLD:Font.PLAIN,size);}
    static JLabel label(String s,int size,Color color,boolean bold){JLabel l=new JLabel(s);l.setFont(font(size,bold));l.setForeground(color);return l;}
    static JPanel col(){JPanel p=new JPanel();p.setOpaque(false);p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));return p;}
    static JPanel row(){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,9,0));p.setOpaque(false);return p;}
    static JPanel padded(JPanel p,int top,int left,int bottom,int right){p.setBorder(new EmptyBorder(top,left,bottom,right));return p;}
    static void gap(JPanel p,int h){p.add(Box.createVerticalStrut(h));}
    static void left(JComponent c){c.setAlignmentX(Component.LEFT_ALIGNMENT);}
    JButton button(String title,boolean primary,Runnable action){JButton b=new JButton(title);b.setFont(font(14,true));b.setFocusable(true);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));b.setMargin(new Insets(11,18,11,18));if(primary){b.setBackground(MINT);b.setForeground(BG);}b.addActionListener(e->action.run());return b;}
    void build(){
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);frame.setMinimumSize(new Dimension(1000,670));frame.setSize(1320,850);frame.setLocationRelativeTo(null);frame.setIconImage(icon(64));
        JPanel root=new JPanel(new BorderLayout());root.setBackground(BG);frame.setContentPane(root);
        JPanel side=padded(col(),34,22,22,22);side.setOpaque(true);side.setBackground(PANEL);side.setPreferredSize(new Dimension(202,0));root.add(side,BorderLayout.WEST);
        JLabel brand=label("тяну ↙",38,MINT,true);left(brand);side.add(brand);gap(side,8);JLabel tagline=label("Всё своим ходом",12,MUTED,false);left(tagline);side.add(tagline);gap(side,45);
        for(String name:new String[]{"Все","Загружаются","Готово","Пауза"}){
            JButton b=button(name,false,()->{filter=name;updateNav();render();});b.setHorizontalAlignment(SwingConstants.LEFT);b.setMaximumSize(new Dimension(Integer.MAX_VALUE,48));b.setBorderPainted(false);left(b);side.add(b);gap(side,8);nav.put(name,b);
        }
        side.add(Box.createVerticalGlue());JButton prefs=button("Настройки",false,this::settingsDialog);prefs.setMaximumSize(new Dimension(Integer.MAX_VALUE,46));left(prefs);side.add(prefs);gap(side,12);
        JButton exit=button("Завершить",false,this::quit);exit.setMaximumSize(new Dimension(Integer.MAX_VALUE,44));left(exit);side.add(exit);gap(side,18);JLabel version=label("Windows · "+VERSION,11,MUTED,false);left(version);side.add(version);updateNav();
        JPanel main=padded(new JPanel(new BorderLayout(0,18)),28,28,20,28);main.setOpaque(false);root.add(main,BorderLayout.CENTER);
        JPanel top=col();JPanel heading=new JPanel(new BorderLayout());heading.setOpaque(false);heading.add(label("Загрузки",32,TEXT,true),BorderLayout.WEST);heading.add(button("+  Добавить",true,this::addMenu),BorderLayout.EAST);left(heading);top.add(heading);gap(top,25);
        JPanel stats=new JPanel(new GridLayout(1,3,12,0));stats.setOpaque(false);stats.add(stat(speedDown,"СКОРОСТЬ ЗАГРУЗКИ",MINT));stats.add(stat(speedUp,"ОТДАЧА",TEXT));stats.add(stat(count,"ТОРРЕНТЫ",TEXT));stats.setMaximumSize(new Dimension(Integer.MAX_VALUE,100));left(stats);top.add(stats);gap(top,19);
        search.putClientProperty("JTextField.placeholderText","Поиск по названию");search.setBorder(new CompoundBorder(search.getBorder(),new EmptyBorder(7,7,7,7)));search.setMaximumSize(new Dimension(Integer.MAX_VALUE,46));left(search);top.add(search);gap(top,16);
        JPanel toolbar=new JPanel(new BorderLayout());toolbar.setOpaque(false);JLabel library=label("БИБЛИОТЕКА",11,MUTED,true);toolbar.add(library,BorderLayout.WEST);JPanel controls=row();controls.add(button("Пауза",false,()->withSelected(i->work(e->e.pause(i.hash,true),null))));controls.add(button("Продолжить",false,()->withSelected(i->work(e->e.pause(i.hash,false),null))));controls.add(button("•••",false,this::selectedMenu));toolbar.add(controls,BorderLayout.EAST);left(toolbar);top.add(toolbar);gap(top,10);
        banner.setForeground(MUTED);banner.setFont(font(12,false));left(banner);top.add(banner);main.add(top,BorderLayout.NORTH);
        torrentList.setOpaque(false);torrentList.setBackground(BG);torrentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);torrentList.setFixedCellHeight(158);torrentList.setCellRenderer(new CardRenderer());
        torrentList.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!refreshing){TorrentEngine.Item i=torrentList.getSelectedValue();selected=i==null?"":i.hash;detailKey="";showDetail(i);}});
        torrentList.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}void popup(MouseEvent e){if(e.isPopupTrigger()){int n=torrentList.locationToIndex(e.getPoint());if(n>=0){torrentList.setSelectedIndex(n);JPopupMenu menu=torrentMenu(torrentList.getSelectedValue());menu.show(torrentList,e.getX(),e.getY());}}}});
        JScrollPane scroll=new JScrollPane(torrentList);scroll.setOpaque(false);scroll.getViewport().setOpaque(false);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel listPane=new JPanel(new BorderLayout());listPane.setOpaque(false);listPane.add(scroll);JLabel hint=label("Перетащи .torrent в окно или добавь magnet-ссылку",12,MUTED,false);hint.setBorder(new EmptyBorder(14,2,0,0));listPane.add(hint,BorderLayout.SOUTH);
        detail.setLayout(new BoxLayout(detail,BoxLayout.Y_AXIS));detail.setOpaque(false);detail.setBorder(new EmptyBorder(22,20,22,20));JScrollPane detailScroll=new JScrollPane(detail);detailScroll.setBorder(null);detailScroll.setBackground(PANEL);detailScroll.getViewport().setBackground(PANEL);detailScroll.setMinimumSize(new Dimension(280,0));detailScroll.setPreferredSize(new Dimension(320,0));detailScroll.getVerticalScrollBar().setUnitIncrement(22);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,listPane,detailScroll);split.setBorder(null);split.setOpaque(false);split.setResizeWeight(1);split.setDividerSize(18);split.setContinuousLayout(true);main.add(split,BorderLayout.CENTER);
        showDetail(null);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){render();}public void removeUpdate(DocumentEvent e){render();}public void changedUpdate(DocumentEvent e){render();}});
        TransferHandler drop=new TransferHandler(){public boolean canImport(TransferSupport support){return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)||support.isDataFlavorSupported(DataFlavor.stringFlavor);}public boolean importData(TransferSupport support){try{
            if(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){List<?> files=(List<?>)support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);for(Object o:files){File f=(File)o;if(f.getName().toLowerCase(Locale.ROOT).endsWith(".torrent"))importFile(f);else message("Нужен файл .torrent");}return true;}
            String text=(String)support.getTransferable().getTransferData(DataFlavor.stringFlavor);if(text.trim().startsWith("magnet:?")){magnetDialog(text.trim());return true;}return false;
        }catch(Exception e){message(e.getMessage());return false;}}};
        root.setTransferHandler(drop);torrentList.setTransferHandler(drop);
        frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){closeWindow();}});
        frame.getRootPane().registerKeyboardAction(e->addMenu(),KeyStroke.getKeyStroke(KeyEvent.VK_N,InputEvent.CTRL_DOWN_MASK),JComponent.WHEN_IN_FOCUSED_WINDOW);
        frame.getRootPane().registerKeyboardAction(e->{search.requestFocusInWindow();search.selectAll();},KeyStroke.getKeyStroke(KeyEvent.VK_F,InputEvent.CTRL_DOWN_MASK),JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
    JPanel stat(JLabel value,String title,Color color){JPanel p=padded(new RoundPanel(PANEL,20),17,20,17,20);p.setLayout(new BorderLayout(0,10));value.setForeground(color);value.setFont(font(28,true));p.add(value,BorderLayout.CENTER);p.add(label(title,10,MUTED,true),BorderLayout.SOUTH);return p;}
    void updateNav(){for(Map.Entry<String,JButton> n:nav.entrySet()){n.getValue().setBackground(n.getKey().equals(filter)?new Color(0x294037):PANEL);n.getValue().setForeground(n.getKey().equals(filter)?MINT:MUTED);}}
    boolean matches(TorrentEngine.Item i){return i.name.toLowerCase(Locale.ROOT).contains(search.getText().toLowerCase(Locale.ROOT))&&(filter.equals("Все")||filter.equals("Пауза")&&i.paused||filter.equals("Готово")&&i.finished||filter.equals("Загружаются")&&!i.paused&&!i.finished);}
    void refresh(){List<TorrentEngine.Item> list=engine.snapshot();items=list;String error=engine.sessionError();SwingUtilities.invokeLater(()->{if(!closing){banner.setText(error.isEmpty()?list.isEmpty()?"Добавь первый торрент — и пусть качается.":"Загрузки продолжаются, пока приложение работает.":"Ошибка сети: "+error);banner.setForeground(error.isEmpty()?MUTED:RED);render();}});}
    void render(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::render);return;}
        refreshing=true;model.clear();long down=0,up=0;int selectedIndex=-1;
        for(TorrentEngine.Item i:items){down+=i.down;up+=i.up;if(matches(i)){if(i.hash.equals(selected))selectedIndex=model.size();model.addElement(i);}}
        speedDown.setText(bytes(down)+"/с");speedUp.setText(bytes(up)+"/с");count.setText(Integer.toString(items.size()));
        if(selectedIndex>=0)torrentList.setSelectedIndex(selectedIndex);else if(!model.isEmpty()){torrentList.setSelectedIndex(0);selected=model.get(0).hash;}else selected="";
        refreshing=false;TorrentEngine.Item i=torrentList.getSelectedValue();String key=i==null?"empty":i.hash+i.state+(int)(i.progress*100)+i.metadata;
        if(!key.equals(detailKey)){detailKey=key;showDetail(i);}torrentList.repaint();
    }
    void showDetail(TorrentEngine.Item i){
        detail.removeAll();JLabel title=label("ПОДРОБНОСТИ",10,MINT,true);left(title);detail.add(title);gap(detail,18);
        if(i==null){JTextArea welcome=plain("Всё на своём месте.\n\nВыбери торрент — здесь появятся его файлы и подробности.",17,TEXT);detail.add(welcome);detail.add(Box.createVerticalGlue());detail.revalidate();detail.repaint();return;}
        detail.add(plain(i.name,21,TEXT));gap(detail,22);detailLine("Состояние",i.state);detailLine("Загружено",bytes(i.done)+" из "+bytes(i.size));detailLine("Отдано",bytes(i.uploaded));detailLine("Пиры / сиды",i.peers+" / "+i.seeds);
        if(!i.finished&&i.down>0){long sec=Math.max(0,(i.size-i.done)/i.down);detailLine("Осталось",sec<60?"Меньше минуты":sec<3600?sec/60+" мин":sec/3600+" ч "+sec%3600/60+" мин");}
        gap(detail,8);detail.add(plain(i.path,12,MUTED));gap(detail,12);JButton open=button("Открыть папку",false,()->openFolder(i.path));left(open);detail.add(open);gap(detail,22);
        if(!i.error.isEmpty()){detail.add(plain(i.error,12,RED));gap(detail,15);}
        JLabel heading=label("ФАЙЛЫ",10,MINT,true);left(heading);detail.add(heading);gap(detail,12);JPanel files=col();left(files);detail.add(files);
        if(!i.metadata)files.add(plain("Список появится после получения метаданных.",13,MUTED));
        else work(e->{List<TorrentEngine.Content> content=e.content(i.hash);SwingUtilities.invokeLater(()->{if(closing||!i.hash.equals(selected))return;files.removeAll();for(TorrentEngine.Content f:content){
            JPanel row=new JPanel(new BorderLayout(3,4));row.setOpaque(false);JCheckBox check=new JCheckBox();check.setSelected(f.wanted);check.setToolTipText("Скачивать этот файл");row.add(check,BorderLayout.WEST);
            JPanel info=col();info.add(plain(new File(f.path).getName(),13,TEXT));gap(info,5);JLabel size=label(bytes(f.done)+" / "+bytes(f.size),11,MUTED,false);left(size);info.add(size);row.add(info,BorderLayout.CENTER);check.addActionListener(a->work(en->en.select(i.hash,f.index,check.isSelected()),()->detailKey=""));
            if(f.done>=f.size&&f.size>0){JButton launch=button("↗",false,()->openFile(i.path,f.path));launch.setToolTipText("Открыть файл");launch.setMargin(new Insets(5,9,5,9));row.add(launch,BorderLayout.EAST);}
            left(row);row.setMaximumSize(new Dimension(Integer.MAX_VALUE,90));files.add(row);gap(files,14);
        }files.revalidate();files.repaint();});},null);
        detail.add(Box.createVerticalGlue());detail.revalidate();detail.repaint();
    }
    JTextArea plain(String s,int size,Color color){JTextArea a=new JTextArea(s){
            public Dimension getPreferredSize(){
                int width=265; if(getParent()!=null&&getParent().getWidth()>40){Insets edge=getParent().getInsets();width=Math.max(120,getParent().getWidth()-edge.left-edge.right);}
                FontMetrics metrics=getFontMetrics(getFont());int lines=0;
                for(String paragraph:getText().split("\\n",-1)){int used=0;lines++;for(String word:paragraph.split(" ",-1)){int pixels=metrics.stringWidth(word+" ");if(used>0&&used+pixels>width){lines++;used=0;}while(pixels>width){lines++;pixels-=width;}used+=pixels;}}
                return new Dimension(240,Math.max(metrics.getHeight()+8,lines*metrics.getHeight()+8));
            }
            public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
            public Dimension getMinimumSize(){return new Dimension(0,getPreferredSize().height);}
        };a.setFont(font(size,false));a.setForeground(color);a.setOpaque(false);a.setLineWrap(true);a.setWrapStyleWord(true);a.setEditable(false);a.setFocusable(false);a.setBorder(null);left(a);return a;}
    void detailLine(String key,String value){JPanel p=new JPanel(new BorderLayout(8,0));p.setOpaque(false);p.add(label(key,12,MUTED,false),BorderLayout.WEST);JLabel right=label(value,12,TEXT,false);right.setHorizontalAlignment(SwingConstants.RIGHT);p.add(right,BorderLayout.CENTER);p.setMaximumSize(new Dimension(Integer.MAX_VALUE,40));left(p);detail.add(p);gap(detail,13);}
    void withSelected(Consumer<TorrentEngine.Item> action){TorrentEngine.Item i=torrentList.getSelectedValue();if(i!=null)action.accept(i);else message("Сначала выбери торрент в списке.");}
    void selectedMenu(){withSelected(i->{JPopupMenu m=torrentMenu(i);m.show(torrentList,Math.max(20,torrentList.getWidth()-230),30);});}
    JPopupMenu torrentMenu(TorrentEngine.Item i){JPopupMenu menu=new JPopupMenu();menuItem(menu,i.paused?"Продолжить":"Пауза",()->work(e->e.pause(i.hash,!i.paused),null));menuItem(menu,"Открыть папку",()->openFolder(i.path));menuItem(menu,"Проверить файлы",()->work(e->e.recheck(i.hash),null));menuItem(menu,"Скопировать magnet-ссылку",()->work(e->{String magnet=e.magnet(i.hash);SwingUtilities.invokeLater(()->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(magnet),null));},null));menu.addSeparator();menuItem(menu,"Удалить из списка…",()->{if(JOptionPane.showConfirmDialog(frame,"Скачанные файлы останутся в папке.\nУбрать торрент из списка?",i.name,JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)work(e->e.remove(i.hash),()->{detailKey="";render();});});return menu;}
    void menuItem(JPopupMenu p,String label,Runnable r){JMenuItem item=new JMenuItem(label);item.addActionListener(e->r.run());p.add(item);}
    void work(Work task,Runnable done){
        if(closing)return;
        worker.execute(()->{try{if(engine==null)throw new IllegalStateException("Движок ещё запускается. Подожди секунду.");task.run(engine);refresh();if(done!=null)SwingUtilities.invokeLater(done);}catch(Exception e){SwingUtilities.invokeLater(()->message(e.getMessage()==null?e.toString():e.getMessage()));}});
    }
    void addMenu(){Object[] options={"Magnet-ссылка","Файл .torrent","Отмена"};int choice=JOptionPane.showOptionDialog(frame,"Откуда добавим торрент?","Добавить торрент",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,options,options[0]);if(choice==0)magnetDialog(clipboardMagnet());if(choice==1){JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Выбери файл .torrent");chooser.setMultiSelectionEnabled(true);chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Торренты (*.torrent)","torrent"));if(chooser.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION)for(File file:chooser.getSelectedFiles())importFile(file);}}
    String clipboardMagnet(){try{String s=(String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);return s.trim().startsWith("magnet:?")?s.trim():"";}catch(Exception e){return "";}}
    void magnetDialog(String initial){
        JTextArea input=new JTextArea(initial,5,48);input.setLineWrap(true);input.setWrapStyleWord(true);JCheckBox paused=new JCheckBox("Добавить на паузе");JPanel p=col();p.add(new JScrollPane(input));gap(p,12);p.add(plain("Папка: "+folder().getAbsolutePath(),12,MUTED));gap(p,8);p.add(paused);
        JDialog dialog=new JDialog(frame,"Magnet-ссылка",true);JPanel content=padded(new JPanel(new BorderLayout(0,18)),24,24,22,24);content.add(p,BorderLayout.CENTER);JPanel buttons=row();buttons.add(button("Отмена",false,dialog::dispose));buttons.add(button("Добавить",true,()->{String uri=input.getText().trim();if(!uri.startsWith("magnet:?")){message("Вставь magnet-ссылку, начинающуюся с magnet:?");return;}File target=folder();boolean pause=paused.isSelected();work(e->{String hash=e.addMagnet(uri,target,pause);SwingUtilities.invokeLater(()->selected=hash);},()->{dialog.dispose();detailKey="";render();});}));content.add(buttons,BorderLayout.SOUTH);dialog.setContentPane(content);dialog.pack();dialog.setLocationRelativeTo(frame);dialog.setVisible(true);
    }
    void importFile(File file){
        work(e->{if(!file.isFile()||file.length()>16*1024*1024)throw new IOException("Нужен существующий .torrent размером до 16 МБ.");byte[] data=Files.readAllBytes(file.toPath());TorrentInfo ti=new TorrentInfo(data);int num=ti.numFiles();String name=ti.name();String[] names=new String[num];boolean[] padding=new boolean[num];for(int i=0;i<num;i++){names[i]=ti.files().filePath(i)+"  ·  "+bytes(ti.files().fileSize(i));padding[i]=ti.files().padFileAt(i);}SwingUtilities.invokeLater(()->selectFiles(name,names,padding,data));},null);
    }
    void selectFiles(String title,String[] names,boolean[] padding,byte[] data){
        JDialog dialog=new JDialog(frame,title,true);JPanel body=padded(new JPanel(new BorderLayout(0,16)),22,24,22,24);JPanel files=col();JCheckBox[] checks=new JCheckBox[names.length];for(int i=0;i<names.length;i++){checks[i]=new JCheckBox(names[i],!padding[i]);checks[i].setEnabled(!padding[i]);left(checks[i]);files.add(checks[i]);gap(files,8);}JScrollPane sc=new JScrollPane(files);sc.setPreferredSize(new Dimension(650,310));body.add(sc,BorderLayout.CENTER);JPanel bottom=col();bottom.add(plain("Папка: "+folder().getAbsolutePath(),12,MUTED));gap(bottom,12);JPanel buttons=row();buttons.add(button("Отмена",false,dialog::dispose));for(boolean pause:new boolean[]{true,false})buttons.add(button(pause?"На паузе":"Скачать",!pause,()->{boolean[] chosen=new boolean[checks.length];for(int j=0;j<chosen.length;j++)chosen[j]=checks[j].isSelected();File dest=folder();work(e->{String hash=e.addTorrent(data,dest,pause,chosen);SwingUtilities.invokeLater(()->selected=hash);},()->{dialog.dispose();detailKey="";render();});}));bottom.add(buttons);body.add(bottom,BorderLayout.SOUTH);dialog.setContentPane(body);dialog.pack();dialog.setLocationRelativeTo(frame);dialog.setVisible(true);
    }
    void settingsDialog(){
        JDialog dialog=new JDialog(frame,"Настройки",true);JPanel body=padded(col(),24,26,24,26);JLabel heading=label("ПАПКА ЗАГРУЗОК",11,MINT,true);left(heading);body.add(heading);gap(body,12);JTextField path=new JTextField(folder().getAbsolutePath(),38);left(path);body.add(path);gap(body,10);JButton choose=button("Выбрать папку",false,()->{JFileChooser fc=new JFileChooser(path.getText());fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(fc.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION)path.setText(fc.getSelectedFile().getAbsolutePath());});left(choose);body.add(choose);gap(body,24);
        JLabel limits=label("ОГРАНИЧЕНИЯ СКОРОСТИ · КБ/С",11,MINT,true);left(limits);body.add(limits);gap(body,12);JSpinner down=new JSpinner(new SpinnerNumberModel(integer("down",0)/1024,0,2000000,100));JSpinner up=new JSpinner(new SpinnerNumberModel(integer("up",0)/1024,0,2000000,100));JPanel grid=new JPanel(new GridLayout(2,2,12,12));grid.setOpaque(false);grid.add(label("Загрузка",14,TEXT,false));grid.add(down);grid.add(label("Отдача",14,TEXT,false));grid.add(up);left(grid);body.add(grid);gap(body,10);body.add(plain("0 — без ограничения. Новая папка применяется только к новым торрентам.",12,MUTED));gap(body,20);
        JButton about=button("О приложении",false,()->message("Тяну "+VERSION+" для Windows\nЛичный торрент-клиент без рекламы и аналитики.\n\nДвижок: libtorrent4j 2.1.0-39.\nГотовые торренты продолжают раздачу до паузы.\n\nДанные приложения:\n"+appDir.getAbsolutePath()));left(about);body.add(about);gap(body,22);JPanel buttons=row();buttons.add(button("Отмена",false,dialog::dispose));buttons.add(button("Сохранить",true,()->{
            try{down.commitEdit();up.commitEdit();File f=new File(path.getText().trim());if(!f.isAbsolute())throw new IOException("Укажи полный путь к папке.");if(!f.isDirectory()&&!f.mkdirs())throw new IOException("Не удалось создать папку.");if(!f.canWrite())throw new IOException("Нет доступа к выбранной папке.");int dl=((Number)down.getValue()).intValue()*1024,ul=((Number)up.getValue()).intValue()*1024;settings.setProperty("folder",f.getCanonicalPath());settings.setProperty("down",Integer.toString(dl));settings.setProperty("up",Integer.toString(ul));saveSettings();work(e->e.limits(dl,ul),dialog::dispose);}catch(Exception e){message(e.getMessage());}
        }));left(buttons);body.add(buttons);dialog.setContentPane(body);dialog.pack();dialog.setLocationRelativeTo(frame);dialog.setVisible(true);
    }
    void saveSettings()throws IOException{File tmp=new File(appDir,"settings.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){settings.store(out,"Tyanu desktop settings");out.getFD().sync();}Files.move(tmp.toPath(),new File(appDir,"settings.properties").toPath(),StandardCopyOption.REPLACE_EXISTING);}
    void openFolder(String path){try{File f=new File(path);if(!f.isDirectory()&&!f.mkdirs())throw new IOException("Папка недоступна");Desktop.getDesktop().open(f);}catch(Exception e){message(e.getMessage());}}
    void openFile(String base,String relative){try{File root=new File(base).getCanonicalFile(),f=new File(root,relative).getCanonicalFile();if(!f.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Файл вне папки загрузок");Desktop.getDesktop().open(f);}catch(Exception e){message(e.getMessage());}}
    void setupTray(){
        if(!SystemTray.isSupported())return;
        try{PopupMenu menu=new PopupMenu();MenuItem show=new MenuItem("Открыть Тяну"),pause=new MenuItem("Пауза всем"),exit=new MenuItem("Завершить");show.addActionListener(e->SwingUtilities.invokeLater(this::show));pause.addActionListener(e->work(en->{for(TorrentEngine.Item i:en.snapshot())en.pause(i.hash,true);},null));exit.addActionListener(e->SwingUtilities.invokeLater(this::quit));menu.add(show);menu.add(pause);menu.addSeparator();menu.add(exit);tray=new TrayIcon(icon(32),"Тяну",menu);tray.setImageAutoSize(true);tray.addActionListener(e->SwingUtilities.invokeLater(this::show));SystemTray.getSystemTray().add(tray);}catch(Exception e){tray=null;}
    }
    void show(){frame.setVisible(true);frame.setExtendedState(Frame.NORMAL);frame.toFront();}
    void closeWindow(){if(tray==null){quit();return;}Object[] choices={"Свернуть в трей","Завершить","Отмена"};int c=JOptionPane.showOptionDialog(frame,"В трее загрузки продолжатся. При завершении состояние сохранится.","Закрыть «Тяну»?",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,choices,choices[0]);if(c==0)frame.setVisible(false);if(c==1)quit();}
    void quit(){if(closing)return;closing=true;frame.setEnabled(false);banner.setText("Сохраняю состояние…");worker.execute(()->{try{if(engine!=null)engine.close();if(lock!=null)lock.release();lockChannel.close();}catch(Exception e){e.printStackTrace();}finally{if(tray!=null)SystemTray.getSystemTray().remove(tray);SwingUtilities.invokeLater(frame::dispose);worker.shutdown();System.exit(0);}});}
    void message(String message){if(!closing)JOptionPane.showMessageDialog(frame,message,"Тяну",JOptionPane.INFORMATION_MESSAGE);}
    static BufferedImage icon(int size){BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(PANEL);g.fillRoundRect(0,0,size,size,size/3,size/3);g.setColor(MINT);g.setStroke(new BasicStroke(size/12f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawLine(size/2,size/4,size/2,size*2/3);g.drawLine(size/3,size/2,size/2,size*2/3);g.drawLine(size*2/3,size/2,size/2,size*2/3);g.drawLine(size/3,size*4/5,size*2/3,size*4/5);g.dispose();return image;}
    public static String bytes(long n){if(n<1024)return n+" Б";String[] u={"КБ","МБ","ГБ","ТБ"};double v=n;int p=-1;do{v/=1024;p++;}while(v>=1024&&p<3);return String.format(new Locale("ru"),v>=100?"%.0f %s":v>=10?"%.1f %s":"%.2f %s",v,u[p]);}
    static class FitWidthPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return new Dimension(320,600);}
        public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 22;}
        public int getScrollableBlockIncrement(Rectangle r,int o,int d){return 150;}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    static class RoundPanel extends JPanel{final Color color;final int radius;RoundPanel(Color color,int radius){this.color=color;this.radius=radius;setOpaque(false);}protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g2.setColor(color);g2.fillRoundRect(0,0,getWidth(),getHeight(),radius,radius);g2.dispose();super.paintComponent(g);}}
    final class CardRenderer implements ListCellRenderer<TorrentEngine.Item>{
        public Component getListCellRendererComponent(JList<? extends TorrentEngine.Item> list,TorrentEngine.Item item,int index,boolean isSelected,boolean focus){
            JPanel cell=new JPanel(){
                protected void paintComponent(Graphics graphics){
                    Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    int w=getWidth(),h=getHeight()-12;g.setColor(isSelected?new Color(0x263B34):CARD);g.fillRoundRect(0,0,w,h,22,22);
                    g.setFont(font(23,true));g.setColor(MINT);String percent=String.format(Locale.ROOT,"%.0f%%",item.progress*100);g.drawString(percent,w-20-g.getFontMetrics().stringWidth(percent),37);
                    g.setFont(font(16,true));g.setColor(TEXT);String name=item.name;FontMetrics metrics=g.getFontMetrics();int available=Math.max(30,w-125);while(name.length()>1&&metrics.stringWidth(name)>available)name=name.substring(0,name.length()-1);if(!name.equals(item.name))name+="…";g.drawString(name,20,35);
                    g.setColor(LINE);g.fillRoundRect(20,58,w-40,5,5,5);g.setColor(MINT);g.fillRoundRect(20,58,(int)((w-40)*Math.max(0,Math.min(1,item.progress))),5,5,5);
                    g.setFont(font(12,false));g.setColor(!item.error.isEmpty()?RED:item.paused?MUTED:MINT);g.drawString(item.state,20,88);
                    String total=bytes(item.done)+" / "+bytes(item.size);g.setColor(MUTED);g.drawString(total,w-20-g.getFontMetrics().stringWidth(total),88);
                    g.setFont(font(12,true));g.setColor(TEXT);g.drawString(item.paused?"На паузе":item.finished?"↑ "+bytes(item.up)+"/с":"↓ "+bytes(item.down)+"/с",20,120);
                    g.setFont(font(11,false));g.setColor(MUTED);String peers=item.peers+" пиров";g.drawString(peers,w-20-g.getFontMetrics().stringWidth(peers),120);g.dispose();
                }
            };cell.setOpaque(false);cell.setToolTipText(item.name);return cell;
        }
    }
}
