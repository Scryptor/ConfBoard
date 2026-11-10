package board.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import board.core.BoardInfo
import board.core.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Accent = Color(0xFF1E88E5)
private val PanelColor = Color(0xF7FFFFFF)

@Composable
fun App(c: BoardController) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent)) {
        if (c.isTv) TvScreen(c) else TabletScreen(c)
    }
}

// ============================================================== планшет

@Composable
private fun TabletScreen(c: BoardController) {
    var confirmClear by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = c.editingText != null) { c.finishText() }

    // Пока мы ведущий — отправляем свой вид, чтобы ТВ его повторяли.
    LaunchedEffect(c.isPresenter) {
        if (!c.isPresenter) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { c.viewRect()?.toList() }.collect { r ->
            if (r != null) c.session.sendViewport(r.toFloatArray())
            kotlinx.coroutines.delay(33)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        BoardCanvas(c, Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            ToolPanel(c, Modifier.align(Alignment.CenterStart))
            BoardNameChip(c, Modifier.align(Alignment.TopStart))

            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(c.status)
                Spacer(Modifier.width(8.dp))
                Panel {
                    Row {
                        PanelButton(
                            if (c.isPresenter) Icons.Filled.CastConnected else Icons.Filled.Cast,
                            if (c.isPresenter) "ТВ показывает мой экран — нажмите, чтобы вернуть всю доску" else "Показывать мой экран на ТВ",
                            selected = c.isPresenter,
                        ) { c.togglePresenting() }
                        PanelButton(Icons.Filled.Tv, "Режим ТВ") { c.setTvMode(true) }
                        PanelButton(Icons.Filled.FitScreen, "Показать всё") { c.fitToContent() }
                        PanelButton(Icons.Outlined.FolderOpen, "Доски") { c.boardsOpen = true }
                        PanelButton(Icons.Filled.DeleteSweep, "Очистить") { confirmClear = true }
                    }
                }
            }
            PresenterBadge(c)
            }

            StylePanel(c, Modifier.align(Alignment.BottomCenter))

            c.editingText?.let { TextEditor(c, it.text, Modifier.align(Alignment.TopCenter).imePadding()) }
        }
    }

    if (c.boardsOpen) BoardsDialog(c, onDismiss = { c.boardsOpen = false })
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить доску?") },
            text = { Text("Всё нарисованное исчезнет у всех участников. Действие можно отменить кнопкой «Отменить».") },
            confirmButton = { TextButton({ c.clearBoard(); confirmClear = false }) { Text("Очистить") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = PanelColor, shadowElevation = 6.dp, content = content)
}

@Composable
private fun PanelButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(2.dp).size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (selected) Accent else Color(0xFF37474F),
        ),
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun ToolPanel(c: BoardController, modifier: Modifier) {
    Panel(modifier) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tools = listOf(
                Tool.PEN to (Icons.Outlined.Draw to "Маркер"),
                Tool.LINE to (Icons.Filled.HorizontalRule to "Линия"),
                Tool.ARROW to (Icons.Filled.NorthEast to "Стрелка"),
                Tool.RECT to (Icons.Outlined.CropSquare to "Прямоугольник"),
                Tool.ELLIPSE to (Icons.Outlined.Circle to "Эллипс"),
                Tool.TEXT to (Icons.Filled.TextFields to "Текст"),
                Tool.ERASER to (EraserIcon to "Ластик"),
            )
            for ((tool, iconLabel) in tools) {
                PanelButton(iconLabel.first, iconLabel.second, selected = c.tool == tool) {
                    if (c.tool != tool) c.finishText()
                    c.tool = tool
                }
            }
            HorizontalDivider(Modifier.width(32.dp).padding(vertical = 4.dp))
            PanelButton(Icons.AutoMirrored.Filled.Undo, "Отменить", enabled = c.canUndo) { c.undo() }
            PanelButton(Icons.AutoMirrored.Filled.Redo, "Вернуть", enabled = c.canRedo) { c.redo() }
        }
    }
}

