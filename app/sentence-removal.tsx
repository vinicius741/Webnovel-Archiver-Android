import React, { useState, useMemo } from 'react';
import { StyleSheet, View, ScrollView } from 'react-native';
import { Appbar, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { router, Stack } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useAppAlert } from '../src/context/AlertContext';
import { RegexCleanupRule } from '../src/types';
import { TabValue, RuleDraft, EMPTY_RULE_DRAFT, createRuleId, QuickBuilderConfig, DEFAULT_QUICK_CONFIG } from '../src/types/sentenceRemoval';
import { validateRegexCleanupRule } from '../src/utils/textCleanup';
import { generateQuickPattern, parseQuickPattern, generateRuleName } from '../src/utils/regexBuilder';
import { useSentenceRemovalData } from '../src/hooks/useSentenceRemovalData';
import { SentenceList, RegexRuleList, SentenceDialog, RuleDialog } from '../src/components/sentence-removal';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

function getDerivedPattern(draft: RuleDraft): { pattern: string; flags: string } {
  if (draft.mode === 'quick' && draft.quickConfig) {
    return generateQuickPattern(draft.quickConfig);
  }
  return { pattern: draft.pattern, flags: draft.flags };
}

export default function SentenceRemovalScreen() {
  const { showAlert } = useAppAlert();
  const { sentences, regexRules, loading, saveSentences, saveRegexRules } = useSentenceRemovalData();
  const [activeTab, setActiveTab] = useState<TabValue>('sentences');

  const [sentenceDialogVisible, setSentenceDialogVisible] = useState(false);
  const [newSentence, setNewSentence] = useState('');
  const [editingSentenceIndex, setEditingSentenceIndex] = useState<number | null>(null);

  const [ruleDialogVisible, setRuleDialogVisible] = useState(false);
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(EMPTY_RULE_DRAFT);
  const [rulePreviewInput, setRulePreviewInput] = useState('');

  const handleExport = async () => {
    try {
      const json = JSON.stringify({ sentences, regexRules }, null, 4);
      const file = new File(Paths.cache, 'text_cleanup_rules.json');

      if (!file.exists) {
        file.create();
      }

      file.write(json);

      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(file.uri, {
          mimeType: 'application/json',
          dialogTitle: 'Export Text Cleanup Rules',
          UTI: 'public.json'
        });
      } else {
        showAlert('Error', 'Sharing is not available on this device');
      }
    } catch (error) {
      console.error(error);
      showAlert('Export Failed', 'Failed to export the file.');
    }
  };

  const handleSaveSentence = () => {
    const trimmed = newSentence.trim();
    if (!trimmed) return;

    const existingIndex = sentences.indexOf(trimmed);
    if (existingIndex !== -1 && (editingSentenceIndex === null || existingIndex !== editingSentenceIndex)) {
      showAlert('Duplicate', 'This sentence is already in the removal list.');
      return;
    }

    const list = [...sentences];
    if (editingSentenceIndex !== null) {
      list[editingSentenceIndex] = trimmed;
    } else {
      list.unshift(trimmed);
    }

    void saveSentences(list);
    setSentenceDialogVisible(false);
    setNewSentence('');
    setEditingSentenceIndex(null);
  };

  const confirmDeleteSentence = (index: number) => {
    showAlert(
      'Remove Sentence',
      'Are you sure you want to remove this sentence from the blocklist?',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            const list = sentences.filter((_, i) => i !== index);
            void saveSentences(list);
          }
        }
      ]
    );
  };

  const openSentenceDialog = (sentence: string = '', index: number | null = null) => {
    setNewSentence(sentence);
    setEditingSentenceIndex(index);
    setSentenceDialogVisible(true);
  };

  const openRuleDialog = (rule?: RegexCleanupRule) => {
    if (!rule) {
      setRuleDraft(EMPTY_RULE_DRAFT);
    } else {
      const parsed = parseQuickPattern(rule.pattern, rule.flags);
      const mode = parsed ? 'quick' : 'advanced';
      const quickConfig = parsed || DEFAULT_QUICK_CONFIG;
      
      setRuleDraft({
        id: rule.id,
        name: rule.name,
        pattern: rule.pattern,
        flags: rule.flags,
        enabled: rule.enabled,
        appliesTo: rule.appliesTo,
        mode,
        quickConfig,
      });
    }
    setRuleDialogVisible(true);
  };

  const closeRuleDialog = () => {
    setRuleDialogVisible(false);
    setRuleDraft(EMPTY_RULE_DRAFT);
  };

  const derivedPattern = useMemo(() => getDerivedPattern(ruleDraft), [ruleDraft]);
  
  const effectivePattern = ruleDraft.mode === 'quick' ? derivedPattern.pattern : ruleDraft.pattern;
  const effectiveFlags = ruleDraft.mode === 'quick' ? derivedPattern.flags : ruleDraft.flags;

  const ruleValidation = useMemo(() => {
    return validateRegexCleanupRule({
      name: ruleDraft.name,
      pattern: effectivePattern,
      flags: effectiveFlags,
    });
  }, [ruleDraft.name, effectivePattern, effectiveFlags]);

  const handleSaveRule = () => {
    if (!ruleValidation.valid) {
      showAlert('Invalid Rule', ruleValidation.error || 'Please review the regex rule.');
      return;
    }

    const normalizedPattern = ruleValidation.normalizedPattern || effectivePattern.trim();
    const normalizedFlags = ruleValidation.normalizedFlags || '';
    const normalizedName = ruleDraft.name.trim() || (ruleDraft.mode === 'quick' && ruleDraft.quickConfig 
      ? generateRuleName(ruleDraft.quickConfig) 
      : 'Unnamed Rule');

    const duplicate = regexRules.find(rule =>
      rule.id !== ruleDraft.id &&
      rule.pattern === normalizedPattern &&
      rule.flags === normalizedFlags &&
      rule.appliesTo === ruleDraft.appliesTo
    );

    if (duplicate) {
      showAlert('Duplicate', 'A similar regex rule already exists.');
      return;
    }

    const nextRule: RegexCleanupRule = {
      id: ruleDraft.id || createRuleId(),
      name: normalizedName,
      pattern: normalizedPattern,
      flags: normalizedFlags,
      enabled: ruleDraft.enabled,
      appliesTo: ruleDraft.appliesTo,
    };

    const list = [...regexRules];
    const existingIndex = list.findIndex(rule => rule.id === nextRule.id);
    if (existingIndex >= 0) {
      list[existingIndex] = nextRule;
    } else {
      list.unshift(nextRule);
    }

    void saveRegexRules(list);
    closeRuleDialog();
  };

  const toggleRule = (ruleId: string, enabled: boolean) => {
    const updatedRules = regexRules.map(rule => {
      if (rule.id !== ruleId) return rule;
      return { ...rule, enabled };
    });
    void saveRegexRules(updatedRules);
  };

  const confirmDeleteRule = (ruleId: string) => {
    showAlert(
      'Remove Regex Rule',
      'Are you sure you want to delete this regex cleanup rule?',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            const list = regexRules.filter(rule => rule.id !== ruleId);
            void saveRegexRules(list);
          }
        }
      ]
    );
  };

  if (loading) {
    return (
      <ScreenContainer edges={['bottom', 'left', 'right']}>
        <Stack.Screen options={{ headerShown: false }} />
        <Appbar.Header>
          <Appbar.BackAction onPress={() => router.back()} />
          <Appbar.Content title="Text Cleanup" />
        </Appbar.Header>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
        </View>
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']}>
      <Stack.Screen options={{ headerShown: false }} />
      <Appbar.Header>
        <Appbar.BackAction onPress={() => router.back()} />
        <Appbar.Content title="Text Cleanup" />
        <Appbar.Action icon="export-variant" onPress={handleExport} accessibilityLabel="Export JSON" />
      </Appbar.Header>

      <ScrollView contentContainerStyle={styles.content}>
        <SegmentedButtons
          value={activeTab}
          onValueChange={(value) => setActiveTab(value as TabValue)}
          buttons={[
            { value: 'sentences', label: 'Sentences' },
            { value: 'regex', label: 'Regex Rules' },
          ]}
          style={styles.tabs}
        />

        {activeTab === 'sentences' && (
          <SentenceList
            sentences={sentences}
            onAdd={() => openSentenceDialog()}
            onEdit={openSentenceDialog}
            onDelete={confirmDeleteSentence}
          />
        )}

        {activeTab === 'regex' && (
          <RegexRuleList
            rules={regexRules}
            onAdd={() => openRuleDialog()}
            onEdit={openRuleDialog}
            onDelete={confirmDeleteRule}
            onToggle={toggleRule}
          />
        )}
      </ScrollView>

      <SentenceDialog
        visible={sentenceDialogVisible}
        sentence={newSentence}
        isEditing={editingSentenceIndex !== null}
        onSentenceChange={setNewSentence}
        onSave={handleSaveSentence}
        onDismiss={() => setSentenceDialogVisible(false)}
      />

      <RuleDialog
        visible={ruleDialogVisible}
        ruleDraft={ruleDraft}
        previewInput={rulePreviewInput}
        effectivePattern={effectivePattern}
        effectiveFlags={effectiveFlags}
        onDraftChange={setRuleDraft}
        onPreviewInputChange={setRulePreviewInput}
        onSave={handleSaveRule}
        onDismiss={closeRuleDialog}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  content: {
    paddingBottom: 40,
  },
  tabs: {
    marginBottom: 8,
  },
});
