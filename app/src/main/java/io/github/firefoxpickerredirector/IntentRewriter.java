package io.github.firefoxpickerredirector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

final class IntentRewriter {
    private static final String TAG = "FirefoxPickerRedirector";

    // Framework Photo Picker action (Android 13+ / Mainline backport).
    private static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";

    // AndroidX/OEM system fallback Photo Picker action.
    private static final String ACTION_SYSTEM_FALLBACK_PICK_IMAGES =
            "androidx.activity.result.contract.action.PICK_IMAGES";

    // Google Play services Photo Picker fallback action used by AndroidX on some devices.
    private static final String GMS_ACTION_PICK_IMAGES =
            "com.google.android.gms.provider.action.PICK_IMAGES";

    private IntentRewriter() {}

    static Intent rewrite(Activity activity, Intent original, int requestCode) {
        String targetPackage = readTargetPackage(activity);
        if (targetPackage == null || targetPackage.isEmpty()) {
            return original;
        }

        String action = original.getAction();

        if (Intent.ACTION_CHOOSER.equals(action)) {
            rewriteChooser(activity, original, targetPackage, requestCode);
            return original;
        }

        if (isPhotoPickerAction(action)) {
            Intent replacement = photoPickerToOpenDocument(original, targetPackage);
            if (canResolve(activity, replacement)) {
                log("Photo Picker -> " + describe(replacement));
                return replacement;
            }
            log("Target cannot handle rewritten Photo Picker; keeping Firefox intent");
            return original;
        }

        if (Intent.ACTION_GET_CONTENT.equals(action) && isFirefoxUploadGetContent(original, requestCode)) {
            if (forcePackageIfResolvable(activity, original, targetPackage)) {
                log("GET_CONTENT -> " + describe(original));
            }
            return original;
        }

        // AndroidX PickVisualMedia uses ACTION_OPEN_DOCUMENT as its final fallback.
        // Limit this branch to visual media to avoid redirecting unrelated Firefox OPEN_DOCUMENT flows.
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action) && isVisualSelection(original)) {
            if (forcePackageIfResolvable(activity, original, targetPackage)) {
                log("Photo fallback OPEN_DOCUMENT -> " + describe(original));
            }
            return original;
        }

        return original;
    }

    @SuppressWarnings("deprecation")
    private static void rewriteChooser(Activity activity, Intent chooser, String targetPackage, int requestCode) {
        boolean changed = false;

        Intent base = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        if (base != null && Intent.ACTION_GET_CONTENT.equals(base.getAction())
                && isFirefoxUploadGetContent(base, requestCode)) {
            changed |= forcePackageIfResolvable(activity, base, targetPackage);
            chooser.putExtra(Intent.EXTRA_INTENT, base);
        } else if (base != null && isPhotoPickerAction(base.getAction())) {
            Intent replacement = photoPickerToOpenDocument(base, targetPackage);
            if (canResolve(activity, replacement)) {
                chooser.putExtra(Intent.EXTRA_INTENT, replacement);
                changed = true;
            }
        }

        Parcelable[] rawInitial = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        if (rawInitial != null && rawInitial.length > 0) {
            ArrayList<Intent> rewritten = new ArrayList<>(rawInitial.length);
            boolean initialChanged = false;
            for (Parcelable item : rawInitial) {
                if (!(item instanceof Intent)) {
                    continue;
                }
                Intent intent = (Intent) item;
                if (Intent.ACTION_GET_CONTENT.equals(intent.getAction())
                        && isFirefoxUploadGetContent(intent, requestCode)) {
                    initialChanged |= forcePackageIfResolvable(activity, intent, targetPackage);
                    rewritten.add(intent);
                } else if (isPhotoPickerAction(intent.getAction())) {
                    Intent replacement = photoPickerToOpenDocument(intent, targetPackage);
                    if (canResolve(activity, replacement)) {
                        rewritten.add(replacement);
                        initialChanged = true;
                    } else {
                        rewritten.add(intent);
                    }
                } else {
                    // Camera/video/audio capture intents stay untouched.
                    rewritten.add(intent);
                }
            }
            if (initialChanged) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, rewritten.toArray(new Intent[0]));
                changed = true;
            }
        }

        if (changed) {
            log("Chooser upload entry redirected to " + targetPackage);
        }
    }

    private static boolean isFirefoxUploadGetContent(Intent intent, int requestCode) {
        if (!Intent.ACTION_GET_CONTENT.equals(intent.getAction())) {
            return false;
        }
        boolean localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false);
        boolean openable = intent.getCategories() != null
                && intent.getCategories().contains(Intent.CATEGORY_OPENABLE);
        return (localOnly && openable) || requestCode == Config.FIREFOX_FILE_PICKER_REQUEST_CODE;
    }

    private static boolean isPhotoPickerAction(String action) {
        if (action == null) {
            return false;
        }
        return ACTION_PICK_IMAGES.equals(action)
                || ACTION_SYSTEM_FALLBACK_PICK_IMAGES.equals(action)
                || GMS_ACTION_PICK_IMAGES.equals(action)
                || action.contains("PICK_IMAGES");
    }

    private static Intent photoPickerToOpenDocument(Intent original, String targetPackage) {
        Intent replacement = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        replacement.addCategory(Intent.CATEGORY_OPENABLE);

        String type = original.getType();
        String[] mimeTypes = original.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);

        if (type == null || type.isEmpty()) {
            // AndroidX ImageAndVideo has no single MIME type. Its ACTION_OPEN_DOCUMENT
            // fallback uses */* plus exactly image/* and video/* in EXTRA_MIME_TYPES.
            replacement.setType("*/*");
            if (mimeTypes == null || mimeTypes.length == 0) {
                mimeTypes = new String[] { "image/*", "video/*" };
            }
        } else {
            replacement.setType(type);
        }

        if (mimeTypes != null && mimeTypes.length > 0) {
            replacement.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        }

        replacement.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiplePhotoPicker(original));
        replacement.addFlags(original.getFlags() & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        ));
        replacement.setPackage(targetPackage);
        return replacement;
    }

    private static boolean isMultiplePhotoPicker(Intent intent) {
        if (intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) {
            return true;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) {
            return false;
        }

        Set<String> keys = extras.keySet();
        for (String key : keys) {
            if (key == null || !key.contains("PICK_IMAGES_MAX")) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Number && ((Number) value).intValue() > 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVisualSelection(Intent intent) {
        String type = intent.getType();
        if (type != null && (type.startsWith("image/") || type.startsWith("video/"))) {
            return true;
        }
        String[] types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (types != null) {
            for (String item : types) {
                if (item != null && (item.startsWith("image/") || item.startsWith("video/"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean forcePackageIfResolvable(Activity activity, Intent intent, String targetPackage) {
        Intent probe = new Intent(intent);
        probe.setComponent(null);
        probe.setPackage(targetPackage);
        if (!canResolve(activity, probe)) {
            log("Target " + targetPackage + " cannot handle " + describe(probe));
            return false;
        }
        intent.setComponent(null);
        intent.setPackage(targetPackage);
        return true;
    }

    private static boolean canResolve(Activity activity, Intent intent) {
        try {
            PackageManager pm = activity.getPackageManager();
            ComponentName name = intent.resolveActivity(pm);
            return name != null;
        } catch (Throwable t) {
            log("resolveActivity failed: " + t);
            return false;
        }
    }

    private static String readTargetPackage(Activity activity) {
        try {
            Bundle result = activity.getContentResolver().call(
                    Uri.parse(Config.PROVIDER_URI),
                    Config.METHOD_GET_TARGET,
                    null,
                    null
            );
            if (result != null) {
                String value = result.getString(Config.RESULT_TARGET_PACKAGE);
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        } catch (Throwable t) {
            log("Cannot read module settings, using default target: " + t);
        }
        return Config.DEFAULT_TARGET_PACKAGE;
    }

    private static String describe(Intent intent) {
        return "action=" + intent.getAction()
                + ", type=" + intent.getType()
                + ", package=" + intent.getPackage();
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
