package io.github.firefoxpickerredirector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (Config.METHOD_GET_TARGET.equals(method)) {
            SharedPreferences prefs = providerContext().getSharedPreferences(Config.PREFS_NAME, 0);
            String target = prefs.getString(Config.PREF_TARGET_PACKAGE, Config.DEFAULT_TARGET_PACKAGE);
            Bundle result = new Bundle();
            result.putString(Config.RESULT_TARGET_PACKAGE, normalize(target));
            return result;
        }
        return super.call(method, arg, extras);
    }

    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider context is null");
        }
        return context;
    }

    private static String normalize(String value) {
        if (value == null) {
            return Config.DEFAULT_TARGET_PACKAGE;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Config.DEFAULT_TARGET_PACKAGE : trimmed;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