@Composable
private fun StylePanel(c: BoardController, modifier: Modifier) {
    Panel(modifier.widthIn(max = 720.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (c.tool == Tool.ERASER) {
                EraserOptions(c)
                return@Row
            }
            for (col in PALETTE) {
                val selected = c.color == col
                Box(
                    Modifier
                        .size(36.dp)
                        .border(if (selected) 3.dp else 0.dp, if (selected) Accent else Color.Transparent, CircleShape)
                        .padding(if (selected) 5.dp else 3.dp)
                        .clip(CircleShape)
                        .background(Color(col))
                        .border(1.dp, Color(0x33000000), CircleShape)
                        .clickable { c.color = col; applyColorToEditingText(c) },
                )
            }
            Box(Modifier.width(1.dp).height(28.dp).background(Color(0x22000000)))
            for ((i, w) in WIDTHS.withIndex()) {
                val selected = c.widthIndex == i
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { c.widthIndex = i },
                    contentAlignment = Alignment.Center,
                ) {
                    val d = (w * 1.1f).coerceIn(4f, 22f).dp
                    Box(Modifier.size(d).clip(CircleShape).background(Color(c.color).let { if (c.color == PALETTE[9]) Color.LightGray else it }))
                }
            }
        }
    }
}

/** Настройки ластика: стирать частями или целиком, размер. */
@Composable
private fun EraserOptions(c: BoardController) {
    @Composable
    fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(text, color = if (selected) Accent else Color(0xFF37474F), fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    Choice("Стирать частями", !c.eraserWhole) { c.eraserWhole = false }
    Choice("Объект целиком", c.eraserWhole) { c.eraserWhole = true }
    Box(Modifier.width(1.dp).height(28.dp).background(Color(0x22000000)))
    for ((i, r) in ERASER_SIZES.withIndex()) {
        val selected = c.eraserSizeIndex == i
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable { c.eraserSizeIndex = i },
            contentAlignment = Alignment.Center,
        ) {
            val d = (r * 0.9f).coerceIn(8f, 36f).dp
            Box(Modifier.size(d).clip(CircleShape).background(Color(0x3390A4AE)).border(1.5.dp, Color(0xFF607D8B), CircleShape))
        }
    }
}

/** Название текущей доски; по нажатию — список досок. */
@Composable
private fun BoardNameChip(c: BoardController, modifier: Modifier) {
    c.revision // название может поменяться
    Surface(
        modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(16.dp)).clickable { c.boardsOpen = true },
        shape = RoundedCornerShape(16.dp), color = PanelColor, shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Dashboard, null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.board.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF37474F), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private fun applyColorToEditingText(c: BoardController) {
    val t = c.editingText ?: return
    if (t.color != c.color) {
        // Перекрасить редактируемый текст, сохранив набранное.
        c.restyleText(c.color)
    }
}

/** Подсказка, чей экран сейчас на ТВ. */
@Composable
private fun PresenterBadge(c: BoardController) {
    val text = when {
        c.isPresenter -> "ТВ повторяет ваш экран"
        c.presenterId != null -> "На ТВ экран: ${c.presenterName ?: "другого планшета"}"
        else -> return
    }
    Spacer(Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (c.isPresenter) Accent else Color(0xEE37474F),
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CastConnected, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusChip(s: Session.Status) {
    val (dot, text) = when (s.role) {
        Session.Role.HOST -> Color(0xFF43A047) to "Хозяин · ${deviceCount(s.members.size)}"
        Session.Role.CLIENT -> Color(0xFF43A047) to "${s.hostName ?: "Хозяин"} · ${deviceCount(s.members.size)}"
        Session.Role.CONNECTING -> Color(0xFFFB8C00) to "Подключение к ${s.hostName ?: "…"}"
        Session.Role.SEARCHING -> Color(0xFFFB8C00) to "Поиск устройств…"
    }
    Panel {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 14.sp, color = Color(0xFF37474F))
        }
    }
}

private fun deviceCount(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    val word = when {
        m10 == 1 && m100 != 11 -> "устройство"
        m10 in 2..4 && m100 !in 12..14 -> "устройства"
        else -> "устройств"
    }
    return "$n $word"
}

