import { useState, useEffect, useCallback } from "react";
import { router } from "expo-router";
import { storageService } from "../services/StorageService";
import { downloadManager } from "../services/download/DownloadManager";
import { sourceRegistry } from "../services/source/SourceRegistry";
import { backupService } from "../services/BackupService";
import { useThemeContext } from "../theme/ThemeContext";
import { useAppAlert } from "../context/AlertContext";
import type { SourceDownloadSettingsMap } from "../types";

// Validation boundary constants
const CONCURRENCY_MIN = 1;
const CONCURRENCY_MAX = 10;
const DELAY_MIN = 0;

// Validation functions
const validateConcurrency = (
  value: string,
): { valid: string; actual: number } => {
  const num = parseInt(value, 10) || CONCURRENCY_MIN;
  const clamped = Math.max(CONCURRENCY_MIN, Math.min(CONCURRENCY_MAX, num));
  return { valid: clamped.toString(), actual: clamped };
};

const validateDelay = (value: string): { valid: string; actual: number } => {
  const num = parseInt(value, 10) || DELAY_MIN;
  const clamped = Math.max(DELAY_MIN, num);
  return { valid: clamped.toString(), actual: clamped };
};

export const useSettings = () => {
  const { themeMode, setThemeMode } = useThemeContext();
  const { showAlert } = useAppAlert();
  const [globalConcurrency, setGlobalConcurrency] = useState("1");
  const [globalDelay, setGlobalDelay] = useState("500");
  const [persistedMaxChaptersPerEpub, setPersistedMaxChaptersPerEpub] =
    useState(150);
  const [concurrencyError, setConcurrencyError] = useState<
    string | undefined
  >();
  const [delayError, setDelayError] = useState<string | undefined>();
  const [sourceSettings, setSourceSettings] =
    useState<SourceDownloadSettingsMap>({});
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [availableProviders] = useState(() =>
    sourceRegistry.getAllProviders().map((p) => p.name),
  );

  // Working text values for source overrides (tracks what the user is typing)
  const [sourceConcurrencyText, setSourceConcurrencyText] = useState("");
  const [sourceDelayText, setSourceDelayText] = useState("");

  // Compute display values for text inputs
  const concurrency = selectedSource ? sourceConcurrencyText : globalConcurrency;
  const delay = selectedSource ? sourceDelayText : globalDelay;

  useEffect(() => {
    let mounted = true;
    const loadSettings = async () => {
      const [settings, loadedSourceSettings] = await Promise.all([
        storageService.getSettings(),
        storageService.getSourceDownloadSettings(),
      ]);
      if (!mounted) return;
      setGlobalConcurrency(settings.downloadConcurrency.toString());
      setGlobalDelay(settings.downloadDelay.toString());
      setPersistedMaxChaptersPerEpub(settings.maxChaptersPerEpub);
      setSourceSettings(loadedSourceSettings);
      setConcurrencyError(undefined);
      setDelayError(undefined);
    };

    void loadSettings();
    return () => {
      mounted = false;
    };
  }, []);

  // When selected source changes, initialize working text from loaded settings
  const handleSourceSelect = useCallback(
    (source: string | null) => {
      setSelectedSource(source);
      if (source) {
        const override = sourceSettings[source];
        const globalConc = parseInt(globalConcurrency, 10) || 1;
        const globalDel = parseInt(globalDelay, 10) || 0;
        setSourceConcurrencyText(
          (override?.concurrency ?? globalConc).toString(),
        );
        setSourceDelayText((override?.delay ?? globalDel).toString());
      }
      setConcurrencyError(undefined);
      setDelayError(undefined);
    },
    [sourceSettings, globalConcurrency, globalDelay],
  );

  const persistGlobalSettings = useCallback(
    async (concResult: { actual: number }, delayRes: { actual: number }) => {
      await storageService.saveSettings({
        downloadConcurrency: concResult.actual,
        downloadDelay: delayRes.actual,
        maxChaptersPerEpub: persistedMaxChaptersPerEpub,
      });
      downloadManager.updateSettings(
        concResult.actual,
        delayRes.actual,
        sourceSettings,
      );
    },
    [persistedMaxChaptersPerEpub, sourceSettings],
  );

  const persistSourceSettings = useCallback(
    async (updated: SourceDownloadSettingsMap) => {
      setSourceSettings(updated);
      await storageService.saveSourceDownloadSettings(updated);
      const globalConcurrencyResult = validateConcurrency(globalConcurrency);
      const globalDelayResult = validateDelay(globalDelay);
      downloadManager.updateSettings(
        globalConcurrencyResult.actual,
        globalDelayResult.actual,
        updated,
      );
    },
    [globalConcurrency, globalDelay],
  );

  const saveSettings = useCallback(async () => {
    const activeConcurrency = selectedSource ? sourceConcurrencyText : globalConcurrency;
    const activeDelay = selectedSource ? sourceDelayText : globalDelay;

    const concurrencyResult = validateConcurrency(activeConcurrency);
    const delayResult = validateDelay(activeDelay);

    setConcurrencyError(undefined);
    setDelayError(undefined);

    if (selectedSource) {
      const updated = {
        ...sourceSettings,
        [selectedSource]: {
          concurrency: concurrencyResult.actual,
          delay: delayResult.actual,
        },
      };
      await persistSourceSettings(updated);
    } else {
      setGlobalConcurrency(concurrencyResult.valid);
      setGlobalDelay(delayResult.valid);
      await persistGlobalSettings(concurrencyResult, delayResult);
    }
  }, [
    sourceConcurrencyText,
    sourceDelayText,
    globalConcurrency,
    globalDelay,
    selectedSource,
    sourceSettings,
    persistGlobalSettings,
    persistSourceSettings,
  ]);

  const handleConcurrencyChange = (text: string) => {
    if (selectedSource) {
      setSourceConcurrencyText(text);
    } else {
      setGlobalConcurrency(text);
    }
    const num = parseInt(text, 10);
    if (isNaN(num) || num < CONCURRENCY_MIN || num > CONCURRENCY_MAX) {
      setConcurrencyError(
        `Must be between ${CONCURRENCY_MIN} and ${CONCURRENCY_MAX}`,
      );
    } else {
      setConcurrencyError(undefined);
    }
  };

  const handleDelayChange = (text: string) => {
    if (selectedSource) {
      setSourceDelayText(text);
    } else {
      setGlobalDelay(text);
    }
    const num = parseInt(text, 10);
    if (isNaN(num) || num < DELAY_MIN) {
      setDelayError(`Must be ${DELAY_MIN} or greater`);
    } else {
      setDelayError(undefined);
    }
  };

  const handleConcurrencyBlur = () => {
    void saveSettings();
  };

  const handleDelayBlur = () => {
    void saveSettings();
  };

  const handleResetSource = useCallback(() => {
    if (!selectedSource) return;
    const updated = { ...sourceSettings };
    delete updated[selectedSource];
    void persistSourceSettings(updated);
    // Reset working text to global defaults
    const globalConc = parseInt(globalConcurrency, 10) || 1;
    const globalDel = parseInt(globalDelay, 10) || 0;
    setSourceConcurrencyText(globalConc.toString());
    setSourceDelayText(globalDel.toString());
  }, [selectedSource, sourceSettings, persistSourceSettings, globalConcurrency, globalDelay]);

  const clearData = () => {
    showAlert(
      "Clear Data",
      "Are you sure you want to delete all novels and settings? This action cannot be undone.",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Delete",
          style: "destructive",
          onPress: async () => {
            await storageService.clearAll();
            showAlert("Data Cleared", "All data has been deleted.", [
              { text: "OK", onPress: () => router.back() },
            ]);
          },
        },
      ],
    );
  };

  const handleExportBackup = async () => {
    const result = await backupService.exportBackup();
    showAlert(
      result.success ? "Export Complete" : "Export Failed",
      result.message,
    );
  };

  const handleImportBackup = async () => {
    showAlert(
      "Import Backup",
      "This will merge the backup with your existing library. Continue?",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Import",
          onPress: async () => {
            const result = await backupService.importBackup();
            showAlert(
              result.success ? "Import Complete" : "Import Failed",
              result.message,
              result.success
                ? [{ text: "OK", onPress: () => router.back() }]
                : undefined,
            );
          },
        },
      ],
    );
  };

  return {
    themeMode,
    setThemeMode,
    concurrency,
    delay,
    concurrencyError,
    delayError,
    handleConcurrencyChange,
    handleDelayChange,
    handleConcurrencyBlur,
    handleDelayBlur,
    clearData,
    handleExportBackup,
    handleImportBackup,
    selectedSource,
    setSelectedSource: handleSourceSelect,
    availableProviders,
    handleResetSource,
  };
};
