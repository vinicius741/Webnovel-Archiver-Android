import { useCallback } from "react";
import { File, Paths } from "expo-file-system";
import * as Sharing from "expo-sharing";
import { RegexCleanupRule } from "../types";
import { useAppAlert } from "../context/AlertContext";

export function useExportRules() {
  const { showAlert } = useAppAlert();

  const exportRules = useCallback(
    async (sentences: string[], regexRules: RegexCleanupRule[]) => {
      try {
        const json = JSON.stringify({ sentences, regexRules }, null, 4);
        const file = new File(Paths.cache, "text_cleanup_rules.json");

        if (!file.exists) {
          file.create();
        }

        file.write(json);

        if (await Sharing.isAvailableAsync()) {
          await Sharing.shareAsync(file.uri, {
            mimeType: "application/json",
            dialogTitle: "Export Text Cleanup Rules",
            UTI: "public.json",
          });
        } else {
          showAlert("Error", "Sharing is not available on this device");
        }
      } catch (error) {
        console.error(error);
        showAlert("Export Failed", "Failed to export the file.");
      }
    },
    [showAlert],
  );

  return { exportRules };
}
