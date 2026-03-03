import React, { useMemo } from 'react';
import { StyleSheet, View, ScrollView } from 'react-native';
import { Portal, Dialog, TextInput, Button, Text, Divider, Switch, SegmentedButtons, List } from 'react-native-paper';
import { RegexCleanupRule, RegexCleanupAppliesTo } from '../../types';
import { RuleDraft, RuleMode, QuickBuilderConfig, DEFAULT_QUICK_CONFIG } from '../../types/sentenceRemoval';
import { validateRegexCleanupRule, applyTtsCleanupLines } from '../../utils/textCleanup';
import { generateRuleName } from '../../utils/regexBuilder';
import { QuickBuilderForm } from './QuickBuilderForm';

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
  const { mode, quickConfig } = ruleDraft;
  
  const handleQuickConfigChange = (newConfig: QuickBuilderConfig) => {
    const generatedName = ruleDraft.id ? ruleDraft.name : generateRuleName(newConfig);
    onDraftChange({
      ...ruleDraft,
      quickConfig: newConfig,
      name: generatedName,
    });
  };

  const handleModeChange = (newMode: RuleMode) => {
    if (newMode === 'quick' && !ruleDraft.quickConfig) {
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
    if (!previewInput) return '';
    if (!ruleValidation.valid) return '';

    const previewRule: RegexCleanupRule = {
      id: 'preview',
      name: ruleDraft.name.trim() || 'Preview',
      pattern: effectivePattern.trim(),
      flags: ruleValidation.normalizedFlags || '',
      enabled: true,
      appliesTo: 'both',
    };

    return applyTtsCleanupLines(previewInput, [previewRule]);
  }, [ruleDraft.name, effectivePattern, previewInput, ruleValidation]);

  const displayPattern = mode === 'quick' ? effectivePattern : ruleDraft.pattern;
  const displayFlags = mode === 'quick' ? effectiveFlags : ruleDraft.flags;

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss} style={styles.dialog}>
        <Dialog.Title>{ruleDraft.id ? 'Edit Regex Rule' : 'Add Regex Rule'}</Dialog.Title>
        <Dialog.Content style={styles.content}>
          <ScrollView keyboardShouldPersistTaps="handled">
            <SegmentedButtons
              value={mode}
              onValueChange={handleModeChange}
              buttons={[
                { value: 'quick', label: 'Quick Builder' },
                { value: 'advanced', label: 'Advanced' },
              ]}
              style={styles.modeButtons}
            />

            <TextInput
              label="Rule Name"
              value={ruleDraft.name}
              onChangeText={(value) => onDraftChange({ ...ruleDraft, name: value })}
              mode="outlined"
              style={styles.input}
            />

            {mode === 'quick' ? (
              <QuickBuilderForm
                config={quickConfig || DEFAULT_QUICK_CONFIG}
                onChange={handleQuickConfigChange}
                effectivePattern={displayPattern}
                effectiveFlags={displayFlags}
              />
            ) : (
              <>
                <TextInput
                  label="Pattern"
                  value={ruleDraft.pattern}
                  onChangeText={(value) => onDraftChange({ ...ruleDraft, pattern: value })}
                  mode="outlined"
                  style={styles.input}
                  placeholder="(?:[-=]){5,}"
                />
                <TextInput
                  label="Flags"
                  value={ruleDraft.flags}
                  onChangeText={(value) => onDraftChange({ ...ruleDraft, flags: value })}
                  mode="outlined"
                  style={styles.input}
                  placeholder="im"
                  autoCapitalize="none"
                />
                <Text variant="bodySmall" style={styles.helpText}>
                  Allowed flags: gimsu. Use separate pattern/flags or paste /pattern/flags directly. Global replace is always enforced.
                </Text>
              </>
            )}

            {!ruleValidation.valid && (
              <Text variant="bodySmall" style={styles.errorText}>
                {ruleValidation.error}
              </Text>
            )}

            <List.Accordion
              title="Advanced Settings"
              expanded={ruleDraft.showAdvanced}
              onPress={() => onDraftChange({ ...ruleDraft, showAdvanced: !ruleDraft.showAdvanced })}
              style={styles.accordion}
            >
              <Text variant="labelMedium" style={styles.targetLabel}>Apply To</Text>
              <SegmentedButtons
                value={ruleDraft.appliesTo}
                onValueChange={(value) => onDraftChange({ ...ruleDraft, appliesTo: value as RegexCleanupAppliesTo })}
                buttons={[
                  { value: 'both', label: 'Both' },
                  { value: 'download', label: 'Download' },
                  { value: 'tts', label: 'TTS' },
                ]}
              />

              <View style={styles.enabledRow}>
                <Text variant="bodyMedium">Enabled</Text>
                <Switch
                  value={ruleDraft.enabled}
                  onValueChange={(value) => onDraftChange({ ...ruleDraft, enabled: value })}
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
              placeholder={mode === 'quick' 
                ? "Try: ===== or ------ or ######" 
                : "Try text like ----- or ===== to test your rule"}
            />
            <Text variant="bodySmall" style={styles.previewLabel}>Preview output</Text>
            <View style={styles.previewBox}>
              <Text variant="bodySmall">{previewOutput || '(No output)'}</Text>
            </View>
          </ScrollView>
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss}>Cancel</Button>
          <Button onPress={onSave} disabled={!ruleValidation.valid}>Save</Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

const styles = StyleSheet.create({
  dialog: {
    maxHeight: '90%',
  },
  content: {
    paddingBottom: 8,
  },
  modeButtons: {
    marginBottom: 12,
  },
  input: {
    marginBottom: 10,
  },
  helpText: {
    opacity: 0.75,
    marginBottom: 10,
  },
  errorText: {
    color: 'red',
    marginBottom: 10,
  },
  accordion: {
    marginVertical: 8,
    paddingHorizontal: 0,
  },
  targetLabel: {
    marginBottom: 8,
    marginTop: 4,
  },
  enabledRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 12,
  },
  previewDivider: {
    marginVertical: 12,
  },
  previewLabel: {
    marginBottom: 6,
  },
  previewBox: {
    borderWidth: 1,
    borderColor: 'rgba(0,0,0,0.2)',
    borderRadius: 8,
    padding: 10,
    minHeight: 52,
    justifyContent: 'center',
  },
});
