package dev.inferdroid;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class UiSettingsActivity extends AppCompatActivity {
    private String initialTheme;
    private String initialLanguage;
    private RadioGroup themeRadioGroup;
    private RadioGroup languageRadioGroup;
    private boolean isUpdatingUi = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ui_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_content), (view, insets) -> {
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime()
            );
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });

        initialTheme = ThemeHelper.getSelectedTheme(this);
        initialLanguage = ThemeHelper.getSelectedLanguage(this);

        ImageButton backButton = findViewById(R.id.btn_back);
        backButton.setOnClickListener(v -> finish());

        themeRadioGroup = findViewById(R.id.theme_radio_group);
        languageRadioGroup = findViewById(R.id.language_radio_group);

        setupThemeRadioGroup();
        setupLanguageRadioGroup();
    }

    private void setupThemeRadioGroup() {
        isUpdatingUi = true;
        switch (initialTheme) {
            case ThemeHelper.THEME_OLED_DARK:
                themeRadioGroup.check(R.id.radio_theme_oled_dark);
                break;
            case ThemeHelper.THEME_AUTO:
                themeRadioGroup.check(R.id.radio_theme_auto);
                break;
            case ThemeHelper.THEME_GRUVBOX_LIGHT:
                themeRadioGroup.check(R.id.radio_theme_gruvbox_light);
                break;
            case ThemeHelper.THEME_GRUVBOX_DARK:
                themeRadioGroup.check(R.id.radio_theme_gruvbox_dark);
                break;
            case ThemeHelper.THEME_AUTO_GRUVBOX:
                themeRadioGroup.check(R.id.radio_theme_auto_gruvbox);
                break;
            case ThemeHelper.THEME_LIGHT:
            default:
                themeRadioGroup.check(R.id.radio_theme_light);
                break;
        }
        isUpdatingUi = false;

        themeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isUpdatingUi) return;
            String newTheme = ThemeHelper.THEME_LIGHT;
            if (checkedId == R.id.radio_theme_oled_dark) {
                newTheme = ThemeHelper.THEME_OLED_DARK;
            } else if (checkedId == R.id.radio_theme_auto) {
                newTheme = ThemeHelper.THEME_AUTO;
            } else if (checkedId == R.id.radio_theme_gruvbox_light) {
                newTheme = ThemeHelper.THEME_GRUVBOX_LIGHT;
            } else if (checkedId == R.id.radio_theme_gruvbox_dark) {
                newTheme = ThemeHelper.THEME_GRUVBOX_DARK;
            } else if (checkedId == R.id.radio_theme_auto_gruvbox) {
                newTheme = ThemeHelper.THEME_AUTO_GRUVBOX;
            }

            if (!newTheme.equals(initialTheme)) {
                ThemeHelper.setSelectedTheme(this, newTheme);
                recreate();
            }
        });
    }

    private void setupLanguageRadioGroup() {
        isUpdatingUi = true;
        switch (initialLanguage) {
            case ThemeHelper.LANG_EN:
                languageRadioGroup.check(R.id.radio_lang_en);
                break;
            case ThemeHelper.LANG_IT:
                languageRadioGroup.check(R.id.radio_lang_it);
                break;
            case ThemeHelper.LANG_SYSTEM:
            default:
                languageRadioGroup.check(R.id.radio_lang_system);
                break;
        }
        isUpdatingUi = false;

        languageRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isUpdatingUi) return;
            String newLang = ThemeHelper.LANG_SYSTEM;
            if (checkedId == R.id.radio_lang_en) {
                newLang = ThemeHelper.LANG_EN;
            } else if (checkedId == R.id.radio_lang_it) {
                newLang = ThemeHelper.LANG_IT;
            }

            if (!newLang.equals(initialLanguage)) {
                ThemeHelper.setSelectedLanguage(this, newLang);
                ThemeHelper.applyLanguage(this);
                recreate();
            }
        });
    }
}
