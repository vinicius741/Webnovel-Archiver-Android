import { useState, useEffect, useCallback } from "react";
import { storageService } from "../services/StorageService";
import { RegexCleanupRule } from "../types";
import { useAppAlert } from "../context/AlertContext";

export function useSentenceRemovalData() {
  const { showAlert } = useAppAlert();
  const [sentences, setSentences] = useState<string[]>([]);
  const [regexRules, setRegexRules] = useState<RegexCleanupRule[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    void loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [sentenceList, cleanupLoadResult] = await Promise.all([
        storageService.getSentenceRemovalList(),
        storageService.getRegexCleanupRulesWithDiagnostics(),
      ]);
      setSentences(sentenceList);
      setRegexRules(cleanupLoadResult.rules);

      if (cleanupLoadResult.rejected.length > 0) {
        const rejectedCount = cleanupLoadResult.rejected.length;
        showAlert(
          "Some Rules Were Skipped",
          `${rejectedCount} invalid regex rule${rejectedCount > 1 ? "s were" : " was"} ignored. Please review your cleanup rules.`,
        );
      }
    } finally {
      setLoading(false);
    }
  }, [showAlert]);

  const saveSentences = useCallback(async (list: string[]) => {
    setSentences(list);
    await storageService.saveSentenceRemovalList(list);
  }, []);

  const saveRegexRules = useCallback(async (rules: RegexCleanupRule[]) => {
    setRegexRules(rules);
    await storageService.saveRegexCleanupRules(rules);
  }, []);

  return {
    sentences,
    regexRules,
    loading,
    saveSentences,
    saveRegexRules,
    loadData,
  };
}
