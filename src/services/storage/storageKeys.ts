export const STORAGE_KEYS = {
  LIBRARY_LEGACY: "wa_library_v1",
  LIBRARY_INDEX: "wa_library_index_v1",
  LIBRARY_MIGRATION_COMPLETE: "wa_library_migration_complete_v1",
  LIBRARY: "wa_library_v1", // kept for backward-compat detection; new code uses per-story keys
  SETTINGS: "wa_settings_v1",
  SENTENCE_REMOVAL: "wa_sentence_removal_v1",
  REGEX_CLEANUP_RULES: "wa_regex_cleanup_rules_v1",
  TTS_SETTINGS: "wa_tts_settings_v1",
  TTS_SESSION: "wa_tts_session_v1",
  CHAPTER_FILTER_SETTINGS: "wa_chapter_filter_settings_v1",
  TABS: "wa_tabs_v1",
  FOLD_LAYOUT_MODE: "wa_fold_layout_mode_v1",
  SOURCE_DOWNLOAD_SETTINGS: "wa_source_download_settings_v1",
  THEME_DARK_VARIANT: "wa_theme_dark_variant_v1",
  THEME_LIGHT_VARIANT: "wa_theme_light_variant_v1",
  THEME_FOLLOW_SYSTEM: "wa_theme_follow_system_v1",
  THEME_MODE: "wa_theme_mode_v2",
  THEME_ACTIVE: "wa_theme_active_v1",
} as const;

/** Per-story key: `wa_story_{storyId}` → JSON of that story */
export const STORY_KEY_PREFIX = "wa_story_";

/** Build the AsyncStorage key for an individual story */
export function storyKey(storyId: string): string {
  return `${STORY_KEY_PREFIX}${storyId}`;
}
