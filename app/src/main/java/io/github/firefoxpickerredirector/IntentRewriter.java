package io.github.firefoxpickerredirector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;

import java.util.ArrayList;

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
        String action = original.getAction();

        // Photo/video uploads must stay on the system media picker. Do not redirect an
        // already-created Photo Picker intent to the custom file manager.
        if (isPhotoPickerAction(action)) {
            return original;
        }

        // Firefox 153 builds image/video upload as a chooser containing GET_CONTENT plus
        // camera/video capture intents. For a visual-only request, replace the whole chooser
        // with the system Photo Picker so there is no camera-vs-file-manager chooser at all.
        if (Intent.ACTION_CHOOSER.equals(action)) {
            Intent mediaPicker = visualChooserToSystemPhotoPicker(activity, original, requestCode);
            if (mediaPicker != null) {
                log("Visual upload chooser -> system Photo Picker: " + describe(mediaPicker));
                return mediaPicker;
            }

            String targetPackage = readTargetPackage(activity);
            if (targetPackage != null && !targetPackage.isEmpty()) {
                rewriteNonVisualChooser(activity, original, targetPackage, requestCode);
            }
            return original;
        }

        // AndroidX Photo Picker fallback should also remain a media picker/file picker chosen by
        // AndroidX itself, not be forced into the configured third-party manager.
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action) && isVisualOnlySelection(original)) {
            return original;
        }

        // Ordinary file uploads still go to the configured custom file manager.
        if (Intent.ACTION_GET_CONTENT.equals(action) && isFirefoxUploadGetContent(original, requestCode)) {
            if (isVisualOnlySelection(original)) {
                Intent mediaPicker = getContentToSystemPhotoPicker(activity, original);
                if (mediaPicker != null) {
                    log("Visual GET_CONTENT -> system Photo Picker: " + describe(mediaPicker));
                    return mediaPicker;
                }
                return original;
            }

            String targetPackage = readTargetPackage(activity);
            if (targetPackage != null && !targetPackage.isEmpty()
                    && forcePackageIfResolvable(activity, original, targetPackage)) {
                log("GET_CONTENT -> " + describe(original));
            }
            return original;
        }

        return original;
    }

    @SuppressWarnings("deprecation")
    private static Intent visualChooserToSystemPhotoPicker(
            Activity activity,
            Intent chooser,
            int requestCode
    ) {
        Intent base = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        if (base == null
                || !Intent.ACTION_GET_CONTENT.equals(base.getAction())
                || !isFirefoxUploadGetContent(base, requestCode)
                || !isVisualOnlySelection(base)) {
            return null;
        }

        return getContentToSystemPhotoPicker(activity, base);
    }

    /**
     * Keep the old custom-file-manager behavior only for non-visual upload choosers.
     * Capture intents are intentionally left alone here because this branch is not the
     * dedicated photo/video entry.
     */
    @SuppressWarnings("deprecation")
    private static void rewriteNonVisualChooser(
            Activity activity,
            Intent chooser,
            String targetPackage,
            int requestCode
    ) {
        boolean changed = false;

        Intent base = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        if (base != null
                && Intent.ACTION_GET_CONTENT.equals(base.getAction())
                && isFirefoxUploadGetContent(base, requestCode)
                && !isVisualOnlySelection(base)) {
            changed |= forcePackageIfResolvable(activity, base, targetPackage);
            chooser.putExtra(Intent.EXTRA_INTENT, base);
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
                        && isFirefoxUploadGetContent(intent, requestCode)
                        && !isVisualOnlySelection(intent)) {
                    initialChanged |= forcePackageIfResolvable(activity, intent, targetPackage);
                }
                rewritten.add(intent);
            }
            if (initialChanged) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, rewritten.toArray(new Intent[0]));
                changed = true;
            }
        }

        if (changed) {
            log("Non-visual upload chooser redirected to " + targetPackage);
        }
    }

    private static Intent getContentToSystemPhotoPicker(Activity activity, Intent source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null;
        }

        Intent picker = new Intent(ACTION_PICK_IMAGES);
        String pickerMimeType = getPhotoPickerMimeType(source);
        if (pickerMimeType != null) {
            picker.setType(pickerMimeType);
        }

        if (source.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) {
            // Match AndroidX PickMultipleVisualMedia on Android 13+: ACTION_PICK_IMAGES with
            // EXTRA_PICK_IMAGES_MAX. Firefox consumes the returned ClipData normally.
            picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        }

        picker.addFlags(source.getFlags() & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        ));

        return canResolve(activity, picker) ? picker : null;
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

    /**
     * True only when every requested MIME type is visual media. Mixed requests such as
     * image/* + application/pdf remain ordinary file uploads and still use the custom manager.
     */
    private static boolean isVisualOnlySelection(Intent intent) {
        String[] types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (types != null && types.length > 0) {
            boolean sawVisual = false;
            for (String item : types) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                if (item.startsWith("image/") || item.startsWith("video/")) {
                    sawVisual = true;
                } else {
                    return false;
                }
            }
            return sawVisual;
        }

        String type = intent.getType();
        return type != null && (type.startsWith("image/") || type.startsWith("video/"));
    }

    /**
     * Mirrors Firefox/AndroidX visual-media classification:
     * - exactly one accepted visual MIME: preserve it;
     * - multiple image MIME types: image/*;
     * - multiple video MIME types: video/*;
     * - image + video: null, meaning both in ACTION_PICK_IMAGES.
     */
    private static String getPhotoPickerMimeType(Intent source) {
        String[] types = source.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (types != null && types.length > 0) {
            if (types.length == 1) {
                String only = types[0];
                if (only != null && (only.startsWith("image/") || only.startsWith("video/"))) {
                    return only;
                }
            }

            boolean image = false;
            boolean video = false;
            for (String item : types) {
                if (item == null) {
                    continue;
                }
                if (item.startsWith("image/")) {
                    image = true;
                } else if (item.startsWith("video/")) {
                    video = true;
                }
            }
            if (image && !video) {
                return "image/*";
            }
            if (video && !image) {
                return "video/*";
            }
            if (image && video) {
                return null;
            }
        }

        String type = source.getType();
        if (type != null && (type.startsWith("image/") || type.startsWith("video/"))) {
            return type;
        }
        return null;
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
