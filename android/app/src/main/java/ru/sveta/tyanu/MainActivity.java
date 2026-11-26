package ru.sveta.tyanu;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.libtorrent4j.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    static final int BG=0xff111119,PANEL=0xff191923,CARD=0xff20202d,LINE=0xff323241,TEXT=0xfff3f0ff,MUTED=0xffa4a1b8,ACCENT=0xffc0a4ff,GREEN=0xff8ee0c4,RED=0xffffaaa9;
    private TorrentService service;
    private boolean bound,wide,split,visible;
    private LinearLayout root,list,details,nav;
    private ScrollView listScroll;
    private TextView downValue,upValue,countValue,storageLabel;
    private EditText search;
    private String filter="Все",selected="",detailKey="",query="";
    private android.content.SharedPreferences prefs;
    private List<TorrentEngine.Item> items=Collections.emptyList();
    private Uri pending;
    private String removalHash="";
    private int removalChoice;
    private Uri pendingRemovalSource;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder binder){service=((TorrentService.LocalBinder)binder).service();bound=true;service.observe(()->{if(visible)render();});render();}
        public void onServiceDisconnected(ComponentName n){service=null;bound=false;}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("settings",0);
        if(state!=null){selected=state.getString("selected","");filter=state.getString("filter","Все");removalHash=state.getString("removalHash","");removalChoice=state.getInt("removalChoice",0);String ref=state.getString("pendingRemovalSource",null);if(ref!=null)pendingRemovalSource=Uri.parse(ref);}
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        build();receive(getIntent());
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
    }
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("selected",selected);state.putString("filter",filter);state.putString("removalHash",removalHash);state.putInt("removalChoice",removalChoice);if(pendingRemovalSource!=null)state.putString("pendingRemovalSource",pendingRemovalSource.toString());}
    @Override protected void onStart(){
        super.onStart();visible=true;
        Intent i=new Intent(this,TorrentService.class);
        try{startForegroundService(i);bindService(i,connection,Context.BIND_AUTO_CREATE);}catch(Exception e){message("Не удалось запустить загрузки",e.getMessage());}
    }
    @Override protected void onStop(){visible=false;if(service!=null)service.observe(null);if(bound){unbindService(connection);bound=false;}service=null;super.onStop();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receive(i);render();}
    private void receive(Intent intent){
        pending=null;
        if(Intent.ACTION_VIEW.equals(intent.getAction()))pending=intent.getData();
        if(Intent.ACTION_SEND.equals(intent.getAction())){
            Uri stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);String text=intent.getStringExtra(Intent.EXTRA_TEXT);
            if(stream!=null)pending=stream;else if(text!=null&&text.trim().startsWith("magnet:?")){pending=Uri.parse(text.trim());}
        }
    }
    int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("tnum");v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setIncludeFontPadding(false);return v;}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    GradientDrawable shape(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    void space(LinearLayout v,int h){v.addView(new View(this),lp(1,h));}
    void stretch(LinearLayout v){v.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}
    TextView button(String title,boolean primary,Runnable action){TextView v=text(title,14,primary?BG:TEXT,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(18),dp(12),dp(18),dp(12));v.setMinHeight(dp(48));v.setBackground(shape(primary?ACCENT:CARD,15));v.setOnClickListener(w->action.run());v.setFocusable(true);return v;}
    void smallGap(LinearLayout v){v.addView(new View(this),lp(9,1));}
    private void build(){
        wide=getResources().getConfiguration().screenWidthDp>=760;split=getResources().getConfiguration().screenWidthDp>=1100;
        root=row();root.setGravity(Gravity.TOP);root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(s.left,s.top,s.right,s.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        root.requestApplyInsets();
        if(wide){
            LinearLayout side=column();side.setPadding(dp(22),dp(32),dp(18),dp(22));side.setBackgroundColor(PANEL);root.addView(side,lp(194,-1));
            TextView brand=text("тяну ↙",34,ACCENT,true);side.addView(brand);space(side,8);side.addView(text("Всё своим ходом",12,MUTED,false));space(side,38);
            nav=column();side.addView(nav,lp(-1,-2));drawNav();
            View fill=new View(this);side.addView(fill,new LinearLayout.LayoutParams(1,0,1));
            side.addView(button("⚙  Настройки",false,this::settings),lp(-1,-2));space(side,16);side.addView(text("Твоя версия · 0.1.3",11,MUTED,false));
        }
        LinearLayout main=column();main.setPadding(dp(wide?28:18),dp(26),dp(wide?28:18),0);root.addView(main,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout header=row();TextView title=text("Загрузки",wide?30:27,TEXT,true);header.addView(title);stretch(header);
        if(!wide){header.addView(button("⚙",false,this::settings));smallGap(header);}
        header.addView(button(wide?"+  Добавить":"+",true,this::addMenu));main.addView(header,lp(-1,-2));space(main,22);
        LinearLayout stats=row();downValue=stat(stats,"СКОРОСТЬ ЗАГРУЗКИ",GREEN);smallGap(stats);upValue=stat(stats,"ОТДАЧА",ACCENT);smallGap(stats);countValue=stat(stats,"ТОРРЕНТЫ",TEXT);main.addView(stats,lp(-1,-2));space(main,18);
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setTextSize(14);search.setHint("Поиск по названию");search.setPadding(dp(16),dp(10),dp(16),dp(10));search.setBackground(shape(PANEL,13));main.addView(search,lp(-1,48));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString().toLowerCase(Locale.ROOT);render();}public void afterTextChanged(Editable e){}});
        space(main,14);
        if(!wide){HorizontalScrollView sc=new HorizontalScrollView(this);sc.setHorizontalScrollBarEnabled(false);nav=row();sc.addView(nav);main.addView(sc,lp(-1,50));drawNav();space(main,10);}
        LinearLayout actions=row();actions.addView(text("БИБЛИОТЕКА",11,MUTED,true));stretch(actions);actions.addView(button("Ⅱ  Всем",false,()->all(true)));smallGap(actions);actions.addView(button("▶  Всем",false,()->all(false)));main.addView(actions,lp(-1,-2));space(main,12);
        LinearLayout body=row();body.setGravity(Gravity.TOP);main.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        listScroll=new ScrollView(this);listScroll.setFillViewport(true);listScroll.setClipToPadding(false);listScroll.setPadding(0,0,0,dp(20));list=column();listScroll.addView(list);body.addView(listScroll,new LinearLayout.LayoutParams(0,-1,1));
        if(split){smallGap(body);ScrollView dscroll=new ScrollView(this);dscroll.setFillViewport(true);details=column();details.setPadding(dp(20),dp(22),dp(20),dp(22));details.setBackground(shape(PANEL,22));dscroll.addView(details);body.addView(dscroll,lp(300,-1));}
    }
    TextView stat(LinearLayout parent,String title,int color){LinearLayout box=column();box.setPadding(dp(14),dp(16),dp(10),dp(16));box.setBackground(shape(PANEL,18));TextView value=text("—",wide?25:20,color,true);box.addView(value);space(box,9);box.addView(text(title,wide?10:8,MUTED,true));parent.addView(box,new LinearLayout.LayoutParams(0,dp(90),1));return value;}
    void drawNav(){if(nav==null)return;nav.removeAllViews();for(String value:new String[]{"Все","Загружаются","Готово","Пауза"}){boolean active=filter.equals(value);TextView b=button(value,false,()->{filter=value;drawNav();render();});b.setTextColor(active?ACCENT:MUTED);b.setBackground(shape(active?0xff30283f:PANEL,13));if(wide){b.setGravity(Gravity.CENTER_VERTICAL);nav.addView(b,lp(-1,52));space(nav,6);}else{nav.addView(b,lp(-2,46));smallGap(nav);}}}
    private boolean matches(TorrentEngine.Item i){return i.name.toLowerCase(Locale.ROOT).contains(query)&&(filter.equals("Все")||filter.equals("Пауза")&&i.paused||filter.equals("Готово")&&i.finished||filter.equals("Загружаются")&&!i.paused&&!i.finished);}
    private void render(){
        if(list==null)return;
        if(service!=null)items=service.items;
        long down=0,up=0;for(TorrentEngine.Item i:items){down+=i.down;up+=i.up;}
        downValue.setText(bytes(down)+"/с");upValue.setText(bytes(up)+"/с");countValue.setText(String.valueOf(items.size()));
        int scroll=listScroll.getScrollY();list.removeAllViews();
        if(service!=null&&!service.error.isEmpty())addBanner(service.error,RED);
        int shown=0;for(TorrentEngine.Item item:items){if(matches(item)){list.addView(card(item),lp(-1,-2));space(list,12);shown++;}}
        if(shown==0){
            LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(22),dp(54),dp(22),dp(40));
            TextView mark=text("↙",80,ACCENT,false);empty.addView(mark);space(empty,20);
            TextView title=text(items.isEmpty()?"Пусть качается.":"Здесь пока пусто",25,TEXT,true);title.setGravity(Gravity.CENTER);empty.addView(title);space(empty,12);
            TextView desc=text(items.isEmpty()?"Добавь magnet-ссылку или файл .torrent.\nОстальное «Тяну» возьмёт на себя.":"Попробуй другой фильтр или название.",14,MUTED,false);desc.setGravity(Gravity.CENTER);desc.setLineSpacing(dp(5),1);empty.addView(desc);space(empty,25);if(items.isEmpty())empty.addView(button("+  Первый торрент",true,this::addMenu));list.addView(empty,lp(-1,-2));
        }
        if(scroll>0)listScroll.post(()->listScroll.scrollTo(0,scroll));
        TorrentEngine.Item chosen=find(selected);if(chosen==null&&!items.isEmpty()){chosen=items.get(0);selected=chosen.hash;}
        if(split&&chosen!=null){String key=chosen.hash+chosen.state+(int)(chosen.progress*100)+chosen.metadata;if(!key.equals(detailKey)){detailKey=key;drawDetails(details,chosen);}}
        else if(split&&items.isEmpty()){details.removeAllViews();details.addView(text("Всё на своём месте",20,TEXT,true));space(details,12);details.addView(text("Выбери торрент — здесь появятся его файлы и подробности.",14,MUTED,false));}
        if(pendingRemovalSource!=null&&service!=null&&service.ready){
            final Uri uri=pendingRemovalSource;final String hash=removalHash;final int choice=removalChoice;pendingRemovalSource=null;removalHash="";
            if(!hash.isEmpty())run(engine->engine.sourceReference(hash,uri.toString(),readTorrentData(uri)),()->{if(visible&&!isDestroyed()){TorrentEngine.Item item=find(hash);if(item!=null)removalDialog(item,choice);}});
        }
        if(pending!=null&&service!=null&&service.ready){Uri uri=pending;pending=null;if("magnet".equals(uri.getScheme()))magnetDialog(uri.toString());else importTorrent(uri);}
    }
    void addBanner(String value,int color){TextView t=text(value,13,color,false);t.setPadding(dp(14),dp(12),dp(14),dp(12));t.setBackground(shape(CARD,12));list.addView(t,lp(-1,-2));space(list,12);}
    LinearLayout card(TorrentEngine.Item i){
        LinearLayout c=column();c.setPadding(dp(20),dp(19),dp(20),dp(16));GradientDrawable bg=shape(CARD,20);if(selected.equals(i.hash)&&split)bg.setStroke(dp(1),ACCENT);c.setBackground(bg);
        LinearLayout top=row();TextView icon=text(i.finished?"✓":"↓",24,i.finished?GREEN:ACCENT,true);icon.setGravity(Gravity.CENTER);icon.setBackground(shape(0xff30283f,12));top.addView(icon,lp(46,46));smallGap(top);
        TextView name=text(i.name,16,TEXT,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));smallGap(top);TextView percent=text(String.format(Locale.ROOT,"%.0f%%",i.progress*100),19,i.finished?GREEN:ACCENT,true);top.addView(percent);c.addView(top);space(c,17);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgress((int)(i.progress*1000));progress.setProgressTintList(ColorStateList.valueOf(i.finished?GREEN:ACCENT));progress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));c.addView(progress,lp(-1,6));space(c,13);
        LinearLayout status=row();TextView state=text(i.state,12,!i.error.isEmpty()?RED:i.paused?MUTED:i.finished?GREEN:ACCENT,false);state.setMaxLines(1);state.setEllipsize(TextUtils.TruncateAt.END);status.addView(state,new LinearLayout.LayoutParams(0,-2,1));status.addView(text(bytes(i.done)+" / "+bytes(i.size),12,MUTED,false));c.addView(status);space(c,13);
        LinearLayout bottom=row();TextView speed=text(i.paused?"Пауза":i.finished?"↑ "+bytes(i.up)+"/с":"↓ "+bytes(i.down)+"/с",13,TEXT,true);bottom.addView(speed);stretch(bottom);
        TextView pause=button(i.paused?"▶":"Ⅱ",false,()->run(e->e.pause(i.hash,!i.paused),null,i.paused));pause.setContentDescription(i.paused?"Продолжить загрузку":"Приостановить загрузку");bottom.addView(pause,lp(48,48));smallGap(bottom);TextView more=button("•••",false,()->torrentMenu(i));more.setContentDescription("Действия с торрентом");bottom.addView(more,lp(48,48));c.addView(bottom);
        c.setOnClickListener(v->{selected=i.hash;detailKey="";if(split)render();else showDetails(i);});return c;
    }
    TorrentEngine.Item find(String hash){for(TorrentEngine.Item i:items)if(i.hash.equals(hash))return i;return null;}
    void showDetails(TorrentEngine.Item i){LinearLayout box=column();box.setPadding(dp(20),dp(20),dp(20),dp(20));ScrollView sc=new ScrollView(this);sc.addView(box);drawDetails(box,i);new AlertDialog.Builder(this).setView(sc).setPositiveButton("Закрыть",null).show();}
    void drawDetails(LinearLayout box,TorrentEngine.Item i){
        box.removeAllViews();box.addView(text("ПОДРОБНОСТИ",10,ACCENT,true));space(box,15);TextView name=text(i.name,20,TEXT,true);name.setMaxLines(4);box.addView(name);space(box,20);
        detail(box,"Состояние",i.state);detail(box,"Загружено",bytes(i.done)+" из "+bytes(i.size));detail(box,"Отдано",bytes(i.uploaded));detail(box,"Пиры / сиды",i.peers+" / "+i.seeds);
        if(!i.finished&&i.down>0){long sec=Math.max(0,(i.size-i.done)/i.down);detail(box,"Осталось",sec<60?"Меньше минуты":sec<3600?sec/60+" мин":sec/3600+" ч "+sec%3600/60+" мин");}
        space(box,8);box.addView(text("ПАПКА",10,MUTED,true));space(box,8);TextView path=text(i.path,12,MUTED,false);path.setTextIsSelectable(true);box.addView(path);space(box,18);
        if(!i.error.isEmpty()){box.addView(text(i.error,13,RED,false));space(box,15);}
        box.addView(text("ФАЙЛЫ",10,ACCENT,true));space(box,12);
        LinearLayout files=column();box.addView(files,lp(-1,-2));files.addView(text(i.metadata?"Получаю список…":"Список появится после получения метаданных",13,MUTED,false));
        if(i.metadata&&service!=null){
            service.execute(e->{List<TorrentEngine.Content> content=e.content(i.hash);runOnUiThread(()->{
                if(!visible||files.getParent()==null)return;files.removeAllViews();
                for(TorrentEngine.Content f:content){
                    LinearLayout line=row();CheckBox check=new CheckBox(this);check.setButtonTintList(ColorStateList.valueOf(ACCENT));check.setChecked(f.wanted);check.setContentDescription("Скачивать "+f.path);line.addView(check,lp(44,48));
                    LinearLayout info=column();TextView fname=text(new File(f.path).getName(),13,TEXT,false);fname.setMaxLines(2);info.addView(fname);space(info,5);info.addView(text(bytes(f.done)+" / "+bytes(f.size),11,MUTED,false));line.addView(info,new LinearLayout.LayoutParams(0,-2,1));
                    check.setOnCheckedChangeListener((b,wanted)->run(e2->e2.select(i.hash,f.index,wanted),()->{detailKey="";}));
                    if(f.done>=f.size&&f.size>0){info.setOnClickListener(v->openFile(i.path,f.path));info.setPadding(0,dp(8),0,dp(8));}
                    files.addView(line,lp(-1,-2));space(files,8);
                }
            });},null,null);
        }
    }
    void detail(LinearLayout box,String title,String value){LinearLayout r=row();r.addView(text(title,12,MUTED,false));stretch(r);TextView t=text(value,12,TEXT,false);t.setMaxLines(2);r.addView(t);box.addView(r,lp(-1,-2));space(box,13);}
    void torrentMenu(TorrentEngine.Item i){new AlertDialog.Builder(this).setTitle(i.name).setItems(new String[]{"Файлы и подробности",i.paused?"Продолжить":"Пауза","Проверить скачанные файлы","Скопировать magnet-ссылку","Удалить…"},(d,n)->{if(n==0)showDetails(i);if(n==1)run(e->e.pause(i.hash,!i.paused),null,i.paused);if(n==2)run(e->e.recheck(i.hash),null);if(n==3)run(e->{String magnet=e.magnet(i.hash);runOnUiThread(()->copy(magnet));},null);if(n==4)removalDialog(i,0);}).show();}
    void removalDialog(TorrentEngine.Item item,int initialChoice){
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(8));
        TextView name=text(item.name,17,TEXT,true);box.addView(name,lp(-1,-2));space(box,14);
        RadioGroup choices=new RadioGroup(this);String[] titles={"Строку и исходный файл .torrent","Только строку из списка","Всё: строку, .torrent и скачанное содержимое"};
        for(int j=0;j<titles.length;j++){RadioButton option=new RadioButton(this);option.setId(301+j);option.setText(titles[j]);option.setTextColor(TEXT);option.setTextSize(14);option.setButtonTintList(ColorStateList.valueOf(ACCENT));option.setPadding(0,dp(9),0,dp(9));choices.addView(option,lp(-1,-2));}
        box.addView(choices);space(box,14);TextView note=text("",13,MUTED,false);box.addView(note,lp(-1,-2));space(box,8);
        TextView path=text(item.path,12,MUTED,false);path.setTextIsSelectable(true);box.addView(path,lp(-1,-2));
        Runnable update=()->{int mode=choices.getCheckedRadioButtonId()-301;String info=mode==2?"Скачанное содержимое этой раздачи будет удалено. Другие файлы в папке останутся.":mode==1?"Исходный .torrent и скачанное содержимое останутся на устройстве.":"Будет удалён только исходный .torrent. Скачанное содержимое останется.";
            if(mode!=1&&item.sourceReference.isEmpty())info+="\n\n"+("file".equals(item.source)?"Исходный .torrent нужно выбрать: старая версия не запоминала его расположение.":"Задача добавлена по magnet-ссылке: исходного файла .torrent нет.");note.setText(info);note.setTextColor(mode==2?RED:MUTED);};
        choices.check(301+Math.max(0,Math.min(2,initialChoice)));choices.setOnCheckedChangeListener((g,id)->update.run());update.run();
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("Удалить торрент?").setView(box).setNegativeButton("Отмена",null).setPositiveButton("Удалить",null);
        if("file".equals(item.source))builder.setNeutralButton("Выбрать .torrent",null);
        AlertDialog dialog=builder.create();dialog.setOnShowListener(v->{
            if("file".equals(item.source))dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->{int choice=choices.getCheckedRadioButtonId()-301;dialog.dismiss();chooseRemovalSource(item.hash,choice);});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                int selectedMode=choices.getCheckedRadioButtonId()-301;
                if(selectedMode!=1&&"file".equals(item.source)&&item.sourceReference.isEmpty()){dialog.dismiss();chooseRemovalSource(item.hash,selectedMode);return;}
                if(service==null){message("Подожди секунду","Служба ещё запускается.");return;}
                dialog.setCancelable(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Удаление…");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);if("file".equals(item.source))dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                final String reference=item.sourceReference;
                service.execute(engine->engine.remove(item.hash,TorrentEngine.Removal.values()[selectedMode],new TorrentEngine.SourceAccess(){
                    public byte[] read(String ref)throws IOException{return readTorrentData(Uri.parse(ref));}
                    public void delete(String ref)throws IOException{deleteSourceFile(Uri.parse(ref));}
                }),()->{releaseSourceAccess(reference);dialog.dismiss();detailKey="";render();},error->{dialog.dismiss();message("Не удалось удалить",error);detailKey="";render();});
            });
        });dialog.show();
    }
    Intent torrentPicker(){return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);}
    void chooseRemovalSource(String hash,int choice){removalHash=hash;removalChoice=choice;startActivityForResult(torrentPicker(),22);}
    void rememberSourceAccess(Uri uri){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(SecurityException e){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}}
    void releaseSourceAccess(String reference){if(reference==null||reference.isEmpty())return;Uri uri=Uri.parse(reference);for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())if(permission.getUri().equals(uri)){int flags=(permission.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)|(permission.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);try{getContentResolver().releasePersistableUriPermission(uri,flags);}catch(SecurityException ignored){}break;}}
    byte[] readTorrentData(Uri uri)throws IOException{
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Исходный .torrent недоступен");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>16*1024*1024)throw new IOException("Файл .torrent больше 16 МБ");out.write(buffer,0,n);}return out.toByteArray();
        }catch(SecurityException|IllegalArgumentException e){throw new IOException("Нет доступа к исходному .torrent. Выбери его заново кнопкой «Выбрать .torrent».",e);}
    }
    void deleteSourceFile(Uri uri)throws IOException{
        try{
            boolean deleted;
            if("file".equals(uri.getScheme())){File file=new File(uri.getPath());if(!file.isFile())throw new IOException("Исходный .torrent недоступен");deleted=file.delete();}
            else if(DocumentsContract.isDocumentUri(this,uri))deleted=DocumentsContract.deleteDocument(getContentResolver(),uri);
            else deleted=getContentResolver().delete(uri,null,null)>0;
            if(!deleted)throw new IOException("Не удалось удалить исходный .torrent. Выбери его заново или удали только строку.");
        }catch(SecurityException|UnsupportedOperationException|IllegalArgumentException e){throw new IOException("Нет права на удаление исходного .torrent. Выбери его заново кнопкой «Выбрать .torrent».",e);}
    }
    void all(boolean pause){run(e->{for(TorrentEngine.Item i:e.snapshot())e.pause(i.hash,pause);},null,!pause&&!items.isEmpty());}
    void run(TorrentService.Work work,Runnable done){run(work,done,false);}
    void run(TorrentService.Work work,Runnable done,boolean startsTransfer){if(service==null){message("Подожди секунду","Загрузки ещё запускаются.");return;}if(startsTransfer){try{service.beginTransfer();}catch(Exception e){message("Не удалось запустить загрузку",e.getMessage());return;}}service.execute(work,()->{if(done!=null)done.run();render();},s->message("Не получилось",s));}
    void addMenu(){new AlertDialog.Builder(this).setTitle("Добавить торрент").setItems(new String[]{"Вставить magnet-ссылку","Открыть файл .torrent"},(d,n)->{if(n==0)magnetDialog("");else{startActivityForResult(torrentPicker(),20);}}).show();}
    void magnetDialog(String initial){
        LinearLayout box=column();box.setPadding(dp(24),dp(10),dp(24),dp(10));EditText input=new EditText(this);input.setHint("magnet:?xt=urn:btih:…");input.setText(initial);input.setTextSize(14);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setMaxLines(5);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);box.addView(input,lp(-1,-2));space(box,16);box.addView(text("Папка: "+folder().getAbsolutePath(),12,MUTED,false));
        CheckBox pause=new CheckBox(this);pause.setText("Добавить на паузе");pause.setTextColor(TEXT);box.addView(pause);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Magnet-ссылка").setView(box).setNegativeButton("Отмена",null).setPositiveButton("Добавить",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(!storageAllowed()){requestStorage();return;}String link=input.getText().toString().trim();if(!link.startsWith("magnet:?")){input.setError("Нужна magnet-ссылка");return;}boolean paused=pause.isChecked();File destination=folder();run(e->{String hash=e.addMagnet(link,destination,paused);runOnUiThread(()->selected=hash);},()->{dialog.dismiss();detailKey="";render();},!paused);}));dialog.show();
    }
    void importTorrent(Uri uri){
        if(service==null||!service.ready){pending=uri;return;}
        rememberSourceAccess(uri);
        service.execute(e->{
            byte[] data=readTorrentData(uri);
            TorrentInfo ti=new TorrentInfo(data);String[] names=new String[ti.numFiles()];boolean[] checked=new boolean[names.length];Arrays.fill(checked,true);
            for(int j=0;j<names.length;j++)names[j]=ti.files().filePath(j)+"  ·  "+bytes(ti.files().fileSize(j));
            runOnUiThread(()->{
                if(!visible)return;
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle(ti.name()).setMultiChoiceItems(names,checked,(d,n,value)->checked[n]=value).setNegativeButton("Отмена",null).setNeutralButton("На паузе",null).setPositiveButton("Скачать",null).create();
                dialog.setOnShowListener(v->{for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL})dialog.getButton(which).setOnClickListener(w->{if(!storageAllowed()){requestStorage();return;}boolean pause=which==AlertDialog.BUTTON_NEUTRAL;File destination=folder();run(engine->{String hash=engine.addTorrent(data,destination,pause,checked,uri.toString());runOnUiThread(()->selected=hash);},()->{dialog.dismiss();detailKey="";render();},!pause);});});dialog.show();
            });
        },null,s->message("Не удалось открыть торрент",s));
    }
    File folder(){return new File(prefs.getString("folder",new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Tyanu").getAbsolutePath()));}
    boolean storageAllowed(){return Build.VERSION.SDK_INT>=30?Environment.isExternalStorageManager():checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;}
    void requestStorage(){new AlertDialog.Builder(this).setTitle("Доступ к папке загрузок").setMessage("Чтобы сохранять торренты в обычную папку, разреши «Тяну» доступ к файлам в настройках Android. Затем вернись и нажми «Добавить» ещё раз.").setNegativeButton("Пока нет",null).setPositiveButton("Настройки",(d,n)->{if(Build.VERSION.SDK_INT>=30){try{startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(ActivityNotFoundException ex){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}}else requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},12);}).show();}
    void settings(){
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(12));box.addView(text("ПАПКА ЗАГРУЗОК",11,ACCENT,true));space(box,10);storageLabel=text(folder().getAbsolutePath(),13,TEXT,false);box.addView(storageLabel);space(box,10);
        box.addView(button("Выбрать папку",false,()->{if(!storageAllowed()){requestStorage();return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),21);}));space(box,20);
        Switch wifi=new Switch(this);wifi.setText("Скачивать только по Wi-Fi");wifi.setTextSize(14);wifi.setTextColor(TEXT);wifi.setChecked(prefs.getBoolean("wifi",true));box.addView(wifi,lp(-1,52));space(box,16);
        box.addView(text("СКОРОСТЬ · КБ/С",11,ACCENT,true));space(box,6);box.addView(text("0 — без ограничения",12,MUTED,false));space(box,12);
        EditText down=number("Загрузка",prefs.getInt("down",0)/1024),up=number("Отдача",prefs.getInt("up",0)/1024);box.addView(down);box.addView(up);space(box,18);
        box.addView(button("О приложении",false,()->message("Тяну · 0.1.3","Личный торрент-клиент для Android.\n\nДвижок: libtorrent4j 2.1.0-39 (MIT), libtorrent (BSD).\nБез рекламных SDK и аналитики.\n\nПри удалении можно отдельно выбрать исходный .torrent и скачанное содержимое. После загрузки торрент продолжает раздачу до паузы.\n\nAndroid может остановить фоновую загрузку через 6 часов. Открой приложение, чтобы продолжить.")));
        ScrollView sc=new ScrollView(this);sc.addView(box);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Настройки").setView(sc).setNegativeButton("Закрыть",null).setPositiveButton("Сохранить",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{long d=Long.parseLong(down.getText().toString().isEmpty()?"0":down.getText().toString()),u=Long.parseLong(up.getText().toString().isEmpty()?"0":up.getText().toString());if(d>2000000||u>2000000)throw new NumberFormatException();int dl=(int)(d*1024),ul=(int)(u*1024);prefs.edit().putBoolean("wifi",wifi.isChecked()).putInt("down",dl).putInt("up",ul).apply();run(e->e.limits(dl,ul),dialog::dismiss);}catch(NumberFormatException ex){down.setError("Допустимо от 0 до 2 000 000 КБ/с");}}));dialog.show();
    }
    EditText number(String hint,int value){EditText v=new EditText(this);v.setHint(hint);v.setTextColor(TEXT);v.setHintTextColor(MUTED);v.setText(String.valueOf(value));v.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);v.setSingleLine(true);v.setTextSize(16);return v;}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==20)importTorrent(uri);if(request==22){rememberSourceAccess(uri);pendingRemovalSource=uri;render();}if(request==21){try{if(!"com.android.externalstorage.documents".equals(uri.getAuthority()))throw new IOException("Выбери папку во внутренней памяти, на SD-карте или USB-накопителе.");String id=DocumentsContract.getTreeDocumentId(uri);String[] parts=id.split(":",2);File base="primary".equalsIgnoreCase(parts[0])?Environment.getExternalStorageDirectory():new File("/storage",parts[0]);File folder=parts.length>1?new File(base,parts[1]):base;if(!folder.isDirectory()||!folder.canWrite())throw new IOException("Нет доступа к папке. Разреши доступ к файлам.");prefs.edit().putString("folder",folder.getCanonicalPath()).apply();if(storageLabel!=null)storageLabel.setText(folder.getCanonicalPath());}catch(Exception e){message("Папка недоступна",e.getMessage());}}}
    void openFile(String base,String relative){try{File root=new File(base).getCanonicalFile(),file=new File(root,relative).getCanonicalFile();if(!file.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Файл находится за пределами папки загрузки");Uri uri=ShareProvider.share(file);Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,getContentResolver().getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(open,"Открыть файл"));}catch(Exception e){message("Не удалось открыть файл",e.getMessage());}}
    void copy(String value){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Magnet",value));Toast.makeText(this,"Magnet-ссылка скопирована",Toast.LENGTH_SHORT).show();}
    void message(String title,String value){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("Понятно",null).show();}
    public static String bytes(long n){if(n<1024)return n+" Б";String[] units={"КБ","МБ","ГБ","ТБ"};double v=n;int p=-1;do{v/=1024;p++;}while(v>=1024&&p<units.length-1);return String.format(new Locale("ru"),v>=100?"%.0f %s":v>=10?"%.1f %s":"%.2f %s",v,units[p]);}
}
