package com.au.ytpro;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Launcher screen. Shows the YT Pro brand and attribution, then hands over to
 * {@link MainActivity} which owns the WebView.
 *
 * <p>The delay is deliberately short: it exists so the brand is visible on cold
 * start, not to slow the user down. Relaunching the launcher icon while the app
 * is already alive goes straight to {@link MainActivity} because it is declared
 * {@code singleTask} and we pass {@code CLEAR_TOP | SINGLE_TOP}.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable launchMainActivity = this::openMainActivity;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        TextView versionView = findViewById(R.id.splash_version);
        versionView.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        handler.postDelayed(launchMainActivity, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(launchMainActivity);
        super.onDestroy();
    }

    private void openMainActivity() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
