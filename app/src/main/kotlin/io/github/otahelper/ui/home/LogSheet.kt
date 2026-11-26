package io.github.otahelper.ui.home

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.github.otahelper.R
import io.github.otahelper.ui.components.AppIcons
import io.github.otahelper.ui.theme.Dimens
import kotlinx.coroutines.launch

private const val SHEET_HEIGHT = 0.9f

/** Bottom sheet with the controller log in monospace, newest line at the bottom, and a copy button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogSheet(loadLog: suspend () -> List<String>, onDismiss: () -> Unit) {
    val lines by produceState<List<String>?>(initialValue = null) { value = loadLog() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.log_title)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Hidden → Expanded only: the log is long, a half-open state adds nothing.
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.Gutter + Dimens.Small, end = Dimens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(
                    onClick = {
                        val text = lines.orEmpty().joinToString(separator = "\n")
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, text))) }
                    },
                    enabled = !lines.isNullOrEmpty(),
                ) {
                    Icon(imageVector = AppIcons.Copy, contentDescription = stringResource(R.string.log_copy))
                }
            }
            LogBody(lines)
        }
    }
}

@Composable
private fun LogBody(lines: List<String>?) {
    if (lines.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (lines == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = lines.lastIndex)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Dimens.Gutter, end = Dimens.Gutter, top = Dimens.Small, bottom = Dimens.Gutter),
        shape = RoundedCornerShape(Dimens.LogCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(Dimens.RowVertical),
            verticalArrangement = Arrangement.spacedBy(Dimens.RowGap),
        ) {
            items(count = lines.size) { index ->
                Text(
                    text = lines[index],
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
