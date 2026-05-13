import React, { useMemo } from "react";
import { StyleSheet, View, Dimensions, ScrollView } from "react-native";
import {
  Portal,
  Dialog,
  TextInput,
  Text,
  Divider,
  Switch,
  List,
  useTheme,
} from "react-native-paper";
import { AppButton } from "../theme/AppButton";
import { AppSegmentedButtons } from "../theme/AppSegmentedButtons";
import { RegexCleanupRule } from "../../types";
import {
  RuleDraft,
  RuleMode,
  QuickBuilderConfig,
  DEFAULT_QUICK_CONFIG,
} from "../../types/sentenceRemoval";
import {
  validateRegexCleanupRule,
} from "../../utils/regexValidation";
import { applyTtsCleanupLines } from "../../utils/textCleanup";
import { generateRuleName } from "../../utils/regexBuilder";
import { QuickBuilderForm } from "./QuickBuilderForm";

const { height: SCREEN_HEIGHT } = Dimensions.get("window");

interface RuleDialogProps {
  visible: boolean;
  ruleDraft: RuleDraft;
  previewInput: string;
  effectivePattern: string;
  effectiveFlags: string;
  onDraftChange: (draft: RuleDraft) => void;
  onPreviewInputChange: (value: string) => void;
  onSave: () => void;
  onDismiss: () => void;
}

export function RuleDialog({
  visible,
  ruleDraft,
  previewInput,
  effectivePattern,
  effectiveFlags,
  onDraftChange,
  onPreviewInputChange,
  onSave,
  onDismiss,
}: RuleDialogProps) {
  const theme = useTheme();
  const { mode, quickConfig } = ruleDraft;

  const handleQuickConfigChange = (newConfig: QuickBuilderConfig) => {
    const generatedName = ruleDraft.id
      ? ruleDraft.name
      : generateRuleName(newConfig);
    onDraftChange({
      ...ruleDraft,
      quickConfig: newConfig,
      name: generatedName,
    });
  };

  const handleModeChange = (newMode: RuleMode) => {
    if (newMode === "quick" && !ruleDraft.quickConfig) {
      onDraftChange({
        ...ruleDraft,
        mode: newMode,
        quickConfig: DEFAULT_QUICK_CONFIG,
      });
    } else {
      onDraftChange({
        ...ruleDraft,
        mode: newMode,
      });
    }
  };

  const ruleValidation = useMemo(() => {
    return validateRegexCleanupRule({
      name: ruleDraft.name,
      pattern: effectivePattern,
      flags: effectiveFlags,
    });
  }, [ruleDraft.name, effectivePattern, effectiveFlags]);

  const previewOutput = useMemo(() => {
    if (!previewInput) return "";
    if (!ruleValidation.valid) return "";

    const previewRule: RegexCleanupRule = {
      id: "preview",
      name: ruleDraft.name.trim() || "Preview",
      pattern: effectivePattern.trim(),
      flags: ruleValidation.normalizedFlags || "",
      enabled: true,
      appliesTo: "both",
    };

    return applyTtsCleanupLines(previewInput, [previewRule]);
  }, [ruleDraft.name, effectivePattern, previewInput, ruleValidation]);

  const displayPattern =
    mode === "quick" ? effectivePattern : ruleDraft.pattern;
  const displayFlags = mode === "quick" ? effectiveFlags : ruleDraft.flags;
  const isQuickValid =
    mode === "quick" &&
    quickConfig &&
    quickConfig.characters.length > 0 &&
    quickConfig.minCount >= 1;

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss} style={styles.dialog}>
        <Dialog.Title>
          {ruleDraft.id ? "Edit Regex Rule" : "Add Regex Rule"}
        </Dialog.Title>
        <Dialog.Content style={styles.content}>
          <ScrollView
            style={styles.scrollView}
            contentContainerStyle={styles.scrollContent}
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={true}
          >
            <AppSegmentedButtons
              value={mode}
              onValueChange={handleModeChange}
              buttons={[
                { value: "quick", label: "Quick Builder" },
                { value: "advanced", label: "Advanced" },
              ]}
              style={styles.modeButtons}
            />

            <TextInput
              label="Rule Name"
              value={ruleDraft.name}
              onChangeText={(value) =>
                onDraftChange({ ...ruleDraft, name: value })
              }
              mode="outlined"
              style={styles.input}
            />

            {mode === "quick" ? (
              <QuickBuilderForm
                config={quickConfig || DEFAULT_QUICK_CONFIG}
                onChange={handleQuickConfigChange}
              />
            ) : (
              <>
                <TextInput
                  label="Pattern"
                  value={ruleDraft.pattern}
                  onChangeText={(value) =>
                    onDraftChange({ ...ruleDraft, pattern: value })
                  }
                  mode="outlined"
                  style={styles.input}
                  placeholder="(?:[-=]){5,}"
                />
                <TextInput
                  label="Flags"
                  value={ruleDraft.flags}
                  onChangeText={(value) =>
                    onDraftChange({ ...ruleDraft, flags: value })
                  }
                  mode="outlined"
                  style={styles.input}
                  placeholder="im"
                  autoCapitalize="none"
                />
                <Text variant="bodySmall" style={styles.helpText}>
                  Allowed flags: gimsu. Use separate pattern/flags or paste
                  /pattern/flags directly.
                </Text>
              </>
            )}

            {!ruleValidation.valid && (
              <Text variant="bodySmall" style={[styles.errorText, { color: theme.colors.error }]}>
                {ruleValidation.error}
              </Text>
            )}

            <List.Accordion
              title="Advanced"
              expanded={ruleDraft.showAdvanced}
              onPress={() =>
                onDraftChange({
                  ...ruleDraft,
                  showAdvanced: !ruleDraft.showAdvanced,
                })
              }
              style={styles.accordion}
            >
              {mode === "quick" && (
                <View style={styles.patternPreview}>
                  <Text variant="labelMedium" style={styles.patternLabel}>
                    Generated Pattern
                  </Text>
                  <View
                    style={[
                      styles.patternBox,
                      { backgroundColor: theme.colors.surfaceVariant },
                      !isQuickValid && styles.patternBoxEmpty,
                    ]}
                  >
                    <Text variant="bodySmall" style={styles.patternText}>
                      {isQuickValid
                        ? `/${displayPattern}/${displayFlags}`
                        : "Enter character(s) to generate pattern"}
                    </Text>
                  </View>
                </View>
              )}

              <Text variant="labelMedium" style={styles.targetLabel}>
                Apply To
              </Text>
              <AppSegmentedButtons
                value={ruleDraft.appliesTo}
                onValueChange={(value) =>
                  onDraftChange({
                    ...ruleDraft,
                    appliesTo: value,
                  })
                }
                buttons={[
                  { value: "both", label: "Both" },
                  { value: "download", label: "Download" },
                  { value: "tts", label: "TTS" },
                ]}
              />

              <View style={styles.enabledRow}>
                <Text variant="bodyMedium">Enabled</Text>
                <Switch
                  value={ruleDraft.enabled}
                  onValueChange={(value) =>
                    onDraftChange({ ...ruleDraft, enabled: value })
                  }
                />
              </View>
            </List.Accordion>

            <Divider style={styles.previewDivider} />
            <Text variant="labelMedium">Test Preview</Text>
            <TextInput
              label="Preview input"
              value={previewInput}
              onChangeText={onPreviewInputChange}
              mode="outlined"
              multiline
              numberOfLines={3}
              style={styles.input}
              placeholder={
                mode === "quick"
                  ? "Try: ===== or ------ or ######"
                  : "Try text like ----- or ===== to test your rule"
              }
            />
            <Text variant="bodySmall" style={styles.previewLabel}>
              Preview output
            </Text>
            <View style={[styles.previewBox, { borderColor: theme.colors.outline }]}>
              <Text variant="bodySmall">{previewOutput || "(No output)"}</Text>
            </View>
          </ScrollView>
        </Dialog.Content>
        <Dialog.Actions>
          <AppButton onPress={onDismiss}>Cancel</AppButton>
          <AppButton onPress={onSave} disabled={!ruleValidation.valid}>
            Save
          </AppButton>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

