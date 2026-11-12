package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.view.ViewGroup
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.SourceMetadataPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun formatSourceDate(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(value))
