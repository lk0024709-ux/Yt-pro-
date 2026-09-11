package com.au.ytpro;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.au.ytpro.data.LikesStore;
import com.au.ytpro.data.SubscriptionsStore;
import com.au.ytpro.data.VideoItem;
import com.au.ytpro.data.YouTubeRepository;
import com.au.ytpro.player.PlayerManager;
import com.au.ytpro.ui.VideoAdapter;
import com.au.ytpro.util.FormatUtils;
import com.bumptech.glide.Glide;

/**
 * Native watch screen: an ExoPlayer {@link PlayerView} with the styled
 * controls (seekbar), a double-tap layer (left = −10s, right = +10s,
 * centre = play/pause), a fullscreen toggle, channel actions (subscribe /
 * like / share) and the "Up next" recommendation list below.
 */
public class WatchActivity extends AppCompatActivity {

    private static final String EXTRA_URL = "extra_url";
    private static final String EXTRA_VIDEO_ID = "extra_video_id";
    private static final String EXTRA_TITLE = "extra_title";
    private static final String EXTRA_CHANNEL = "extra_channel";
    private static final String EXTRA_CHANNEL_URL = "extra_channel_url";
    private static final String EXTRA_THUMB = "extra_thumb";
    private static final String EXTRA_AVATAR = "extra_avatar";

    private static final long DOUBLE_TAP_MAX_INTERVAL_MS = 300L;
    private static final long SEEK_STEP_MS = 10_000L;
    private static final int PLAYER_HEIGHT_DP = 220;

    /** Opens the watch screen for one video. */
    public static void start(Context context, @NonNull VideoItem item) {
        Intent intent = new Intent(context, WatchActivity.class);
        intent.putExtra(EXTRA_URL, item.getUrl());
        intent.putExtra(EXTRA_VIDEO_ID, item.getVideoId());
        intent.putExtra(EXTRA_TITLE, item.getTitle());
        intent.putExtra(EXTRA_CHANNEL, item.getChannelName());
        intent.putExtra(EXTRA_CHANNEL_URL, item.getChannelUrl());
        intent.putExtra(EXTRA_THUMB, item.getThumbnailUrl());
        intent.putExtra(EXTRA_AVATAR, item.getAvatarUrl());
        context.startActivity(intent);
    }

    private ExoPlayer player;
    private PlayerView playerView;
    private View playerContainer;
    private View scrollContent;
    private View gestureLayer;
    private TextView seekBadge;
    private ProgressBar buffering;

    private TextView videoTitle;
    private TextView videoMeta;
    private TextView channelName;
    private ImageView channelAvatar;
    private TextView videoDescription;
    private Button subscribeButton;
    private Button likeButton;
    private VideoAdapter relatedAdapter;

