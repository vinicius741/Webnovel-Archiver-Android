import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Portal, Dialog, TextInput, Button, Text, Divider, Switch, SegmentedButtons } from 'react-native-paper';
import { RegexCleanupRule, RegexCleanupAppliesTo } from '../../types';
import { RuleDraft, EMPTY_RULE_DRAFT } from '../../types/sentenceRemoval';
import { validateRegexCleanupRule } from '../../utils/textCleanup';
import { applyTtsCleanupLines } from '../../utils/textCleanup';
import { useMemo } from 'react';

interface RuleDialogProps {
  visible: boolean;
  ruleDraft: RuleDraft;
  previewInput: string;
  onDraftChange: (draft: RuleDraft) => void;
  onPreviewInputChange: (value: string) => void;
  onSave: () => void;
  onDismiss: () => void;
}

export function RuleDialog({
  visible,
  ruleDraft,
  previewInput,
  onDraftChange,
  onPreviewInputChange,
  onSave,
  onDismiss,
}: RuleDialogProps) {
  const ruleValidation = useMemo(() => {
    return validateRegexCleanupRule({
      name: ruleDraft.name,
      pattern: ruleDraft.pattern,
      flags: ruleDraft.flags,
    });
  }, [ruleDraft.name, ruleDraft.pattern, ruleDraft.flags]);

  const previewOutput = useMemo(() => {
    if (!previewInput) return '';
    if (!ruleValidation.valid) return '';

    const previewRule: RegexCleanupRule = {
      id: 'preview',
      name: ruleDraft.name.trim() || 'Preview',
      pattern: ruleDraft.pattern.trim(),
      flags: ruleValidation.normalizedFlags || '',
      enabled: true,
      appliesTo: 'both',
    };

    return applyTtsCleanupLines(previewInput, [previewRule]);
  }, [ruleDraft.name, ruleDraft.pattern, previewInput, ruleValidation]);

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss}>
        <Dialog.Title>{ruleDraft.id ? 'Edit Regex Rule' : 'Add Regex Rule'}</Dialog.Title>
        <Dialog.Content>
          <TextInput
            label="Rule Name"
            value={ruleDraft.name}
            onChangeText={(value) => onDraftChange({ ...ruleDraft, name: value })}
            mode="outlined"
            style={styles.input}
          />
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
            Allowed flags: gimsu. Global replace is always enforced.
          </Text>
          {!ruleValidation.valid && (
            <Text variant="bodySmall" style={styles.errorText}>
              {ruleValidation.error}
            </Text>
          )}

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
            placeholder="Try text like ----- or ===== to test your rule"
          />
          <Text variant="bodySmall" style={styles.previewLabel}>Preview output</Text>
          <View style={styles.previewBox}>
            <Text variant="bodySmall">{previewOutput || '(No output)'}</Text>
          </View>
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss}>Cancel</Button>
          <Button onPress={onSave}>Save</Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

const styles = StyleSheet.create({
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
  targetLabel: {
    marginBottom: 8,
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
