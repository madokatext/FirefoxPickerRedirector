package io.github.firefoxpickerredirector;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private EditText packageInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Firefox Picker Redirector");

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Firefox 文件/照片选择重定向");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText("仅作用于 org.mozilla.firefox。普通网页文件上传会定向到目标文件管理器；Android Photo Picker 入口会改写为 OPEN_DOCUMENT 后再定向。目标应用必须支持 GET_CONTENT / OPEN_DOCUMENT 并返回可读的 Uri。\n\n默认目标：Material Files");
        description.setTextSize(14);
        LinearLayout.LayoutParams descParams = matchWrap();
        descParams.topMargin = dp(12);
        root.addView(description, descParams);

        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setHint("目标文件管理器包名");
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        packageInput.setText(prefs.getString(Config.PREF_TARGET_PACKAGE, Config.DEFAULT_TARGET_PACKAGE));
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(16);
        root.addView(packageInput, inputParams);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> saveTarget());
        root.addView(save, matchWrap());

        Button materialFiles = new Button(this);
        materialFiles.setText("恢复为 Material Files");
        materialFiles.setOnClickListener(v -> {
            packageInput.setText(Config.DEFAULT_TARGET_PACKAGE);
            saveTarget();
        });
        root.addView(materialFiles, matchWrap());

        Button test = new Button(this);
        test.setText("测试打开目标文件管理器");
        test.setOnClickListener(v -> testTarget());
        root.addView(test, matchWrap());

        TextView note = new TextView(this);
        note.setText("修改目标后无需重启手机；若 Firefox 已在前台，下一次选择文件时模块会重新读取设置。LSPosed 中只需勾选 Firefox 作用域。");
        note.setTextSize(13);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(12);
        root.addView(note, noteParams);

        setContentView(root);
    }

    private void saveTarget() {
        String target = packageInput.getText().toString().trim();
        if (target.isEmpty()) {
            target = Config.DEFAULT_TARGET_PACKAGE;
            packageInput.setText(target);
        }
        getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(Config.PREF_TARGET_PACKAGE, target)
                .apply();
        Toast.makeText(this, "已保存：" + target, Toast.LENGTH_SHORT).show();
    }

    private void testTarget() {
        String target = packageInput.getText().toString().trim();
        if (target.isEmpty()) {
            target = Config.DEFAULT_TARGET_PACKAGE;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .setPackage(target);
        try {
            startActivityForResult(intent, 1001);
        } catch (Throwable t) {
            Toast.makeText(this, "目标应用无法处理 OPEN_DOCUMENT：" + target, Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
