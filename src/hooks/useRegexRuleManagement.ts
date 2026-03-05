import { useCallback, useMemo, useState } from "react";
import { RegexCleanupRule } from "../types";
import {
  RuleDraft,
  EMPTY_RULE_DRAFT,
  createRuleId,
  DEFAULT_QUICK_CONFIG,
} from "../types/sentenceRemoval";
import { validateRegexCleanupRule } from "../utils/textCleanup";
import {
  generateQuickPattern,
  parseQuickPattern,
  generateRuleName,
} from "../utils/regexBuilder";
import { useAppAlert } from "../context/AlertContext";

export interface RegexRuleManagementState {
  dialogVisible: boolean;
  ruleDraft: RuleDraft;
  previewInput: string;
}

export function useRegexRuleManagement(
  regexRules: RegexCleanupRule[],
  saveRegexRules: (rules: RegexCleanupRule[]) => Promise<void>,
) {
  const { showAlert } = useAppAlert();

  const [state, setState] = useState<RegexRuleManagementState>({
    dialogVisible: false,
    ruleDraft: EMPTY_RULE_DRAFT,
    previewInput: "",
  });

  const derivedPattern = useMemo(() => {
    if (state.ruleDraft.mode === "quick" && state.ruleDraft.quickConfig) {
      return generateQuickPattern(state.ruleDraft.quickConfig);
    }
    return { pattern: state.ruleDraft.pattern, flags: state.ruleDraft.flags };
  }, [state.ruleDraft]);

  const effectivePattern =
    state.ruleDraft.mode === "quick"
      ? derivedPattern.pattern
      : state.ruleDraft.pattern;
  const effectiveFlags =
    state.ruleDraft.mode === "quick" ? derivedPattern.flags : state.ruleDraft.flags;

  const validation = useMemo(() => {
    return validateRegexCleanupRule({
      name: state.ruleDraft.name,
      pattern: effectivePattern,
      flags: effectiveFlags,
    });
  }, [state.ruleDraft.name, effectivePattern, effectiveFlags]);

  const openDialog = useCallback((rule?: RegexCleanupRule) => {
    if (!rule) {
      setState({
        dialogVisible: true,
        ruleDraft: EMPTY_RULE_DRAFT,
        previewInput: "",
      });
    } else {
      const parsed = parseQuickPattern(rule.pattern, rule.flags);
      const mode = parsed ? "quick" : "advanced";
      const quickConfig = parsed || DEFAULT_QUICK_CONFIG;

      setState({
        dialogVisible: true,
        ruleDraft: {
          id: rule.id,
          name: rule.name,
          pattern: rule.pattern,
          flags: rule.flags,
          enabled: rule.enabled,
          appliesTo: rule.appliesTo,
          mode,
          quickConfig,
        },
        previewInput: "",
      });
    }
  }, []);

  const closeDialog = useCallback(() => {
    setState({
      dialogVisible: false,
      ruleDraft: EMPTY_RULE_DRAFT,
      previewInput: "",
    });
  }, []);

  const updateDraft = useCallback((draft: RuleDraft) => {
    setState((prev) => ({ ...prev, ruleDraft: draft }));
  }, []);

  const updatePreviewInput = useCallback((input: string) => {
    setState((prev) => ({ ...prev, previewInput: input }));
  }, []);

  const validateRule = useCallback((): { valid: boolean; error?: string } => {
    if (!validation.valid) {
      return { valid: false, error: validation.error || "Please review the regex rule" };
    }

    const normalizedPattern = validation.normalizedPattern || effectivePattern.trim();
    const normalizedFlags = validation.normalizedFlags || "";

    const duplicate = regexRules.find(
      (r) =>
        r.id !== state.ruleDraft.id &&
        r.pattern === normalizedPattern &&
        r.flags === normalizedFlags &&
        r.appliesTo === state.ruleDraft.appliesTo,
    );

    if (duplicate) {
      return { valid: false, error: "A similar regex rule already exists" };
    }

    return { valid: true };
  }, [validation, effectivePattern, regexRules, state.ruleDraft.id, state.ruleDraft.appliesTo]);

  const handleSave = useCallback(() => {
    const validationResult = validateRule();
    if (!validationResult.valid) {
      showAlert("Invalid Rule", validationResult.error || "Please review the regex rule");
      return false;
    }

    const normalizedPattern = validation.normalizedPattern || effectivePattern.trim();
    const normalizedFlags = validation.normalizedFlags || "";
    const normalizedName =
      state.ruleDraft.name.trim() ||
      (state.ruleDraft.mode === "quick" && state.ruleDraft.quickConfig
        ? generateRuleName(state.ruleDraft.quickConfig)
        : "Unnamed Rule");

    const nextRule: RegexCleanupRule = {
      id: state.ruleDraft.id || createRuleId(),
      name: normalizedName,
      pattern: normalizedPattern,
      flags: normalizedFlags,
      enabled: state.ruleDraft.enabled,
      appliesTo: state.ruleDraft.appliesTo,
    };

    const list = [...regexRules];
    const existingIndex = list.findIndex((r) => r.id === nextRule.id);
    if (existingIndex >= 0) {
      list[existingIndex] = nextRule;
    } else {
      list.unshift(nextRule);
    }

    void saveRegexRules(list);
    closeDialog();
    return true;
  }, [
    validateRule,
    showAlert,
    effectivePattern,
    validation,
    state.ruleDraft,
    regexRules,
    saveRegexRules,
    closeDialog,
  ]);

  const handleDelete = useCallback(
    (ruleId: string) => {
      showAlert(
        "Remove Regex Rule",
        "Are you sure you want to delete this regex cleanup rule?",
        [
          { text: "Cancel", style: "cancel" },
          {
            text: "Delete",
            style: "destructive",
            onPress: () => {
              const list = regexRules.filter((rule) => rule.id !== ruleId);
              void saveRegexRules(list);
            },
          },
        ],
      );
    },
    [regexRules, saveRegexRules, showAlert],
  );

  const handleToggle = useCallback(
    (ruleId: string, enabled: boolean) => {
      const updatedRules = regexRules.map((rule) => {
        if (rule.id !== ruleId) return rule;
        return { ...rule, enabled };
      });
      void saveRegexRules(updatedRules);
    },
    [regexRules, saveRegexRules],
  );

  return {
    ...state,
    effectivePattern,
    effectiveFlags,
    validation,
    openDialog,
    closeDialog,
    updateDraft,
    updatePreviewInput,
    handleSave,
    handleDelete,
    handleToggle,
  };
}
