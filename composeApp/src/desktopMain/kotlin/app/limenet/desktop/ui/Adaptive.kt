package app.limenet.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.limenet.ui.theme.Spacing

/**
 * Адаптация под телефон. Окно уже [COMPACT_MAX] — «компактный» режим: нижняя навигация,
 * поля 16 вместо 24, кнопки не ниже 40dp, ряды из нескольких кнопок переносятся.
 * Флаг считается один раз в корне (`LimeNetApp`) по ширине окна, поэтому на ПК достаточно
 * сузить окно до ~360–420dp, чтобы увидеть телефонную раскладку.
 */
internal val COMPACT_MAX: Dp = 600.dp

internal val LocalCompact = compositionLocalOf { false }

/** Боковые поля экрана: 24 на широком окне, 16 на телефоне (значения шкалы `Spacing`). */
@Composable
internal fun screenPad(): Dp = if (LocalCompact.current) Spacing.l else Spacing.xl

/** Минимальная высота нажимаемого элемента: 40dp на телефоне, на ПК без ограничения. */
@Composable
internal fun touchMin(): Dp = if (LocalCompact.current) 40.dp else 0.dp
