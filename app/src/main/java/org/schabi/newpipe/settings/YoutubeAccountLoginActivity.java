package org.schabi.newpipe.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

/**
 * Lets the user log into their Google/YouTube account through a WebView. After the user
 * finishes signing in and taps "Save login and close", the cookies set by the WebView
 * (including {@code SAPISID}) are captured and stored so that {@link DownloaderImpl} can
 * attach an authenticated {@code Authorization: SAPISIDHASH} header to YouTube requests,
 * allowing extraction of age-restricted videos that require a logged-in account.
 */
public class YoutubeAccountLoginActivity extends AppCompatActivity {
    private static final String LOGIN_URL =
            "https://accounts.google.com/ServiceLogin"
                    + "?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F";

    /**
     * Google's sign-in page rejects Android WebViews outright with
     * "This browser or app may not be secure" unless the WebView is made to look like a
     * regular desktop Chrome browser. Since Android System WebView is itself Chromium-based,
     * a desktop Chrome UA (rather than e.g. Firefox's, which the app's normal extraction
     * requests use) keeps the reported UA consistent with the engine's actual JS/DOM
     * capabilities, which is what Google's login page fingerprinting checks against.
     */
    private static final String LOGIN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
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

        webView = findViewById(R.id.youtubeLoginWebView);
        final WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUserAgentString(LOGIN_USER_AGENT);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // The Android WebView automatically attaches an "X-Requested-With: <package name>"
        // header to every request, which Google's sign-in flow uses to detect (and reject)
        // embedded WebViews with "This browser or app may not be secure". Telling WebView to
        // send that header to no origins removes the signal Google checks for.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(
                    webSettings, java.util.Collections.emptySet());
        }

        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(LOGIN_URL);

        findViewById(R.id.youtubeLoginDoneButton).setOnClickListener(v -> saveCookiesAndFinish());
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
