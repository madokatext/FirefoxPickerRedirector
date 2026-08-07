package io.github.firefoxpickerredirector;

final class Config {
    static final String FIREFOX_PACKAGE = "org.mozilla.firefox";
    static final String DEFAULT_TARGET_PACKAGE = "me.zhanghai.android.files";
    static final String PREFS_NAME = "config";
    static final String PREF_TARGET_PACKAGE = "target_package";
    static final String PROVIDER_AUTHORITY = "io.github.firefoxpickerredirector.config";
    static final String PROVIDER_URI = "content://" + PROVIDER_AUTHORITY;
    static final String METHOD_GET_TARGET = "get_target";
    static final String RESULT_TARGET_PACKAGE = "target_package";

    // Firefox Android Components FilePicker.FILE_PICKER_ACTIVITY_REQUEST_CODE in Firefox 153.
    static final int FIREFOX_FILE_PICKER_REQUEST_CODE = 7113;

    private Config() {}
}
