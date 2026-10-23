package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.ConcurrentHashMap;

public class ClientGuesser {

    public static final int CLIENT_UNKNOWN = 0;
    public static final int CLIENT_IOS = 1;
    public static final int CLIENT_ANDROID = 2;
    public static final int CLIENT_DESKTOP = 3;
    public static final int CLIENT_WEB = 4;
    public static final int CLIENT_BOT = 5;

    private static final ConcurrentHashMap<Long, Integer> clientCache = new ConcurrentHashMap<>();
    private static SharedPreferences preferences;

    private static void ensureInit() {
        if (preferences == null && ApplicationLoader.applicationContext != null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences("client_guesser_cache", Context.MODE_PRIVATE);
        }
    }

    public static int getClientType(long userId) {
        if (userId == 0) {
            return CLIENT_UNKNOWN;
        }
        Integer cached = clientCache.get(userId);
        if (cached != null) {
            return cached;
        }
        ensureInit();
        if (preferences != null) {
            int stored = preferences.getInt("u_" + userId, CLIENT_UNKNOWN);
            if (stored != CLIENT_UNKNOWN) {
                clientCache.put(userId, stored);
                return stored;
            }
        }
        return CLIENT_UNKNOWN;
    }

    public static void setClientType(long userId, int type) {
        if (userId == 0 || type == CLIENT_UNKNOWN) {
            return;
        }
        clientCache.put(userId, type);
        ensureInit();
        if (preferences != null) {
            preferences.edit().putInt("u_" + userId, type).apply();
        }
    }

    public static String getBadgeForType(int type) {
        switch (type) {
            case CLIENT_IOS:
                return "🍏 iOS";
            case CLIENT_ANDROID:
                return "📱 Android";
            case CLIENT_DESKTOP:
                return "💻 Desktop";
            case CLIENT_WEB:
                return "🌐 Web";
            case CLIENT_BOT:
                return "🤖 Bot";
            default:
                return "";
        }
    }

    public static String getDetailedStringForType(int type) {
        switch (type) {
            case CLIENT_IOS:
                return "🍏 Apple iOS (iPhone/iPad)";
            case CLIENT_ANDROID:
                return "📱 Android Cihaz";
            case CLIENT_DESKTOP:
                return "💻 Telegram Desktop (PC/Mac)";
            case CLIENT_WEB:
                return "🌐 Telegram Web";
            case CLIENT_BOT:
                return "🤖 Telegram Bot";
            default:
                return "Bilinmiyor (Yetersiz veri)";
        }
    }

    public static String getDeviceStringForUser(long userId) {
        int type = getClientType(userId);
        return getDetailedStringForType(type);
    }

    public static String getDeviceBadge(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return "";
        }
        long senderId = msg.getFromChatId();
        if (senderId == 0 && msg.messageOwner.from_id != null) {
            senderId = MessageObject.getPeerId(msg.messageOwner.from_id);
        }

        int guessed = analyzeMessage(msg);
        if (guessed != CLIENT_UNKNOWN && senderId > 0) {
            setClientType(senderId, guessed);
            return getBadgeForType(guessed);
        }
        if (senderId > 0) {
            int cached = getClientType(senderId);
            if (cached != CLIENT_UNKNOWN) {
                return getBadgeForType(cached);
            }
        }
        return "";
    }

    public static int analyzeMessage(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return CLIENT_UNKNOWN;
        }
        try {
            if (msg.messageOwner.via_bot_id != 0) {
                return CLIENT_BOT;
            }

            // Voice messages analysis
            if (msg.isVoice()) {
                byte[] wave = msg.getWaveform();
                if (wave != null && wave.length > 0) {
                    if (wave.length >= 90) {
                        return CLIENT_ANDROID;
                    } else if (wave.length <= 64) {
                        return CLIENT_IOS;
                    }
                }
            }

            // Media & document analysis
            if (msg.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = msg.getDocument();
                if (doc != null) {
                    String fileName = FileLoader.getDocumentFileName(doc);
                    if (fileName == null) {
                        fileName = doc.file_name;
                    }
                    if (fileName != null) {
                        String fn = fileName.toLowerCase();
                        if (fn.endsWith(".heic") || fn.endsWith(".heif") || fn.endsWith(".mov") || fn.endsWith(".aiff") || fn.startsWith("rpreplay_")) {
                            return CLIENT_IOS;
                        }
                        if (fn.endsWith(".exe") || fn.endsWith(".msi") || fn.endsWith(".dmg") || fn.endsWith(".pkg") || fn.endsWith(".deb") || fn.endsWith(".rpm") || fn.endsWith(".tar.gz") || fn.endsWith(".7z") || fn.endsWith(".rar")) {
                            return CLIENT_DESKTOP;
                        }
                        if (fn.startsWith("screenshot (") || fn.startsWith("ekran görüntüsü (") || fn.startsWith("screen shot 20")) {
                            return CLIENT_DESKTOP;
                        }
                        if (fn.startsWith("pxl_") || fn.startsWith("screenshot_20") || fn.startsWith("screen_recording_20") || fn.startsWith("vid_20")) {
                            return CLIENT_ANDROID;
                        }
                    }
                    if (doc.mime_type != null) {
                        if ("audio/webm".equals(doc.mime_type) || "video/webm".equals(doc.mime_type)) {
                            return CLIENT_WEB;
                        }
                        if ("video/quicktime".equals(doc.mime_type)) {
                            return CLIENT_IOS;
                        }
                    }
                }
            }

            // Text analysis
            CharSequence text = msg.messageText;
            if (text != null && text.length() > 0) {
                String str = text.toString();

                // Windows Desktop physical Enter newline
                if (str.contains("\r\n")) {
                    return CLIENT_DESKTOP;
                }

                // Apple smart punctuation (curly quotes, smart apostrophe, ellipsis, em-dash)
                if (str.contains("\u201C") || str.contains("\u201D") || str.contains("\u2018") || str.contains("\u2019") || str.contains("\u2026") || str.contains("\u2014")) {
                    return CLIENT_IOS;
                }

                // Apple emoji variation selectors and Apple private Unicode
                if (str.contains("\uFE0F") || str.contains("\u200D\u2640") || str.contains("\u200D\u2642") || str.contains("\uF8FF")) {
                    return CLIENT_IOS;
                }
            }
        } catch (Throwable ignore) {
        }
        return CLIENT_UNKNOWN;
    }
}
