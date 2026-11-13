package it.lact.goalrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Goal Recorder: app Android vera. La pagina (assets/www) e' dentro l'app, non su un sito: si apre anche senza rete.
 * L'app aggiunge alla pagina (oggetto JavaScript "GRNative"):
 *  - stato di modalita' aereo / Wi-Fi / internet / hotspot e pulsanti che aprono le impostazioni giuste;
 *  - schermo sempre acceso e orientamento orizzontale per le camere;
 *  - i video scritti DIRETTAMENTE nella cartella pubblica Download/GoalRecorder mentre registri (niente download:
 *    si salvano anche senza rete e si copiano sul PC collegando il telefono).
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String URL_INIZIO = "https://" + HOST + "/index.html";
    static final String CARTELLA = "GoalRecorder";
    static final int RICHIESTA_PERMESSI = 7;

    WebView web;
    PermissionRequest richiestaWeb;
    final Map<Integer, Scrittura> aperti = new HashMap<>();
    int prossimoId = 1;

    static class Scrittura {
        OutputStream out;
        Uri uri;
        File file;
        long dim;
    }

    // ------------------------------------------------------------------ avvio
    @Override
    protected void onCreate(Bundle stato) {
        super.onCreate(stato);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Ponte(), "GRNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return risorsa(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    // nessuna app per aprire il link
                }
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) {
                    return risorsa(r.getUrl());
                }
            });
        }
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    String[] mancanti = permessiMancanti();
                    if (mancanti.length == 0) {
                        req.grant(req.getResources());
                    } else {
                        richiestaWeb = req;
                        requestPermissions(mancanti, RICHIESTA_PERMESSI);
                    }
                });
            }
        });
        if (stato != null) web.restoreState(stato);
        else web.loadUrl(URL_INIZIO);
        // telefoni vecchi (Android 8-9): per scrivere in Download serve il permesso, lo si chiede subito.
        // Fotocamera e microfono si chiedono solo quando servono (sul telefono che fa da camera).
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, RICHIESTA_PERMESSI);
    }

    /** I file della pagina vengono dall'app (assets/www), come se fossero su https://appassets.androidplatform.net */
    WebResourceResponse risorsa(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath();
        if (p == null || p.equals("/") || p.isEmpty()) p = "/index.html";
        try {
            InputStream in = getAssets().open("www" + p);
            WebResourceResponse r = new WebResourceResponse(tipo(p), "utf-8", in);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            r.setResponseHeaders(h);
            return r;
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(),
                    new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    static String tipo(String p) {
        String q = p.toLowerCase();
        if (q.endsWith(".html")) return "text/html";
        if (q.endsWith(".js")) return "application/javascript";
        if (q.endsWith(".css")) return "text/css";
        if (q.endsWith(".png")) return "image/png";
        if (q.endsWith(".json") || q.endsWith(".webmanifest")) return "application/json";
        if (q.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    String[] permessiMancanti() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.CAMERA);
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT < 29) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        List<String> out = new ArrayList<>();
        for (String x : p) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) out.add(x);
        return out.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int codice, String[] permessi, int[] esiti) {
        super.onRequestPermissionsResult(codice, permessi, esiti);
        if (richiestaWeb == null) return;
        boolean ok = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (ok) richiestaWeb.grant(richiestaWeb.getResources());
        else richiestaWeb.deny();
        richiestaWeb = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        synchronized (aperti) {
            for (Scrittura w : aperti.values()) chiudi(w);
            aperti.clear();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ file in Download/GoalRecorder
    static String mimeSemplice(String m) {
        if (m == null || m.isEmpty()) return "application/octet-stream";
        int i = m.indexOf(';');
        return (i >= 0 ? m.substring(0, i) : m).trim();
    }

    static String nomeSicuro(String n) {
        String s = n == null ? "" : n.replaceAll("[^A-Za-z0-9._ -]", "_");
        return s.isEmpty() ? "GoalRecorder_file" : s;
    }

    File cartellaVecchia() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CARTELLA);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    Scrittura crea(String nome, String mime) throws IOException {
        Scrittura w = new Scrittura();
        nome = nomeSicuro(nome);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeSemplice(mime));
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CARTELLA);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            w.uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (w.uri == null) throw new IOException("cartella Download non disponibile");
            OutputStream o = getContentResolver().openOutputStream(w.uri, "w");
            if (o == null) throw new IOException("file non apribile");
            w.out = new BufferedOutputStream(o, 1 << 20);
        } else {
            w.file = new File(cartellaVecchia(), nome);
            w.out = new BufferedOutputStream(new FileOutputStream(w.file), 1 << 20);
        }
        return w;
    }

    void chiudi(Scrittura w) {
        try {
            w.out.flush();
            w.out.close();
        } catch (IOException e) {
            // gia' chiuso
        }
        if (w.uri != null && Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                getContentResolver().update(w.uri, cv, null, null);
            } catch (Exception e) {
                // resta comunque scritto
            }
        } else if (w.file != null) {
            MediaScannerConnection.scanFile(this, new String[]{w.file.getAbsolutePath()}, null, null);
        }
    }

    Uri trovaPerNome(String nome) {
        if (Build.VERSION.SDK_INT < 29) return null;
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] arg = {nome, Environment.DIRECTORY_DOWNLOADS + "/" + CARTELLA + "/"};
        try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID}, sel, arg, null)) {
            if (c != null && c.moveToFirst())
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
        } catch (Exception e) {
            // non trovato
        }
        return null;
    }

    // ------------------------------------------------------------------ rete e impostazioni
    Boolean hotspotAcceso() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            Method m = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            m.setAccessible(true);
            return (Boolean) m.invoke(wm);
        } catch (Throwable t) {
            return null;                                          // questo telefono non lo dice
        }
    }

    boolean apriImpostazioni(String cosa) {
        List<Intent> prove = new ArrayList<>();
        if ("connessioni".equals(cosa)) {
            if (Build.VERSION.SDK_INT >= 29) prove.add(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
            prove.add(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
            prove.add(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } else if ("wifi".equals(cosa)) {
            if (Build.VERSION.SDK_INT >= 29) prove.add(new Intent(Settings.Panel.ACTION_WIFI));
            prove.add(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } else if ("aereo".equals(cosa)) {
            prove.add(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        } else if ("hotspot".equals(cosa)) {
            Intent t = new Intent(Intent.ACTION_MAIN);
            t.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
            prove.add(t);
            Intent t2 = new Intent(Intent.ACTION_MAIN);
            t2.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"));
            prove.add(t2);
            prove.add(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
        prove.add(new Intent(Settings.ACTION_SETTINGS));
        for (Intent i : prove) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                // prova la successiva
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ponte JavaScript
    class Ponte {
        @JavascriptInterface
        public String versione() {
            return "1.0";
        }

        @JavascriptInterface
        public String stato() {
            JSONObject o = new JSONObject();
            try {
                o.put("aereo", Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                o.put("wifi", wm != null && wm.isWifiEnabled());
                boolean wifiConn = false, internet = false;
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network n = cm.getActiveNetwork();
                    NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
                    if (c != null) {
                        wifiConn = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                        internet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    }
                }
                o.put("wifiConnesso", wifiConn);
                o.put("internet", internet);
                Boolean h = hotspotAcceso();
                o.put("hotspot", h == null ? JSONObject.NULL : h);
            } catch (Exception e) {
                // stato parziale
            }
            return o.toString();
        }

        @JavascriptInterface
        public boolean apri(String cosa) {
            final boolean[] ok = {false};
            runOnUiThread(() -> ok[0] = apriImpostazioni(cosa));
            return true;
        }

        @JavascriptInterface
        public void schermoAcceso(boolean si) {
            runOnUiThread(() -> {
                if (si) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void orizzontale(boolean si) {
            runOnUiThread(() -> {
                setRequestedOrientation(si ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                int f = si ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) : 0;
                getWindow().getDecorView().setSystemUiVisibility(f);
            });
        }

        @JavascriptInterface
        public int fileApri(String nome, String mime) {
            try {
                Scrittura w = crea(nome, mime);
                synchronized (aperti) {
                    int id = prossimoId++;
                    aperti.put(id, w);
                    return id;
                }
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public double fileScrivi(int id, String base64) {
            Scrittura w;
            synchronized (aperti) {
                w = aperti.get(id);
            }
            if (w == null) return -1;
            try {
                byte[] b = Base64.decode(base64, Base64.DEFAULT);
                w.out.write(b);
                w.dim += b.length;
                return w.dim;
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public double fileChiudi(int id) {
            Scrittura w;
            synchronized (aperti) {
                w = aperti.remove(id);
            }
            if (w == null) return -1;
            chiudi(w);
            return w.dim;
        }

        @JavascriptInterface
        public boolean salvaTesto(String nome, String testo) {
            try {
                nome = nomeSicuro(nome);
                byte[] b = testo.getBytes(StandardCharsets.UTF_8);
                Uri esiste = trovaPerNome(nome);
                if (esiste != null) {
                    try (OutputStream o = getContentResolver().openOutputStream(esiste, "wt")) {
                        if (o == null) return false;
                        o.write(b);
                    }
                    return true;
                }
                Scrittura w = crea(nome, "application/json");
                w.out.write(b);
                w.dim = b.length;
                chiudi(w);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String elenco() {
            JSONArray a = new JSONArray();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                    String[] arg = {Environment.DIRECTORY_DOWNLOADS + "/" + CARTELLA + "/"};
                    String[] col = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
                    try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, col, sel, arg,
                            MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
                        while (c != null && c.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("id", String.valueOf(c.getLong(0)));
                            o.put("nome", c.getString(1));
                            o.put("dim", c.getLong(2));
                            o.put("data", c.getLong(3) * 1000L);
                            a.put(o);
                        }
                    }
                } else {
                    File[] fs = cartellaVecchia().listFiles();
                    if (fs != null) for (File f : fs) {
                        if (!f.isFile()) continue;
                        JSONObject o = new JSONObject();
                        o.put("id", f.getName());
                        o.put("nome", f.getName());
                        o.put("dim", f.length());
                        o.put("data", f.lastModified());
                        a.put(o);
                    }
                }
            } catch (Exception e) {
                // elenco parziale
            }
            return a.toString();
        }

        Uri uriDi(String id) {
            try {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Long.parseLong(id));
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean elimina(String id) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    Uri u = uriDi(id);
                    return u != null && getContentResolver().delete(u, null, null) > 0;
                }
                return new File(cartellaVecchia(), nomeSicuro(id)).delete();
            } catch (Exception e) {
                return false;                                      // es. file creato da un'installazione precedente
            }
        }

        @JavascriptInterface
        public boolean condividi(String id) {
            if (Build.VERSION.SDK_INT < 29) return false;
            Uri u = uriDi(id);
            if (u == null) return false;
            String t = getContentResolver().getType(u);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(t == null ? "video/*" : t);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent scelta = Intent.createChooser(i, "Condividi");
            scelta.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(scelta);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public double spazioLibero() {
            try {
                StatFs s = new StatFs(Environment.getExternalStorageDirectory().getPath());
                return (double) s.getAvailableBytes();
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public String cartella() {
            return Environment.DIRECTORY_DOWNLOADS + "/" + CARTELLA;
        }
    }
}