    /** Best-known metadata; replaced by the extractor result when it lands. */
    private VideoItem currentItem;
    private boolean isFullscreen;
    private boolean wasPlaying;
    private int orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private long lastTapUpAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);

        Intent intent = getIntent();
        currentItem = new VideoItem(
                stringExtra(intent, EXTRA_URL),
                stringExtra(intent, EXTRA_VIDEO_ID),
                stringExtra(intent, EXTRA_TITLE),
                stringExtra(intent, EXTRA_CHANNEL),
                stringExtra(intent, EXTRA_CHANNEL_URL),
                -1, -1,
                stringExtra(intent, EXTRA_THUMB),
                stringExtra(intent, EXTRA_AVATAR),
                stringExtra(intent, EXTRA_URL).contains("/shorts/"),
                "");

        playerView = findViewById(R.id.watch_player);
        playerContainer = findViewById(R.id.player_container);
        scrollContent = findViewById(R.id.scroll_content);
        gestureLayer = findViewById(R.id.gesture_layer);
        seekBadge = findViewById(R.id.seek_badge);
        buffering = findViewById(R.id.buffering);

        videoTitle = findViewById(R.id.video_title);
        videoMeta = findViewById(R.id.video_meta);
        channelName = findViewById(R.id.channel_name);
        channelAvatar = findViewById(R.id.channel_avatar);
        videoDescription = findViewById(R.id.video_description);
        subscribeButton = findViewById(R.id.button_subscribe);
        likeButton = findViewById(R.id.button_like);

        if (!currentItem.getTitle().isEmpty()) {
            videoTitle.setText(currentItem.getTitle());
        }
        channelName.setText(currentItem.getChannelName());
        syncSubscribeButton();
        syncLikeButton();

        RecyclerView relatedList = findViewById(R.id.related_list);
        relatedList.setLayoutManager(new LinearLayoutManager(this));
        relatedAdapter = new VideoAdapter(R.layout.item_related_video,
                new VideoAdapter.Listener() {
                    @Override
                    public void onVideoClicked(VideoItem item) {
                        WatchActivity.start(WatchActivity.this, item);
                    }
                });
        relatedList.setAdapter(relatedAdapter);

        player = PlayerManager.createPlayer(this);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                buffering.setVisibility(
                        state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
        });
        playerView.setPlayer(player);

        gestureLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Only the second tap of a double-tap is consumed; every
                // other touch falls through to the PlayerView controller
                // (seekbar, play/pause) underneath this transparent layer.
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastTapUpAt < DOUBLE_TAP_MAX_INTERVAL_MS) {
                        lastTapUpAt = 0;
                        handleDoubleTap(event.getX(), v.getWidth());
                        playerView.showController();
                        return true;
                    }
                    lastTapUpAt = now;
                }
                return false;
            }
        });

        ImageButton fullscreenButton = findViewById(R.id.button_fullscreen);
        fullscreenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isFullscreen) {
                    exitFullscreen();
                } else {
                    enterFullscreen();
                }
            }
        });

        subscribeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSubscribe();
            }
        });
        likeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLike();
            }
        });
        findViewById(R.id.button_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });

        load();
    }

    private static String stringExtra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void load() {
        buffering.setVisibility(View.VISIBLE);
        YouTubeRepository.stream(currentItem.getUrl(),
                new YouTubeRepository.Callback<YouTubeRepository.StreamData>() {
                    @Override
                    public void onSuccess(YouTubeRepository.StreamData data) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        buffering.setVisibility(View.GONE);
                        bind(data);
                    }

                    @Override
                    public void onError(Exception e) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        buffering.setVisibility(View.GONE);
                        videoTitle.setText(R.string.error_video);
                        Toast.makeText(WatchActivity.this,
                                R.string.error_video, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void bind(YouTubeRepository.StreamData data) {
        currentItem = new VideoItem(
                currentItem.getUrl(),
                currentItem.getVideoId(),
                data.title,
                data.channelName,
                data.channelUrl,
                data.viewCount,
                data.durationSec,
                currentItem.getThumbnailUrl(),
                data.avatarUrl,
                currentItem.isShortForm(),
                data.uploadedText);

        videoTitle.setText(data.title);
        videoMeta.setText(FormatUtils.metaLine(
                data.channelName, data.viewCount, data.uploadedText));
        channelName.setText(data.channelName);
        if (data.description.isEmpty()) {
            videoDescription.setVisibility(View.GONE);
        } else {
            videoDescription.setVisibility(View.VISIBLE);
            videoDescription.setText(data.description);
        }
        if (!data.avatarUrl.isEmpty()) {
            Glide.with(channelAvatar)
                    .load(data.avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.bg_thumb_placeholder)
                    .into(channelAvatar);
        }
        syncSubscribeButton();
        syncLikeButton();
        relatedAdapter.setItems(data.related);

        PlayerManager.playCandidates(player, this, data.candidates,
                new PlayerManager.ErrorHooks() {
                    @Override
                    public void onFatalError(
                            androidx.media3.common.PlaybackException error) {
                        buffering.setVisibility(View.GONE);
                        Toast.makeText(WatchActivity.this,
                                R.string.error_video, Toast.LENGTH_SHORT).show();
                    }
                });
        player.setPlayWhenReady(true);
    }

    // ------------------------------------------------------------------
    // Double-tap gestures (10s seek / play-pause)
    // ------------------------------------------------------------------

    private void handleDoubleTap(float x, int width) {
        if (player == null || width <= 0) {
            return;
        }
        if (x < width * 0.35f) {
            seekBy(-SEEK_STEP_MS);
        } else if (x > width * 0.65f) {
            seekBy(SEEK_STEP_MS);
        } else {
            togglePlayPause();
        }
    }

    private void seekBy(long deltaMs) {
        long position = player.getCurrentPosition() + deltaMs;
        long duration = player.getDuration();
        if (duration > 0) {
            position = Math.max(0, Math.min(position, duration));
        } else {
            position = Math.max(0, position);
        }
        player.seekTo(position);
        seekBadge.setText(deltaMs > 0
                ? R.string.seek_forward_badge : R.string.seek_backward_badge);
        seekBadge.animate().cancel();
        seekBadge.setAlpha(1f);
        seekBadge.setVisibility(View.VISIBLE);
        seekBadge.animate().alpha(0f).setStartDelay(600).setDuration(300).start();
    }

    private void togglePlayPause() {
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
        }
        player.setPlayWhenReady(!player.isPlaying());
    }

    // ------------------------------------------------------------------
    // Channel actions
    // ------------------------------------------------------------------

    private void toggleSubscribe() {
        String channelUrl = currentItem.getChannelUrl();
        if (channelUrl.isEmpty()) {
            return;
        }
        String channel = currentItem.getChannelName().isEmpty()
                ? channelUrl : currentItem.getChannelName();
        if (SubscriptionsStore.isSubscribed(this, channelUrl)) {
            SubscriptionsStore.unsubscribe(this, channelUrl);
            Toast.makeText(this,
                    getString(R.string.unsubscribed_from, channel),
                    Toast.LENGTH_SHORT).show();
        } else {
            SubscriptionsStore.subscribe(this, channelUrl,
                    currentItem.getChannelName(), currentItem.getAvatarUrl());
            Toast.makeText(this,
                    getString(R.string.subscribed_to, channel),
                    Toast.LENGTH_SHORT).show();
        }
        syncSubscribeButton();
    }

    private void syncSubscribeButton() {
        boolean subscribed = SubscriptionsStore.isSubscribed(
                this, currentItem.getChannelUrl());
        subscribeButton.setText(subscribed
                ? R.string.action_subscribed : R.string.action_subscribe);
    }

    private void toggleLike() {
        LikesStore.toggle(this, currentItem);
        syncLikeButton();
    }

    private void syncLikeButton() {
        boolean liked = LikesStore.isLiked(this, currentItem.getVideoId());
        likeButton.setCompoundDrawablesWithIntrinsicBounds(
                liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart, 0, 0, 0);
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,
                currentItem.getTitle() + " " + currentItem.getUrl());
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)));
    }

    // ------------------------------------------------------------------
    // Fullscreen
    // ------------------------------------------------------------------

    private void enterFullscreen() {
        isFullscreen = true;
        orientationBeforeFullscreen = getRequestedOrientation();
        scrollContent.setVisibility(View.GONE);

        ViewGroup.LayoutParams containerParams = playerContainer.getLayoutParams();
        containerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        playerContainer.setLayoutParams(containerParams);
        ViewGroup.LayoutParams playerParams = playerView.getLayoutParams();
        playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        playerView.setLayoutParams(playerParams);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode(true);
    }

    private void exitFullscreen() {
        isFullscreen = false;
        scrollContent.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams containerParams = playerContainer.getLayoutParams();
        containerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        playerContainer.setLayoutParams(containerParams);
        ViewGroup.LayoutParams playerParams = playerView.getLayoutParams();
        playerParams.height = (int) (PLAYER_HEIGHT_DP
                * getResources().getDisplayMetrics().density);
        playerView.setLayoutParams(playerParams);

        setRequestedOrientation(orientationBeforeFullscreen);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode(false);
    }

    private void applyImmersiveMode(boolean enabled) {
        View decorView = getWindow().getDecorView();
        if (enabled) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isFullscreen) {
            applyImmersiveMode(true);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        playerView.onResume();
        if (wasPlaying && player != null) {
            player.play();
        }
    }

    @Override
    protected void onPause() {
        wasPlaying = player != null && player.isPlaying();
        if (player != null) {
            player.pause();
        }
        playerView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
