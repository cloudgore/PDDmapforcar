package app.samara.cameras;

import android.app.Activity;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import javax.net.ssl.HttpsURLConnection;

public final class MainActivity extends Activity {
    private static final String MAP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String MAP_URL =
            MAP_ORIGIN + "/assets/index.html";
    private static final int GPS_PERMISSION = 1;
    private static final String TILE_HOST = "tile.openstreetmap.org";
    private static final String BACKUP_TILE_HOST = "tile.opentopomap.org";
    private static final String REGISTRY = "https://xn--80aebkobnwfcnsfk1e0h.xn--p1ai/r/63/milestones/?page=";
    private static final int SURFACE = Color.rgb(16, 47, 48);
    private WebView map;
    private GeolocationPermissions.Callback locationPrompt;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(SURFACE);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right,
                        Math.max(safe.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }

        map = new WebView(this);
        map.setBackgroundColor(SURFACE);
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SamaraCameraMap/1.9");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        map.setWebViewClient(new MapClient());
        map.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                if (!MAP_ORIGIN.equals(origin) && !(MAP_ORIGIN + "/").equals(origin)) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasPreciseLocation()) {
                    callback.invoke(origin, true, false);
                } else if (locationPrompt == null) {
                    locationPrompt = (ignored, allow, retain) -> callback.invoke(origin, allow, false);
                    requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, GPS_PERMISSION);
                } else {
                    callback.invoke(origin, false, false);
                }
            }
        });
        layout.addView(map, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layout);
        map.loadUrl(MAP_URL);
    }

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == GPS_PERMISSION && locationPrompt != null) {
            GeolocationPermissions.Callback callback = locationPrompt;
            locationPrompt = null;
            callback.invoke(MAP_ORIGIN, hasPreciseLocation(), false);
        }
    }

    private final class MapClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (request.isForMainFrame() && MAP_URL.equals(url.toString())) {
                try {
                    return new WebResourceResponse("text/html", "UTF-8", 200, "OK",
                            Collections.singletonMap("Content-Security-Policy",
                                    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                                    + "img-src https://" + TILE_HOST + " https://" + BACKUP_TILE_HOST
                                    + "; connect-src 'self'; base-uri 'none'; form-action 'none'"),
                            getAssets().open("index.html"));
                } catch (IOException error) {
                    return blocked();
                }
            }
            if (!request.isForMainFrame() && "GET".equals(request.getMethod())
                    && url.toString().matches(MAP_ORIGIN.replace(".", "\\.") + "/registry/[1-9][0-9]{0,2}")) {
                return registryPage(url.getLastPathSegment());
            }
            if (!request.isForMainFrame() && "https".equals(url.getScheme())
                    && (TILE_HOST.equals(url.getHost()) || BACKUP_TILE_HOST.equals(url.getHost()))) {
                return null;
            }
            return blocked();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (MAP_URL.equals(url.toString())) {
                return false;
            }
            if (request.isForMainFrame() && request.hasGesture() && isExternalLink(url)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url)
                            .addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (ActivityNotFoundException error) {
                    Toast.makeText(MainActivity.this, "Не найден браузер", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    }

    private static WebResourceResponse registryPage(String page) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(REGISTRY + page).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "SamaraCameraMap/1.4");
            if (connection.getResponseCode() != 200) {
                return new WebResourceResponse("text/plain", "UTF-8", 502, "Registry unavailable",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (body.size() + count > 4 * 1024 * 1024) return blocked();
                    body.write(buffer, 0, count);
                }
                return new WebResourceResponse("text/plain", "UTF-8", 200, "OK",
                        Collections.singletonMap("Cache-Control", "no-store"),
                        new ByteArrayInputStream(body.toByteArray()));
            }
        } catch (IOException error) {
            return new WebResourceResponse("text/plain", "UTF-8", 502, "Registry unavailable",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isExternalLink(Uri url) {
        if (!"https".equals(url.getScheme())) {
            return false;
        }
        String host = url.getHost();
        return "xn--80aebkobnwfcnsfk1e0h.xn--p1ai".equals(host)
                || "www.openstreetmap.org".equals(host)
                || "opentopomap.org".equals(host);
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        map.evaluateJavascript("typeof window.closeMapCard === 'function' && window.closeMapCard()", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        ((FrameLayout) map.getParent()).removeView(map);
        map.destroy();
        super.onDestroy();
    }
}
