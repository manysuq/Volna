package com.volna.player.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.volna.player.BuildConfig
import com.volna.player.LogBuffer
import com.volna.player.R
import com.volna.player.ui.theme.AppLanguage
import com.volna.player.ui.theme.ThemeMode

/** Адрес репозитория: сюда ведёт пункт «Проект на GitHub». */
const val GITHUB_URL = "https://github.com/manysuq/Volna"

/**
 * Экран настроек: внешний вид, язык, логи и ссылка на проект.
 *
 * Тема и язык применяются сразу: они пересоздают активность сами,
 * поэтому возвращаться сюда после смены не нужно.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val copied = stringResource(R.string.settings_logs_copied)
    val copyFailed = stringResource(R.string.settings_copy_failed)
    val noLogs = stringResource(R.string.settings_logs_empty)
    val noBrowser = stringResource(R.string.settings_github_unavailable)

    Column(
        modifier = modifier
            .fillMaxSize()
            // Фон обязателен: экран лежит поверх основного, а без заливки
            // сквозь него просвечивали бы поиск и боковая панель.
            .background(MaterialTheme.colorScheme.background),
    ) {
        SettingsHeader(onBack)

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                // Нижний отступ под панель жестов, иначе последний пункт
                // («Скопировать логи») упирается в край экрана.
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            ThemeMode.entries.forEach { mode ->
                ChoiceRow(
                    title = stringResource(
                        when (mode) {
                            ThemeMode.System -> R.string.settings_theme_system
                            ThemeMode.Light -> R.string.settings_theme_light
                            ThemeMode.Dark -> R.string.settings_theme_dark
                        },
                    ),
                    icon = when (mode) {
                        ThemeMode.System -> Icons.Filled.BrightnessAuto
                        ThemeMode.Light -> Icons.Filled.LightMode
                        ThemeMode.Dark -> Icons.Filled.DarkMode
                    },
                    selected = mode == themeMode,
                    onClick = { onThemeChange(mode) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            SectionTitle(stringResource(R.string.settings_language))
            ChoiceRow(
                title = stringResource(R.string.settings_language_system),
                icon = Icons.Filled.Language,
                selected = language == AppLanguage.System,
                onClick = { onLanguageChange(AppLanguage.System) },
            )
            AppLanguage.entries.filter { it != AppLanguage.System }.forEach { lang ->
                ChoiceRow(
                    // Название языка на самом языке: так его узнаёт человек,
                    // который этим языком и пользуется.
                    title = languageName(lang),
                    icon = Icons.Filled.Language,
                    selected = lang == language,
                    onClick = { onLanguageChange(lang) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            SectionTitle(stringResource(R.string.settings_support))
            ActionRow(
                title = stringResource(R.string.settings_copy_logs),
                icon = Icons.Filled.ContentCopy,
                onClick = {
                    // Пустой буфер — не повод показывать ошибку: сообщаем, что
                    // записей ещё нет, иначе выглядит как поломка копирования.
                    val text = LogBuffer.dump(dumpHeader())
                    val message = when {
                        text.isEmpty() -> noLogs
                        copyToClipboard(context, text) -> copied
                        else -> copyFailed
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
            )
            ActionRow(
                title = stringResource(R.string.settings_github),
                icon = Icons.Filled.OpenInNew,
                onClick = { openUrl(context, GITHUB_URL, noBrowser) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            SectionTitle(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow()
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Заголовок и стрелка уходят под строку состояния, если не учесть
            // вырез: на скриншоте «Настройки» наезжали на часы.
            .statusBarsPadding()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Строка с переключателем целиком: нажатие в любом месте меняет выбор. */
@Composable
private fun ChoiceRow(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(20.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun InfoRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            // Лицензия — не украшение, а первое, что ищут при выборе плеера.
            text = stringResource(R.string.settings_license),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Имя языка на самом языке: «Русский», «Français», «العربية». */
private fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> ""
    AppLanguage.English -> "English"
    AppLanguage.Russian -> "Русский"
    AppLanguage.French -> "Français"
    AppLanguage.Spanish -> "Español"
    AppLanguage.Chinese -> "中文"
    AppLanguage.Arabic -> "العربية"
}

/**
 * Заголовок лога: без него присланный текст непонятно, откуда он.
 *
 * Модель и версия ОС нужны почти всегда — половина разборов «не играет»
 * упирается в версию Android или в конкретный аппарат.
 */
private fun dumpHeader(): String = buildString {
    append("Volna ").append(BuildConfig.VERSION_NAME)
    append(" · Android ").append(Build.VERSION.RELEASE)
    append(" (API ").append(Build.VERSION.SDK_INT).append(')')
    append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
    append(" · locale=").append(java.util.Locale.getDefault())
    append("\n---")
}

private fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("Volna logs", text))
    true
}.getOrDefault(false)

private fun openUrl(context: Context, url: String, failureMessage: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        }
}