package cz.rybky

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Odhalení ryby po úlovku nebo nalepení: ikona v barvě vzácnosti, název, štítek vzácnosti a body.
 * [points] je hlavní řádek s body (např. „+40 b.“), [details] drobný text pod ním.
 */
@Composable
fun FishReveal(fish: String, rarity: String, envName: String?, points: String, details: String? = null) {
    val color = rarityColor(rarity)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(88.dp).background(color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.SetMeal, null, Modifier.size(52.dp), tint = color) }
        Text(fish, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            rarity.uppercase(),
            color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        envName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        Spacer(Modifier.height(2.dp))
        Text(points, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        details?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}
