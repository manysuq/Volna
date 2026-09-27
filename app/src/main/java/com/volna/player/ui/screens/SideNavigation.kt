@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/** Разделы приложения. */
enum class AppTab(@StringRes val title: Int, val icon: ImageVector) {
    Search(R.string.tab_search, Icons.Filled.Search),
    Albums(R.string.tab_albums, Icons.Filled.Album),
    Recommendations(R.string.tab_similar, Icons.Filled.Explore),
}

/**
 * Боковая навигация, повёрнутая на -90 градусов — как в ViMusic:
 * панель идёт вертикально вдоль левого края, подписи читаются снизу вверх.
 *
 * Поворот в Compose не меняет раскладку, поэтому размеры считаем вручную:
 * содержимое измеряется «наоборот» (длина ограничивается высотой панели),
 * узел сообщает перевёрнутые размеры и центрирует контент — тогда поворот
 * вокруг центра ничего не выносит за края.
 */
@Composable
fun SideNavigation(
    current: AppTab,
    onSelect: (AppTab) -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(RAIL_THICKNESS)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // Переключатель темы стоит неповёрнутым, чтобы иконка читалась ровно
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                    contentDescription = stringResource(
                        if (isDarkTheme) R.string.theme_light else R.string.theme_dark,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            RotatedRail {
                AppTab.entries.forEach { tab ->
                    RailItem(
                        title = stringResource(tab.title),
                        icon = tab.icon,
                        selected = tab == current,
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RailItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val highlight by animateFloatAsState(if (selected) 1f else 0f, label = "railHighlight")
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Ряд вытянут вдоль панели: после поворота иконка снизу, подпись над ней
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f + 0.92f * highlight))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}

/**
 * Ряд, развёрнутый на -90 градусов: в горизонтальной рамке считается длина,
 * которая после поворота становится высотой панели.
 */
@Composable
private fun RotatedRail(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth),
                )
                val over = placeable.width - placeable.height
                layout(placeable.height, placeable.width) {
                    // контент шире рамки, поэтому выносим его наружу по обе оси
                    placeable.place(x = -over / 2, y = over / 2)
                }
            }
            .rotate(-90f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Толщина панели на экране: это высота повёрнутого содержимого. */
private val RAIL_THICKNESS = 72.dp
