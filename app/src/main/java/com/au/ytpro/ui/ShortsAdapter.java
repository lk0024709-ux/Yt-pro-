package com.au.ytpro.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.au.ytpro.R;
import com.au.ytpro.WatchActivity;
import com.au.ytpro.data.LikesStore;
import com.au.ytpro.data.VideoItem;
import com.au.ytpro.data.YouTubeRepository;
import com.au.ytpro.player.PlayerManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vertical Shorts pager adapter: one integrated {@code PlayerView} per page
 * with auto-play/pause on swipe, tap to play/pause, double-tap-to-like with
 * an animated floating heart, and subtitles/CC disabled (see
 * {@link PlayerManager#createPlayer(Context)}).
 */
public class ShortsAdapter extends RecyclerView.Adapter<ShortsAdapter.Holder> {

    private final List<VideoItem> items = new ArrayList<>();
    private final Map<String, List<PlayerManager.Candidate>> candidateCache = new HashMap<>();
    private final Map<Integer, Holder> holders = new HashMap<>();
    private int activePosition;
    private boolean started = true;

    public void setItems(List<VideoItem> next) {
        releaseAll();
        holders.clear();
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        activePosition = 0;
        notifyDataSetChanged();
    }

    /** Called by the ViewPager2 page callback: play this page, pause the rest. */
    public void setActive(int position) {
        activePosition = position;
        for (Map.Entry<Integer, Holder> entry : holders.entrySet()) {
            if (entry.getKey() == position) {
                entry.getValue().play();
            } else {
                entry.getValue().pause();
            }
        }
    }

    public void pauseAll() {
        started = false;
        for (Holder holder : holders.values()) {
            holder.pause();
        }
    }

    public void resumeActive() {
        started = true;
        Holder holder = holders.get(activePosition);
        if (holder != null) {
            holder.play();
        }
    }

    public void releaseAll() {
        for (Holder holder : holders.values()) {
            holder.releasePlayer();
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shorts_page, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holders.put(position, holder);
        holder.bind(items.get(position));
        if (position == activePosition && started) {
            holder.play();
        } else {
            holder.pause();
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        holder.releasePlayer();
        for (Map.Entry<Integer, Holder> entry : new HashMap<>(holders).entrySet()) {
            if (entry.getValue() == holder) {
                holders.remove(entry.getKey());
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final PlayerView playerView;
        private final View gestureLayer;
        private final ImageView heart;
        private final TextView channelView;
        private final TextView titleView;
        private final ImageButton likeButton;
        private final ImageButton shareButton;
        private final ImageButton openButton;
        private final ProgressBar buffering;
        private final GestureDetector gestureDetector;

        private VideoItem item;
        private ExoPlayer player;
        private boolean prepared;
        private int fetchId;

        Holder(View view) {
            super(view);
            playerView = view.findViewById(R.id.shorts_player);
            gestureLayer = view.findViewById(R.id.gesture_layer);
            heart = view.findViewById(R.id.heart_popup);
            channelView = view.findViewById(R.id.shorts_channel);
            titleView = view.findViewById(R.id.shorts_title);
            likeButton = view.findViewById(R.id.button_like);
            shareButton = view.findViewById(R.id.button_share);
            openButton = view.findViewById(R.id.button_open);
            buffering = view.findViewById(R.id.buffering);

            gestureDetector = new GestureDetector(view.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            togglePlayPause();
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            toggleLike(true);
                            return true;
                        }
                    });
            gestureLayer.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return gestureDetector.onTouchEvent(event);
                }
            });
            likeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLike(false);
                }
            });
            shareButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    share();
                }
            });
            openButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openWatch();
                }
            });
        }

        void bind(VideoItem next) {
            fetchId++;
            releasePlayer();
            item = next;
            prepared = false;
            channelView.setText(next.getChannelName());
            titleView.setText(next.getTitle());
            syncLikeIcon();
            heart.setAlpha(0f);
            buffering.setVisibility(View.GONE);
        }

        void play() {
            if (item == null) {
                return;
            }
            ensurePlayer();
            if (prepared) {
                if (player != null) {
                    player.setPlayWhenReady(true);
                }
                return;
            }
            List<PlayerManager.Candidate> cached = candidateCache.get(item.getVideoId());
            if (cached != null) {
                prepared = true;
                PlayerManager.playCandidates(player, playerView.getContext(), cached,
                        new PlayerManager.ErrorHooks() {
                            @Override
                            public void onFatalError(
                                    androidx.media3.common.PlaybackException error) {
                                onFatal();
                            }
                        });
                player.setPlayWhenReady(true);
                return;
            }
            final int id = ++fetchId;
            buffering.setVisibility(View.VISIBLE);
            YouTubeRepository.stream(item.getUrl(),
                    new YouTubeRepository.Callback<YouTubeRepository.StreamData>() {
                        @Override
                        public void onSuccess(YouTubeRepository.StreamData data) {
                            if (id != fetchId) {
                                return;
                            }
                            candidateCache.put(item.getVideoId(), data.candidates);
                            buffering.setVisibility(View.GONE);
                            prepared = true;
                            if (player != null) {
                                PlayerManager.playCandidates(player, playerView.getContext(),
                                        data.candidates,
                                        new PlayerManager.ErrorHooks() {
                                            @Override
                                            public void onFatalError(
                                                    androidx.media3.common.PlaybackException e) {
                                                onFatal();
                                            }
                                        });
                                if (getBindingAdapterPosition() == activePosition && started) {
                                    player.setPlayWhenReady(true);
                                }
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            if (id != fetchId) {
                                return;
                            }
                            buffering.setVisibility(View.GONE);
                        }
                    });
        }

        void pause() {
            if (player != null) {
                player.setPlayWhenReady(false);
            }
        }

        void togglePlayPause() {
            if (player == null) {
                return;
            }
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0);
            }
            player.setPlayWhenReady(!player.isPlaying());
        }

        void releasePlayer() {
            if (player != null) {
                player.setPlayWhenReady(false);
                playerView.setPlayer(null);
                player.release();
                player = null;
            }
        }

        private void ensurePlayer() {
            if (player != null) {
                return;
            }
            player = PlayerManager.createPlayer(playerView.getContext());
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    buffering.setVisibility(
                            state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }
            });
            playerView.setPlayer(player);
            player.setPlayWhenReady(false);
        }

        private void onFatal() {
            buffering.setVisibility(View.GONE);
            Toast.makeText(playerView.getContext(), R.string.error_video, Toast.LENGTH_SHORT).show();
        }

        private void toggleLike(boolean withHeart) {
            if (item == null) {
                return;
            }
            boolean liked = LikesStore.toggle(itemView.getContext(), item);
            syncLikeIcon();
            if (withHeart && liked) {
                popHeart();
            }
        }

        private void syncLikeIcon() {
            if (item == null) {
                return;
            }
            boolean liked = LikesStore.isLiked(itemView.getContext(), item.getVideoId());
            likeButton.setImageResource(
                    liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        }

        /** Animated floating heart pop-up for double-tap likes. */
        private void popHeart() {
            heart.setScaleX(0f);
            heart.setScaleY(0f);
            heart.setAlpha(0f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(heart, "alpha", 0f, 1f);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(heart, "scaleX", 0f, 1.25f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(heart, "scaleY", 0f, 1.25f, 1f);
            AnimatorSet pop = new AnimatorSet();
            pop.playTogether(alpha, scaleX, scaleY);
            pop.setDuration(350);
            pop.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    heart.animate().alpha(0f).setStartDelay(250).setDuration(300).start();
                }
            });
            pop.start();
        }

        private void share() {
            if (item == null) {
                return;
            }
            Context context = itemView.getContext();
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, item.getTitle() + " " + item.getUrl());
            context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.action_share)));
        }

        private void openWatch() {
            if (item == null) {
                return;
            }
            WatchActivity.start(itemView.getContext(), item);
        }
    }
}