@Composable
private fun TextEditor(c: BoardController, text: String, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.editingText?.id) { runCatching { focus.requestFocus() } }
    Panel(modifier.padding(top = 64.dp).widthIn(max = 560.dp).fillMaxWidth(0.7f)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { c.updateText(it) },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp).focusRequester(focus),
                placeholder = { Text("Введите текст") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { c.finishText() }) { Icon(Icons.Filled.Check, "Готово", tint = Accent) }
        }
    }
}

@Composable
private fun BoardsDialog(c: BoardController, onDismiss: () -> Unit) {
    var toDelete by remember { mutableStateOf<BoardInfo?>(null) }
    var toRename by remember { mutableStateOf<BoardInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    val role = c.status.role
    val online = role == Session.Role.HOST || role == Session.Role.CLIENT
    // Список приходит от хозяина — запрашиваем, как только есть связь.
    LaunchedEffect(online) { if (online) c.requestBoards() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доски") },
        text = {
            val list = c.boardList
            when {
                !online -> Column(Modifier.fillMaxWidth().height(120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Поиск устройств в сети…", color = Color.Gray)
                }
                list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(list, key = { it.id }) { b ->
                        val current = b.id == c.board.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (current) Accent.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable { c.openBoard(b.id); onDismiss() }
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                                Text(
                                    (if (current) "открыта · " else "") + "изменена ${fmt.format(Date(b.updatedAt))} · элементов: ${b.elementCount}",
                                    fontSize = 12.sp, color = Color.Gray,
                                )
                            }
                            IconButton({ toRename = b }) { Icon(Icons.Filled.Edit, "Переименовать", tint = Color.Gray) }
                            if (!current) {
                                IconButton({ toDelete = b }) { Icon(Icons.Filled.Delete, "Удалить", tint = Color.Gray) }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ creating = true }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Новая доска")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(if (c.board.elements.isEmpty()) "Закрыть" else "Продолжить") } },
    )
    if (creating) {
        NameDialog(
            title = "Новая доска",
            initial = board.core.Board.defaultName(),
            confirm = "Создать",
            onDismiss = { creating = false },
        ) { name -> c.newBoard(name); creating = false; onDismiss() }
    }
    toRename?.let { b ->
        NameDialog(title = "Переименовать доску", initial = b.name, confirm = "Сохранить", onDismiss = { toRename = null }) { name ->
            c.renameBoard(b.id, name)
            toRename = null
        }
    }
    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить «${b.name}»?") },
            text = { Text("Доска будет удалена с устройства-хозяина без возможности восстановления.") },
            confirmButton = { TextButton({ c.deleteBoard(b.id); c.requestBoards(); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton({ onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

// ============================================================== телевизор

@Composable
private fun TvScreen(c: BoardController) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        TvCanvas(c, Modifier.fillMaxSize())
        c.revision
        if (c.board.elements.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Онлайн доска", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF37474F))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Откройте приложение на планшете в этой же Wi‑Fi сети — рисунок появится здесь",
                    fontSize = 20.sp, color = Color(0xFF78909C),
                )
            }
        }
        // Статус показываем при изменениях и прячем, чтобы не закрывал рисунок.
        var chipVisible by remember { mutableStateOf(true) }
        LaunchedEffect(c.status, c.presenterId, c.board.id, c.board.name) {
            chipVisible = true
            kotlinx.coroutines.delay(6000)
            chipVisible = c.board.elements.isEmpty()
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = chipVisible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Panel {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Dashboard, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(c.board.name, fontSize = 14.sp, color = Color(0xFF37474F))
                    }
                }
                Spacer(Modifier.height(8.dp))
                c.presenterName?.let {
                    Panel {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CastConnected, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Экран: $it", fontSize = 14.sp, color = Color(0xFF37474F))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                StatusChip(c.status)
            }
        }

        // Планшет в режиме ТВ: тап по экрану показывает кнопку выхода.
        if (!c.realTv) {
            var exitVisible by remember { mutableStateOf(false) }
            LaunchedEffect(exitVisible) {
                if (exitVisible) { kotlinx.coroutines.delay(5000); exitVisible = false }
            }
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { exitVisible = !exitVisible },
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = exitVisible,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Panel {
                    TextButton(onClick = { c.setTvMode(false) }, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Filled.Tv, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Выйти из режима ТВ")
                    }
                }
            }
        }
    }
}
