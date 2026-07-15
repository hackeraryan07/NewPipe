74a2cab51764e6f8b971861d607782476bcd5a1b
package org.schabi.newpipe;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.util.InfoCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class DownloaderImpl extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE_KEY =
            "youtube_restricted_mode_key";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
    public static final String YOUTUBE_DOMAIN = "youtube.com";
    public static final String YOUTUBE_ACCOUNT_COOKIE_KEY =
            "youtube_account_cookie_key";
    private static final String YOUTUBE_ORIGIN = "https://www.youtube.com";

    private static DownloaderImpl instance;
    private final Map<String, String> mCookies;
    private final OkHttpClient client;

    private DownloaderImpl(final OkHttpClient.Builder builder) {
        this.client = builder
                .readTimeout(30, TimeUnit.SECONDS)
//                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"),
//                        16 * 1024 * 1024))
                .build();
        this.mCookies = new HashMap<>();
    }

    @NonNull
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * It's recommended to call exactly once in the entire lifetime of the application.
     *
     * @param builder if null, default builder will be used
     * @return a new instance of {@link DownloaderImpl}
     */
    public static DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
        instance = new DownloaderImpl(
                builder != null ? builder : new OkHttpClient.Builder());
        return instance;
    }

    public static DownloaderImpl getInstance() {
        return instance;
    }

    public String getCookies(final String url) {
        final String youtubeCookie = url.contains(YOUTUBE_DOMAIN)
                ? getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY) : null;
        final String youtubeAccountCookie = url.contains(YOUTUBE_DOMAIN)
                ? getCookie(YOUTUBE_ACCOUNT_COOKIE_KEY) : null;

        // Recaptcha cookie is always added TODO: not sure if this is necessary
        return Stream.of(youtubeCookie, youtubeAccountCookie,
                        getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY))
                .filter(Objects::nonNull)
                .flatMap(cookies -> Arrays.stream(cookies.split("; *")))
                .distinct()
                .collect(Collectors.joining("; "));
    }

    /**
     * Store the cookies obtained after logging into YouTube through a WebView, so that
     * age-restricted videos that require a logged-in account can be extracted.
     *
     * @param cookies the raw "name=value; name2=value2" cookie string captured from the WebView
     */
    public void setYoutubeAccountCookie(@Nullable final String cookies) {
        if (cookies == null || cookies.isEmpty()) {
            removeCookie(YOUTUBE_ACCOUNT_COOKIE_KEY);
        } else {
            setCookie(YOUTUBE_ACCOUNT_COOKIE_KEY, cookies);
        }
        InfoCache.getInstance().clearCache();
    }

    public String getYoutubeAccountCookie() {
        return getCookie(YOUTUBE_ACCOUNT_COOKIE_KEY);
    }

    public boolean isYoutubeAccountLoggedIn() {
        final String cookie = getYoutubeAccountCookie();
        return cookie != null
                && (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID="));
    }

    /**
     * Build the {@code Authorization: SAPISIDHASH ...} header YouTube requires on requests
     * made with a logged-in account's cookies, using the well-documented SAPISID hash scheme
     * (see https://stackoverflow.com/a/32065323). Returns {@code null} when no account is
     * logged in.
     */
    @Nullable
    private static String generateSapisidHashHeader(final String cookieHeader) {
        String sapisid = extractCookieValue(cookieHeader, "SAPISID");
        if (sapisid == null) {
            sapisid = extractCookieValue(cookieHeader, "__Secure-3PAPISID");
        }
        if (sapisid == null) {
            return null;
        }

        final long timestampSeconds = System.currentTimeMillis() / 1000L;
        final String input = timestampSeconds + " " + sapisid + " " + YOUTUBE_ORIGIN;

        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            final byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hexHash = new StringBuilder();
            for (final byte b : hashBytes) {
                hexHash.append(String.format("%02x", b));
            }
            return "SAPISIDHASH " + timestampSeconds + "_" + hexHash;
        } catch (final NoSuchAlgorithmException e) {
            return null;
        }
    }

    @Nullable
    private static String extractCookieValue(final String cookieHeader, final String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (final String part : cookieHeader.split("; *")) {
            final int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public String getCookie(final String key) {
        return mCookies.get(key);
    }

    public void setCookie(final String key, final String cookie) {
        mCookies.put(key, cookie);
    }

    public void removeCookie(final String key) {
        mCookies.remove(key);
    }

    public void updateYoutubeRestrictedModeCookies(final Context context) {
        final String restrictedModeEnabledKey =
                context.getString(R.string.youtube_restricted_mode_enabled);
        final boolean restrictedModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(restrictedModeEnabledKey, false);
        updateYoutubeRestrictedModeCookies(restrictedModeEnabled);
    }

    public void updateYoutubeRestrictedModeCookies(final boolean youtubeRestrictedModeEnabled) {
        if (youtubeRestrictedModeEnabled) {
            setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
                    YOUTUBE_RESTRICTED_MODE_COOKIE);
        } else {
            removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        }
        InfoCache.getInstance().clearCache();
    }

    /**
     * Get the size of the content that the url is pointing by firing a HEAD request.
     *
     * @param url an url pointing to the content
     * @return the size of the content, in bytes
     */
    public long getContentLength(final String url) throws IOException {
        try {
            final Response response = head(url);
            return Long.parseLong(response.getHeader("Content-Length"));
        } catch (final NumberFormatException e) {
            throw new IOException("Invalid content length", e);
        } catch (final ReCaptchaException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Response execute(@NonNull final Request request)
            throws IOException, ReCaptchaException {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(dataToSend);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)
                .addHeader("User-Agent", USER_AGENT);

        final String cookies = getCookies(url);
        if (!cookies.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookies);
        }

        if (url.contains(YOUTUBE_DOMAIN) && isYoutubeAccountLoggedIn()) {
            final String sapisidHashHeader = generateSapisidHashHeader(cookies);
            if (sapisidHashHeader != null) {
                requestBuilder.addHeader("Authorization", sapisidHashHeader);
                requestBuilder.addHeader("X-Origin", YOUTUBE_ORIGIN);
                requestBuilder.addHeader("X-Goog-AuthUser", "0");
            }
        }

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue ->
                    requestBuilder.addHeader(headerName, headerValue));
        });

        try (
                okhttp3.Response response = client.newCall(requestBuilder.build()).execute()
        ) {
            if (response.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                responseBodyToReturn = body.string();
            }

            final String latestUrl = response.request().url().toString();
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl);
        }
    }
}

