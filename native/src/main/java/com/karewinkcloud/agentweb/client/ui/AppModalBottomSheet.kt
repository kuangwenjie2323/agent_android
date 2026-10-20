@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Keeps Material's window, scrim and drag-handle semantics in the selected app language. */
@Composable
internal fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    val configuration = LocalAppResources.current?.configuration ?: LocalConfiguration.current
    val localized = remember(view, configuration) {
        ContextThemeWrapper(view.context, view.context.theme).apply {
            applyOverrideConfiguration(Configuration(configuration))
        }
    }
    val composition = rememberCompositionContext()
    val viewId = rememberSaveable { View.generateViewId() }
    val sheet: @Composable () -> Unit = {
        ModalBottomSheet(onDismissRequest, modifier, sheetState, containerColor = containerColor, content = content)
    }
    val currentSheet by rememberUpdatedState(sheet)

    // Material 3 creates its dialog from LocalView.context, then supplies new Android
    // composition locals. A provider around the content cannot localize its scrim or
    // dismiss actions. This zero-size host gives the whole window a localized context.
    key(localized) {
        AndroidView(
            factory = {
                ComposeView(localized).apply {
                    id = viewId
                    setParentCompositionContext(composition)
                    setContent { currentSheet() }
                }
            },
            modifier = Modifier.size(0.dp),
            onRelease = { it.disposeComposition() },
        )
    }
}
