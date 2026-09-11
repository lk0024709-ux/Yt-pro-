package com.au.ytpro.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.au.ytpro.R;
import com.au.ytpro.data.VideoItem;
import com.au.ytpro.util.FormatUtils;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/**
 * Card feed adapter shared by Home, Subscriptions, Library and the Watch
 * "Up next" list. Both card layouts expose the same view ids, so the layout
 * resource is the only thing that differs.
 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.Holder> {

    /** Tap on a card. */
    public interface Listener {
        void onVideoClicked(VideoItem item);
    }

    /** Long-press on a card (optional; used to unsubscribe). */
    public interface LongListener {
        void onVideoLongClicked(VideoItem item);
    }

    private final int layoutRes;
    private final Listener listener;
    private final List<VideoItem> items = new ArrayList<>();
    private LongListener longListener;

    public VideoAdapter(@LayoutRes int layoutRes, Listener listener) {
        this.layoutRes = layoutRes;
        this.listener = listener;
    }

    public void setLongListener(LongListener longListener) {
        this.longListener = longListener;
    }

    public void setItems(List<VideoItem> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final VideoItem item = items.get(position);
        holder.title.setText(item.getTitle());
        holder.meta.setText(FormatUtils.metaLine(item));
        holder.duration.setText(FormatUtils.formatDuration(item.getDurationSec()));

        Glide.with(holder.thumbnail)
                .load(item.getThumbnailUrl())
                .placeholder(R.drawable.bg_thumb_placeholder)
                .error(R.drawable.bg_thumb_placeholder)
                .into(holder.thumbnail);

        if (holder.avatar.getVisibility() == View.VISIBLE) {
            if (item.getAvatarUrl().isEmpty()) {
                holder.avatar.setImageResource(R.drawable.bg_thumb_placeholder);
            } else {
                Glide.with(holder.avatar)
                        .load(item.getAvatarUrl())
                        .circleCrop()
                        .placeholder(R.drawable.bg_thumb_placeholder)
                        .into(holder.avatar);
            }
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onVideoClicked(item);
            }
        });
        if (longListener == null) {
            holder.itemView.setOnLongClickListener(null);
        } else {
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    longListener.onVideoLongClicked(item);
                    return true;
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView duration;
        final ImageView avatar;
        final TextView title;
        final TextView meta;

        Holder(View view) {
            super(view);
            thumbnail = view.findViewById(R.id.thumbnail);
            duration = view.findViewById(R.id.duration_badge);
            avatar = view.findViewById(R.id.channel_avatar);
            title = view.findViewById(R.id.video_title);
            meta = view.findViewById(R.id.video_meta);
        }
    }
}
