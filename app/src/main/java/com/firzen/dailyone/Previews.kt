package com.firzen.dailyone

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import java.io.File

@Preview(showBackground = true, widthDp = 390, heightDp = 820)
@Composable
private fun EditorPreview() {
    DailyOneTheme {
        Editor(Entry(title = "把日子过慢一点", body = "早上沿着湖边走了很久，没有目的地。\n\n风吹过来的时候，突然觉得，能这样安安静静地待一会儿就很好。"),
            File("/"), true, false, {}, {}, {}, {})
    }
}
