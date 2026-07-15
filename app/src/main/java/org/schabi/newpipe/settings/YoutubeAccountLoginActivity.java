package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

/**
 * Lets the user attach a YouTube/Google login session to the app so that
 * {@link DownloaderImpl} can attach an authenticated {@code Authorization: SAPISIDHASH} header
 * to YouTube requests, allowing extraction of age-restricted videos that require a logged-in
 * account.
 *
 * <p>Google actively blocks its sign-in page from working inside any embedded WebView (this
 * has been an enforced server-side policy since September 2021, see
 * <a href="https://developers.googleblog.com/upcoming-security-changes-to-googles-oauth-20-authorization-endpoint-in-embedded-webviews/">
 * Google's announcement</a>) as an anti man-in-the-middle protection, so an in-app WebView
 * login screen cannot work reliably no matter how it is configured. Instead, this screen sends
 * the user to their real, full browser to sign in, then has them paste back the resulting
 * {@code Cookie} header value, which is the same technique used by other cookie-based YouTube
 * clients.</p>
 */
public class YoutubeAccountLoginActivity extends AppCompatActivity {
    private static final String LOGIN_URL =
            "https://accounts.google.com/ServiceLogin"
                    + "?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F";

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

        final Button openBrowserButton = findViewById(R.id.youtubeLoginOpenBrowserButton);
        openBrowserButton.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LOGIN_URL))));

        findViewById(R.id.youtubeLoginDoneButton).setOnClickListener(v -> saveCookieAndFinish());
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveCookieAndFinish() {
        final EditText cookieInput = findViewById(R.id.youtubeLoginCookieInput);
        final String pasted = cookieInput.getText() == null
                ? "" : cookieInput.getText().toString().trim();

        // Users sometimes paste "cookie: <value>" straight out of dev tools; strip the prefix.
        final String combined = pasted.replaceFirst("(?i)^cookie:\\s*", "");

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
