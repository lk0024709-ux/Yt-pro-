package com.au.ytpro;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.au.ytpro.ui.HomeFragment;
import com.au.ytpro.ui.LibraryFragment;
import com.au.ytpro.ui.ShortsFragment;
import com.au.ytpro.ui.SubscriptionsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Native home screen: a {@link BottomNavigationView} with four tabs —
 * Home (trending + search), Shorts (vertical pager), Subscriptions
 * (local subs feed) and Library (about + settings + cache).
 *
 * <p>Back navigates to Home first and requires a double press to exit.
 */
public class MainActivity extends AppCompatActivity {

    private static final long EXIT_CONFIRM_WINDOW_MS = 2000L;
    private static final String STATE_TAB = "tab";

    private BottomNavigationView bottomNav;
    private int currentTab = R.id.nav_home;
    private long lastBackPressAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_home);
        }
        bottomNav.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId());
            return true;
        });
        if (bottomNav.getSelectedItemId() != currentTab) {
            // Fires the listener above, which swaps the fragment.
            bottomNav.setSelectedItemId(currentTab);
        } else {
            switchTab(currentTab);
        }
    }

    private void switchTab(int id) {
        currentTab = id;
        Fragment fragment;
        if (id == R.id.nav_shorts) {
            fragment = new ShortsFragment();
        } else if (id == R.id.nav_subscriptions) {
            fragment = new SubscriptionsFragment();
        } else if (id == R.id.nav_library) {
            fragment = new LibraryFragment();
        } else {
            fragment = new HomeFragment();
        }
        // Shorts is immersive fullscreen; the other tabs keep the brand bar.
        View topBar = findViewById(R.id.top_bar);
        topBar.setVisibility(id == R.id.nav_shorts ? View.GONE : View.VISIBLE);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (currentTab != R.id.nav_home) {
            bottomNav.setSelectedItemId(R.id.nav_home);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
            super.onBackPressed();
            return;
        }
        lastBackPressAt = now;
        Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }
}