const styles = StyleSheet.create({
  dialog: {
    maxHeight: SCREEN_HEIGHT * 0.85,
  },
  content: {
    paddingHorizontal: 24,
    paddingVertical: 8,
  },
  scrollView: {
    maxHeight: SCREEN_HEIGHT * 0.55,
  },
  scrollContent: {
    paddingBottom: 16,
  },
  modeButtons: {
    marginBottom: 12,
  },
  input: {
    marginBottom: 8,
  },
  helpText: {
    opacity: 0.75,
    marginBottom: 8,
  },
  errorText: {
    marginBottom: 8,
  },
  accordion: {
    marginVertical: 4,
    paddingHorizontal: 0,
  },
  patternPreview: {
    marginBottom: 8,
  },
  patternLabel: {
    marginBottom: 4,
  },
  patternBox: {
    borderRadius: 6,
    padding: 10,
  },
  patternBoxEmpty: {
    opacity: 0.6,
  },
  patternText: {
    fontFamily: "monospace",
  },
  targetLabel: {
    marginBottom: 4,
    marginTop: 4,
  },
  enabledRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginTop: 8,
  },
  previewDivider: {
    marginVertical: 8,
  },
  previewLabel: {
    marginBottom: 4,
  },
  previewBox: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    minHeight: 52,
    justifyContent: "center",
  },
});
