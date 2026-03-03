import React, { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { Portal, Dialog, TextInput, Button, Text, Divider, Switch, SegmentedButtons } from 'react-native-paper';
import { RegexCleanupRule, RegexCleanupAppliesTo } from '../../types';
import { RuleDraft, RuleMode, QuickBuilderConfig, DEFAULT_QUICK_CONFIG } from '../../types/sentenceRemoval';
import { validateRegexCleanupRule, applyTtsCleanupLines } from '../../utils/textCleanup';
import { generateQuickPattern, generateRuleName, parseQuickPattern } from '../../utils/regexBuilder';
import { QuickBuilderForm } from './QuickBuilderForm';

interface RuleDialogProps {
  visible: boolean;
  ruleDraft: RuleDraft;
  previewInput: string;
  onDraftChange: (draft: RuleDraft) => void;
  onPreviewInputChange: (value: string) => void;
  onSave: () => void;
  onDismiss: () => void;
}

function quickConfigEquals(a: QuickBuilderConfig, b: QuickBuilderConfig): boolean {
  return a.characters === b.characters && a.minCount === b.minCount && a.wholeLine === b.wholeLine;
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
  const [mode, setMode] = useState<RuleMode>('quick');
  const [quickConfig, setQuickConfig] = useState<QuickBuilderConfig>(DEFAULT_QUICK_CONFIG);
  const prevQuickConfigRef = useRef<QuickBuilderConfig>(DEFAULT_QUICK_CONFIG);
  const isInitializedRef = useRef(false);

  const updateDraftFromQuickConfig = useCallback((config: QuickBuilderConfig, currentDraft: RuleDraft) => {
    const generated = generateQuickPattern(config);
    const generatedName = generateRuleName(config);
    
    if (generated.pattern) {
      onDraftChange({
        ...currentDraft,
        pattern: generated.pattern,
        flags: generated.flags,
        name: currentDraft.id ? currentDraft.name : generatedName,
      });
    }
  }, [onDraftChange]);

  useEffect(() => {
    if (visible) {
      isInitializedRef.current = true;
      
      if (ruleDraft.id && ruleDraft.pattern) {
        const parsed = parseQuickPattern(ruleDraft.pattern, ruleDraft.flags);
        if (parsed) {
          setMode('quick');
          setQuickConfig(parsed);
          prevQuickConfigRef.current = parsed;
        } else {
          setMode('advanced');
        }
      } else {
        setMode('quick');
        setQuickConfig(DEFAULT_QUICK_CONFIG);
        prevQuickConfigRef.current = DEFAULT_QUICK_CONFIG;
      }
    } else {
      isInitializedRef.current = false;
    }
  }, [visible, ruleDraft.id, ruleDraft.pattern, ruleDraft.flags]);

  useEffect(() => {
    if (!isInitializedRef.current || mode !== 'quick' || !visible) {
      return;
    }

    if (!quickConfigEquals(quickConfig, prevQuickConfigRef.current)) {
      prevQuickConfigRef.current = quickConfig;
      updateDraftFromQuickConfig(quickConfig, ruleDraft);
    }
  }, [quickConfig, mode, visible, ruleDraft, updateDraftFromQuickConfig]);

  const handleQuickConfigChange = (newConfig: QuickBuilderConfig) => {
    setQuickConfig(newConfig);
  };

  const handleModeChange = (newMode: RuleMode) => {
    if (newMode === 'quick') {
      const generated = generateQuickPattern(quickConfig);
      const generatedName = generateRuleName(quickConfig);
      
      onDraftChange({
        ...ruleDraft,
        pattern: generated.pattern,
        flags: generated.flags,
        name: ruleDraft.id ? ruleDraft.name : generatedName,
      });
      prevQuickConfigRef.current = quickConfig;
    }
    setMode(newMode);
  };

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
      <Dialog visible={visible} onDismiss={onDismiss} style={styles.dialog}>
        <Dialog.Title>{ruleDraft.id ? 'Edit Regex Rule' : 'Add Regex Rule'}</Dialog.Title>
        <Dialog.Content style={styles.content}>
          <SegmentedButtons
            value={mode}
            onValueChange={(value) => handleModeChange(value as RuleMode)}
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
              config={quickConfig}
              onChange={handleQuickConfigChange}
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
                Allowed flags: gimsu. Global replace is always enforced.
              </Text>
            </>
          )}

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
            placeholder={mode === 'quick' 
              ? "Try: ===== or ------ or ######" 
              : "Try text like ----- or ===== to test your rule"}
          />
          <Text variant="bodySmall" style={styles.previewLabel}>Preview output</Text>
          <View style={styles.previewBox}>
            <Text variant="bodySmall">{previewOutput || '(No output)'}</Text>
          </View>
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
