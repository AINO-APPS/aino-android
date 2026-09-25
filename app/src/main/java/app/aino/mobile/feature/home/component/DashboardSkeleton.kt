package app.aino.mobile.feature.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** `DashboardSkeleton` port (P2.8): shimmering placeholder blocks. */
@Composable
fun DashboardSkeleton() {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Greeting
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(220.dp).height(26.dp).background(colors.surface, RoundedCornerShape(6.dp)))
            Box(Modifier.width(160.dp).height(14.dp).background(colors.surface, RoundedCornerShape(6.dp)))
        }
        // Work timer
        WebCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(90.dp).height(90.dp).background(colors.surface, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(140.dp).height(14.dp).background(colors.surface, RoundedCornerShape(6.dp)))
                    Box(Modifier.width(180.dp).height(40.dp).background(colors.surface, RoundedCornerShape(8.dp)))
                }
            }
        }
        // Two content cards
        repeat(2) {
            WebCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(120.dp).height(16.dp).background(colors.surface, RoundedCornerShape(6.dp)))
                    repeat(3) { Box(Modifier.fillMaxWidth().height(14.dp).background(colors.surface, RoundedCornerShape(6.dp))) }
                }
            }
        }
    }
}