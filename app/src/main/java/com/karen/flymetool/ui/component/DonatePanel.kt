package com.karen.flymetool.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.karen.flymetool.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DonatePanel(
    modifier: Modifier = Modifier,
    imageSize: Int = 120
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.donate_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            DonateQR(
                label = stringResource(R.string.donate_alipay),
                imageRes = R.drawable.donate_alipay,
                imageSize = imageSize
            )
            DonateQR(
                label = stringResource(R.string.donate_wechat),
                imageRes = R.drawable.donate_wechat,
                imageSize = imageSize
            )
        }
    }
}

@Composable
private fun DonateQR(
    label: String,
    imageRes: Int,
    imageSize: Int
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = label,
            modifier = Modifier
                .size(imageSize.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            fontWeight = FontWeight.Medium
        )
    }
}
