package com.barnabas.absorb.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.auth.Credentials

/**
 * A book in a list: a card with the cover filling its left end, the title
 * and author beside it, and how far along it is as a thin line at the
 * bottom, all tinted with the app's colour. With [onRemove] the right end
 * carries a small button to take the book off the watch.
 */
@Composable
fun BookRow(
    item: InProgressItem,
    creds: Credentials,
    fraction: Float?,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) = CoverCard(
    itemId = item.id,
    creds = creds,
    title = item.displayTitle,
    subtitle = item.displaySubtitle,
    fraction = fraction,
    modifier = modifier,
    trailing = onRemove?.let { { RemoveButton(it) } },
    onClick = onClick,
)

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
        ),
    ) {
        Icon(Icons.Rounded.Close, contentDescription = "Remove from watch", modifier = Modifier.size(16.dp))
    }
}

/** The card behind [BookRow], for a book known only by its id and words. */
@Composable
fun CoverCard(
    itemId: String,
    creds: Credentials,
    title: String,
    subtitle: String?,
    fraction: Float?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colors.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
            BookCover(
                itemId = itemId,
                creds = creds,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp).size(58.dp),
                cornerRadius = 16.dp,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp, end = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.caption3,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(end = 8.dp)) { trailing() }
            }
        }
        if (fraction != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 66.dp, end = 12.dp, bottom = 5.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(tint),
                )
            }
        }
    }
}

/**
 * A shelf or list as a card: a fan of its first covers on the left (or an
 * [icon] when it has none to show), its name and how many books it holds.
 */
@Composable
fun ShelfCard(
    label: String,
    caption: String?,
    coverIds: List<String>,
    creds: Credentials,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colors.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight().padding(start = 6.dp)) {
            val covers = coverIds.take(3)
            if (covers.isNotEmpty()) {
                // The first cover sits on top; later ones peek out behind it.
                Box(modifier = Modifier.width((44 + (covers.size - 1) * 16).dp).height(48.dp)) {
                    for (i in covers.indices.reversed()) {
                        BookCover(
                            itemId = covers[i],
                            creds = creds,
                            modifier = Modifier
                                .offset(x = (i * 16).dp, y = (i * 1).dp)
                                .size(44.dp)
                                .padding(top = (i * 2).dp),
                            cornerRadius = 12.dp,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tint.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Column(modifier = Modifier.padding(start = 10.dp, end = 12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.caption3,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
