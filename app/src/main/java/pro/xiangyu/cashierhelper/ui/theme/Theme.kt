/* Hallmark · genre: modern-minimal · macrostructure: Workbench · theme: Coral · enrichment: none */
package pro.xiangyu.cashierhelper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CashierColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Surface,
    primaryContainer = CoralContainer,
    onPrimaryContainer = Ink,
    secondary = Success,
    onSecondary = Surface,
    background = Paper,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = MutedInk,
    outline = Rule,
    error = Error,
)

@Composable
fun CashierHelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CashierColorScheme,
        typography = CashierTypography,
        content = content,
    )
}

