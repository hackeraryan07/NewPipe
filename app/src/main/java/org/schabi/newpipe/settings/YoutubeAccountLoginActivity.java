package org.schabi.newpipe.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Message;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user log into their Google/YouTube account through a WebView. After the user
 * finishes signing in and taps "Save login and close", the cookies set by the WebView
 * (including {@code SAPISID}) are captured and stored so that {@link DownloaderImpl} can
 * attach an authenticated {@code Authorization: SAPISIDHASH} header to YouTube requests,
 * allowing extraction of age-restricted videos that require a logged-in account.
 *
 * <p>Google's sign-in page rejects requests coming from a WebView that identifies itself as
 * such: the default WebView user agent contains a {@code "; wv"} marker Google checks for.
 * Stripping just that marker (and nothing else) keeps the reported user agent identical to a
 * real on-device Chrome browser in every other respect, which is what lets sign-in proceed.
 * Google's account chooser / 2-factor step also opens in a JS popup window
 * ({@code window.open}), so {@link WebChromeClient#onCreateWindow} must be handled — without
 * it the popup silently never appears and sign-in gets stuck.</p>
 */
public class YoutubeAccountLoginActivity extends AppCompatActivity {
    private static final String LOGIN_URL =
            "https://accounts.google.com/ServiceLogin"
                    + "?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F";

    private WebView webView;
    private FrameLayout webViewContainer;
    private final List<WebView> popupWebViews = new ArrayList<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_account_login);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.youtube_account_login_activity_title);
        }

        // Start from a clean slate so a previous account's leftover cookies don't get mixed in
        final CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.setAcceptCookie(true);

        webViewContainer = findViewById(R.id.youtubeLoginWebViewContainer);
        webView = findViewById(R.id.youtubeLoginWebView);
        configureWebView(webView, cookieManager);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(final WebView view,
                                                      final WebResourceRequest request) {
                // Let it navigate normally within this WebView for http(s) URLs.
                final String url = request.getUrl().toString();
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(final WebView view, final boolean isDialog,
                                           final boolean isUserGesture, final Message resultMsg) {
                final WebView popup = new WebView(YoutubeAccountLoginActivity.this);
                popup.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                configureWebView(popup, cookieManager);
                popup.setWebViewClient(new WebViewClient());
                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(final WebView window) {
                        webViewContainer.removeView(window);
                        popupWebViews.remove(window);
                    }
                });

                webViewContainer.addView(popup);
                popupWebViews.add(popup);

                final WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        webView.loadUrl(LOGIN_URL);

        findViewById(R.id.youtubeLoginDoneButton).setOnClickListener(v -> saveCookiesAndFinish());
    }

    private void configureWebView(final WebView view, final CookieManager cookieManager) {
        final WebSettings webSettings = view.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setSupportMultipleWindows(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        // Only strip the WebView marker; keep everything else identical to a real on-device
        // Chrome browser so the reported UA matches the engine's actual capabilities.
        webSettings.setUserAgentString(webSettings.getUserAgentString().replace("; wv", ""));
        cookieManager.setAcceptThirdPartyCookies(view, true);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveCookiesAndFinish() {
        final CookieManager cookieManager = CookieManager.getInstance();
        final String youtubeCookies = cookieManager.getCookie("https://www.youtube.com");
        final String accountsCookies = cookieManager.getCookie("https://accounts.google.com");

        final String combined = java.util.stream.Stream.of(youtubeCookies, accountsCookies)
                .filter(java.util.Objects::nonNull)
                .flatMap(cookies -> java.util.Arrays.stream(cookies.split("; *")))
                .distinct()
                .collect(java.util.stream.Collectors.joining("; "));

        if (combined.isEmpty()
                || !(combined.contains("SAPISID=") || combined.contains("__Secure-3PAPISID="))) {
            Toast.makeText(this, R.string.youtube_account_login_not_detected,
                    Toast.LENGTH_LONG).show();
            return;
        }

        DownloaderImpl.getInstance().setYoutubeAccountCookie(combined);

        final Context context = getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(getString(R.string.youtube_account_cookie_key), combined)
                .apply();

        Toast.makeText(this, R.string.youtube_account_login_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
