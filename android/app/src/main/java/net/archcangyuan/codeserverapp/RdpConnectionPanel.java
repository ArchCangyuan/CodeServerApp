package net.archcangyuan.codeserverapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Connection panel for {@code rdp://host} addresses: Cloudflare Access sign-in,
 * the saved Windows user name, and a local tunnel that remote desktop clients
 * connect to.
 */
final class RdpConnectionPanel {
    private static final int ACCENT = Color.rgb(103, 80, 164);
    private static final int SIGNED_IN = Color.rgb(46, 125, 50);
    private static final int MUTED = Color.rgb(96, 96, 96);
    private static final String ACCESS_COOKIE = "CF_Authorization";
    private static final long LOGIN_POLL_MS = 700L;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private String host;
    private TextView accessStatus;
    private Button accessButton;
    private EditText usernameField;
    private TextView tunnelStatus;
    private Button copyButton;
    private boolean openClientWhenRunning;

    RdpConnectionPanel(Activity activity) {
        this.activity = activity;
    }

    static boolean isRdpAddress(String address) {
        return address != null && address.trim().toLowerCase(Locale.US).startsWith("rdp://");
    }

    /** Normalizes {@code rdp://[user@]host[:port][/...]} to {@code rdp://[user@]host}. */
    static String normalize(String address) {
        String rest = address.trim().substring("rdp://".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        return "rdp://" + rest;
    }

    static String hostOf(String address) {
        String rest = normalize(address).substring("rdp://".length());
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.toLowerCase(Locale.US);
    }

    static String userOf(String address) {
        String rest = normalize(address).substring("rdp://".length());
        int at = rest.lastIndexOf('@');
        if (at <= 0) {
            return "";
        }
        try {
            return URLDecoder.decode(rest.substring(0, at), StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return rest.substring(0, at);
        }
    }

    void show(String address) {
        String requestedHost = hostOf(address);
        if (requestedHost.isEmpty()) {
            Toast.makeText(activity, "Enter a host, e.g. rdp://desktop.example.com", Toast.LENGTH_SHORT)
                .show();
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        host = requestedHost;
        String addressUser = userOf(address);
        if (!addressUser.isEmpty()) {
            AccessTokenStore.saveUsername(activity, host, addressUser);
        }
        openClientWhenRunning = false;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView subtitle = new TextView(activity);
        subtitle.setText("Remote Desktop through Cloudflare Access");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(13);
        content.addView(subtitle);

        LinearLayout accessCard = card(content, "Cloudflare");
        LinearLayout accessRow = row(accessCard);
        accessStatus = statusText(accessRow);
        accessButton = smallButton(accessRow, "Sign in");
        accessButton.setOnClickListener(view -> {
            if (AccessTokenStore.loadToken(activity, host) != null) {
                signOut();
            } else {
                showLogin(null);
            }
        });

        LinearLayout userCard = card(content, "Windows user");
        usernameField = new EditText(activity);
        usernameField.setSingleLine(true);
        usernameField.setTextSize(15);
        usernameField.setHint("User name, e.g. DOMAIN\\user");
        usernameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        usernameField.setText(AccessTokenStore.username(activity, host));
        userCard.addView(usernameField);

        LinearLayout tunnelCard = card(content, "Tunnel");
        LinearLayout tunnelRow = row(tunnelCard);
        tunnelStatus = statusText(tunnelRow);
        copyButton = smallButton(tunnelRow, "Copy");
        copyButton.setOnClickListener(view -> copyLocalAddress(true));

        dialog = new AlertDialog.Builder(activity)
            .setTitle(boldText(host))
            .setView(content)
            .setPositiveButton("Connect", null)
            .setNeutralButton("Stop tunnel", null)
            .setNegativeButton("Close", null)
            .create();
        dialog.setOnShowListener(shown -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> connect());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                openClientWhenRunning = false;
                RdpTunnelService.stop(activity);
            });
        });
        dialog.setOnDismissListener(dismissed -> {
            saveUsername();
            RdpTunnelService.setStateListener(null);
            openClientWhenRunning = false;
        });
        RdpTunnelService.setStateListener(this::onTunnelStateChanged);
        dialog.show();
        refresh();
    }

    /** Re-attaches to tunnel updates after the activity resumes. */
    void onResume() {
        if (dialog != null && dialog.isShowing()) {
            RdpTunnelService.setStateListener(this::onTunnelStateChanged);
            refresh();
        }
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void connect() {
        saveUsername();
        if (AccessTokenStore.loadToken(activity, host) == null) {
            showLogin(this::connect);
            return;
        }
        if (RdpTunnelService.isRunning(host)) {
            openClient();
            return;
        }
        openClientWhenRunning = true;
        RdpTunnelService.start(activity, host);
        refresh();
    }

    private void onTunnelStateChanged() {
        if (openClientWhenRunning && RdpTunnelService.isRunning(host)) {
            openClientWhenRunning = false;
            openClient();
        }
        if (RdpTunnelService.loginRequired() && dialog != null && dialog.isShowing()) {
            Toast.makeText(activity, "Cloudflare sign-in expired", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void refresh() {
        if (dialog == null || host == null) {
            return;
        }
        String token = AccessTokenStore.loadToken(activity, host);
        if (token != null) {
            long expiresAt = AccessTokenStore.expiresAtMillis(token);
            accessStatus.setText(expiresAt > 0
                ? "Signed in · expires in " + formatRemaining(expiresAt - System.currentTimeMillis())
                : "Signed in");
            accessStatus.setTextColor(SIGNED_IN);
            accessButton.setText("Sign out");
        } else {
            accessStatus.setText("Not signed in");
            accessStatus.setTextColor(MUTED);
            accessButton.setText("Sign in");
        }

        boolean running = RdpTunnelService.isRunning(host);
        if (running) {
            int connections = RdpTunnelService.activeConnections();
            tunnelStatus.setText("127.0.0.1:" + RdpTunnelService.runningPort()
                + (connections > 0
                    ? " · " + connections + (connections == 1 ? " connection" : " connections")
                    : " · waiting for a client"));
            tunnelStatus.setTextColor(SIGNED_IN);
        } else if (openClientWhenRunning) {
            tunnelStatus.setText("Starting…");
            tunnelStatus.setTextColor(MUTED);
        } else {
            String error = RdpTunnelService.lastError();
            tunnelStatus.setText(error != null ? "Off · " + error : "Off");
            tunnelStatus.setTextColor(MUTED);
        }
        copyButton.setEnabled(running);
        Button stopButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (stopButton != null) {
            stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        }
    }

    private void saveUsername() {
        if (usernameField != null && host != null) {
            AccessTokenStore.saveUsername(activity, host, usernameField.getText().toString());
        }
    }

    private String copyLocalAddress(boolean announce) {
        String address = "127.0.0.1:" + RdpTunnelService.runningPort();
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Remote desktop address", address));
        }
        if (announce) {
            Toast.makeText(activity, "Copied " + address, Toast.LENGTH_SHORT).show();
        }
        return address;
    }

    /** Opens the installed remote desktop client (e.g. Microsoft Windows App) on the tunnel. */
    private void openClient() {
        String address = copyLocalAddress(false);
        String username = AccessTokenStore.username(activity, host);
        String uri = "rdp://full%20address=s:" + address
            + (username.isEmpty() ? "" : "&username=s:" + Uri.encode(username));
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(
                activity,
                "Copied " + address + ". Add it as a PC in your remote desktop app "
                    + "(e.g. Microsoft Windows App).",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void signOut() {
        AccessTokenStore.clearToken(activity, host);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setCookie("https://" + host, ACCESS_COOKIE + "=; Max-Age=0; Path=/");
        cookies.flush();
        refresh();
    }

    /**
     * Signs in to Cloudflare Access in an embedded page (e.g. with an emailed
     * one-time code) and captures the resulting {@code CF_Authorization} token.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void showLogin(Runnable onSignedIn) {
        Dialog loginDialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(8), dp(10));
        header.setBackgroundColor(Color.rgb(243, 243, 243));
        TextView title = new TextView(activity);
        title.setText("Sign in to Cloudflare · " + host);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15);
        title.setTextColor(Color.BLACK);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button cancel = smallButton(header, "Cancel");
        root.addView(header);

        WebView webView = new WebView(activity);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        CookieManager.getInstance().setAcceptCookie(true);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        loginDialog.setContentView(root);

        String origin = "https://" + host;
        String previousToken = accessCookie(origin);
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            String token = accessCookie(origin);
            long expiresAt = token == null ? 0L : AccessTokenStore.expiresAtMillis(token);
            boolean fresh = token != null
                && !token.equals(previousToken)
                && (expiresAt == 0L || expiresAt > System.currentTimeMillis() + 60_000L);
            boolean stillValid = token != null
                && token.equals(previousToken)
                && expiresAt > System.currentTimeMillis() + 60_000L;
            if (fresh || stillValid) {
                AccessTokenStore.saveToken(activity, host, token);
                CookieManager.getInstance().flush();
                loginDialog.dismiss();
                refresh();
                Toast.makeText(activity, "Signed in to Cloudflare", Toast.LENGTH_SHORT).show();
                if (onSignedIn != null) {
                    onSignedIn.run();
                }
                return;
            }
            handler.postDelayed(poll[0], LOGIN_POLL_MS);
        };
        cancel.setOnClickListener(view -> loginDialog.dismiss());
        loginDialog.setOnDismissListener(dismissed -> {
            handler.removeCallbacks(poll[0]);
            webView.stopLoading();
            webView.destroy();
        });
        loginDialog.show();
        webView.loadUrl(origin + "/");
        handler.postDelayed(poll[0], LOGIN_POLL_MS);
    }

    private static String accessCookie(String origin) {
        String cookies = CookieManager.getInstance().getCookie(origin);
        if (cookies == null) {
            return null;
        }
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(ACCESS_COOKIE + "=")) {
                String value = trimmed.substring(ACCESS_COOKIE.length() + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String formatRemaining(long millis) {
        long minutes = Math.max(0L, millis / 60_000L);
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        return minutes % 60 == 0 ? hours + " h" : hours + " h " + (minutes % 60) + " min";
    }

    private LinearLayout card(LinearLayout parent, String label) {
        TextView heading = new TextView(activity);
        heading.setText(label.toUpperCase(Locale.US));
        heading.setTextSize(11);
        heading.setLetterSpacing(0.08f);
        heading.setTextColor(MUTED);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(16), 0, dp(6));
        parent.addView(heading);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(8), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(243, 243, 243));
        background.setCornerRadius(dp(12));
        card.setBackground(background);
        parent.addView(card);
        return card;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row);
        return row;
    }

    private TextView statusText(LinearLayout parent) {
        TextView text = new TextView(activity);
        text.setTextSize(14);
        parent.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return text;
    }

    private Button smallButton(LinearLayout parent, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(ACCENT);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        parent.addView(button);
        return button;
    }

    private CharSequence boldText(String text) {
        android.text.SpannableString styled = new android.text.SpannableString(text);
        styled.setSpan(
            new android.text.style.StyleSpan(Typeface.BOLD),
            0,
            text.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return styled;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
