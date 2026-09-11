package com.au.ytpro.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local (on-device) channel subscriptions. The native client has no Google
 * sign-in, so subscriptions persist in SharedPreferences and the
 * Subscriptions tab merges each channel's uploads via the extractor.
 */
public final class SubscriptionsStore {

    private static final String PREFS = "subscriptions";
    private static final String KEY_CHANNELS = "channels";
    private static final String SEP = "\u0001";

    /** One subscribed channel. */
    public static final class Channel implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String name;
        public final String url;
        public final String avatar;

        public Channel(String name, String url, String avatar) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.avatar = avatar == null ? "" : avatar;
        }
    }

    private SubscriptionsStore() {
    }

    public static boolean isSubscribed(Context context, String channelUrl) {
        if (channelUrl == null || channelUrl.isEmpty()) {
            return false;
        }
        for (Channel channel : all(context)) {
            if (channelUrl.equals(channel.url)) {
                return true;
            }
        }
        return false;
    }

    public static void subscribe(Context context, String channelUrl, String name, String avatar) {
        if (channelUrl == null || channelUrl.isEmpty()) {
            return;
        }
        Set<String> entries = stored(context);
        // Replace any previous entry for the same channel (name may change).
        for (String entry : new HashSet<>(entries)) {
            Channel channel = decode(entry);
            if (channel != null && channelUrl.equals(channel.url)) {
                entries.remove(entry);
            }
        }
        entries.add(encode(new Channel(name, channelUrl, avatar)));
        save(context, entries);
    }

    public static void unsubscribe(Context context, String channelUrl) {
        if (channelUrl == null || channelUrl.isEmpty()) {
            return;
        }
        Set<String> entries = stored(context);
        for (String entry : new HashSet<>(entries)) {
            Channel channel = decode(entry);
            if (channel != null && channelUrl.equals(channel.url)) {
                entries.remove(entry);
            }
        }
        save(context, entries);
    }

    @NonNull
    public static List<Channel> all(Context context) {
        List<Channel> out = new ArrayList<>();
        for (String entry : stored(context)) {
            Channel channel = decode(entry);
            if (channel != null && !channel.url.isEmpty()) {
                out.add(channel);
            }
        }
        return out;
    }

    public static int count(Context context) {
        return all(context).size();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Defensive copy: SharedPreferences returns its live set. */
    private static Set<String> stored(Context context) {
        Set<String> live = prefs(context).getStringSet(KEY_CHANNELS, null);
        return live == null ? new HashSet<String>() : new HashSet<>(live);
    }

    private static void save(Context context, Set<String> entries) {
        prefs(context).edit().putStringSet(KEY_CHANNELS, entries).apply();
    }

    private static String encode(Channel channel) {
        return channel.url + SEP + channel.name + SEP + channel.avatar;
    }

    private static Channel decode(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.split(SEP, -1);
        if (parts.length < 1 || parts[0].isEmpty()) {
            return null;
        }
        String name = parts.length > 1 ? parts[1] : "";
        String avatar = parts.length > 2 ? parts[2] : "";
        return new Channel(name, parts[0], avatar);
    }
}
