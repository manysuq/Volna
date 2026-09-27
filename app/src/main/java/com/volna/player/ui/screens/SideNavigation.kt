@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Разделы приложения. */
enum class AppTab(@StringRes val title: Int, val icon: ImageVector) {
    Search(R.string.tab_search, Icons.Filled.Search),
    Albums(R.string.tab_albums, Icons.Filled.Album),
    Recommendations(R.string.tab_similar, Icons.Filled.Explore),
    Library(R.string.tab_library, Icons.Filled.Favorite),
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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    order: List<AppTab> = TabOrder.DEFAULT,
    onReorder: (List<AppTab>) -> Unit = { },
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
                ReorderableRail(
                    order = order,
                    current = current,
                    onSelect = onSelect,
                    onReorder = onReorder,
                )
            }
        }

        // Настройки внизу панели: туда попадают язык, логи и ссылка на проект.
        // Стоят неповёрнутым по той же причине, что и тема, — читаются ровно.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    modifier: Modifier = Modifier,
) {
    val highlight by animateFloatAsState(if (selected) 1f else 0f, label = "railHighlight")
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Ряд вытянут вдоль панели: после поворота иконка снизу, подпись над ней
    Row(
        modifier = modifier
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

/**
 * Рельс вкладок с перестановкой долгим нажатием.
 *
 * Жест сделан по локальной оси узла: система уже разворачивает координаты
 * для повёрнутого контейнера, поэтому индекс считается по локальной
 * координате, а не по экранной. Сама перестановка — чистая функция
 * [TabOrder.move], её поведение покрыто тестом.
 */
@Composable
private fun ReorderableRail(
    order: List<AppTab>,
    current: AppTab,
    onSelect: (AppTab) -> Unit,
    onReorder: (List<AppTab>) -> Unit,
) {
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var itemHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEachIndexed { index, tab ->
            val isDragging = draggingIndex == index
            RailItem(
                title = stringResource(tab.title),
                icon = tab.icon,
                selected = tab == current,
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .onGloballyPositioned {
                        if (itemHeight == 0) itemHeight = it.size.height
                    }
                    .pointerInput(order, itemHeight) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggingIndex = index },
                            onDragEnd = { draggingIndex = null },
                            onDragCancel = { draggingIndex = null },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (itemHeight > 0) {
                                    // dragAmount здесь — Offset, а не число:
                                    // так объявлен onDrag у
                                    // detectDragGesturesAfterLongPress. Раньше
                                    // с ним сравнивали как со скаляром, и
                                    // код не собирался.
                                    //
                                    // Ось y: элементы рельса уложены вдоль
                                    // локальной Y, а система уже развернула
                                    // координаты под поворот панели.
                                    val half = itemHeight / 2
                                    val delta = dragAmount.y.toInt()
                                    val shift = when {
                                        delta > half -> 1
                                        delta < -half -> -1
                                        else -> 0
                                    }
                                    if (shift != 0) {
                                        val from = draggingIndex ?: index
                                        val destination = from + shift
                                        // Явные границы вместо `in indices`:
                                        // внутри pointerInput проверка по
                                        // диапазону не разрешалась.
                                        val last = order.size - 1
                                        if (destination in 0..last && from in 0..last) {
                                            onReorder(TabOrder.move(order, from, destination))
                                            draggingIndex = destination
                                        }
                                    }
                                }
                            },
                        )
                    }
                    .graphicsLayer {
                        if (isDragging) {
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    },
            )
        }
    }
}
