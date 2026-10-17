package omnibeat.app.ui

import androidx.annotation.StringRes
import omnibeat.app.R
import omnibeat.app.model.AccentPalette
import omnibeat.app.model.MainPage
import omnibeat.app.model.StationSortMode

@StringRes
fun MainPage.titleRes(): Int {
    return when (this) {
        MainPage.Stations -> R.string.page_stations
        MainPage.Favorites -> R.string.page_favorites
        MainPage.Podcasts -> R.string.page_podcasts
        MainPage.ExportImport -> R.string.page_export_import
        MainPage.Translation -> R.string.page_translation
        MainPage.SearchOnline -> R.string.action_search_online
        MainPage.Settings -> R.string.page_settings
        MainPage.About -> R.string.page_about
    }
}

@StringRes
fun AccentPalette.labelRes(): Int {
    return when (this) {
        AccentPalette.Violet -> R.string.accent_palette_violet
        AccentPalette.Ocean -> R.string.accent_palette_ocean
        AccentPalette.Teal -> R.string.accent_palette_teal
        AccentPalette.Forest -> R.string.accent_palette_forest
        AccentPalette.Amber -> R.string.accent_palette_amber
        AccentPalette.Rose -> R.string.accent_palette_rose
        AccentPalette.Taiji -> R.string.accent_palette_taiji
    }
}

@StringRes
fun StationSortMode.labelRes(): Int {
    return when (this) {
        StationSortMode.DateAdded -> R.string.sort_date_added
        StationSortMode.StationTitle -> R.string.sort_station_title
        StationSortMode.FavoritesFirst -> R.string.sort_favorites
        StationSortMode.Custom -> R.string.sort_custom
    }
}
