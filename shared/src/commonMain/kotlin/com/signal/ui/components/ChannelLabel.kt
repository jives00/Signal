package com.signal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.signal.model.Channel
import com.signal.ui.LocalApp

/** Settings → Appearance → Channel numbers: whether channel numbers show anywhere. */
@Composable
internal fun showNumbers(): Boolean = LocalApp.current.showNumbers.collectAsState().value

/** A channel's name as a title, with its number in front unless numbers are hidden. */
@Composable
internal fun labelOf(ch: Channel): String = if (showNumbers()) "${ch.number}  ${ch.displayName}" else ch.displayName
