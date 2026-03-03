import React, { useEffect, useMemo, useState } from 'react';
import { StyleSheet, View, ScrollView } from 'react-native';
import {
  Appbar,
  List,
  IconButton,
  Portal,
  Dialog,
  TextInput,
  Button,
  Text,
  ActivityIndicator,
  Divider,
  SegmentedButtons,
  Switch
} from 'react-native-paper';
import { router, Stack } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useAppAlert } from '../src/context/AlertContext';
import { storageService } from '../src/services/StorageService';
import { RegexCleanupAppliesTo, RegexCleanupRule } from '../src/types';
import { applyTtsCleanupLines, validateRegexCleanupRule } from '../src/utils/textCleanup';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

interface RuleDraft {
  id?: string;
  name: string;
  pattern: string;
  flags: string;
  enabled: boolean;
  appliesTo: RegexCleanupAppliesTo;
}

const EMPTY_RULE_DRAFT: RuleDraft = {
  name: '',
  pattern: '',
  flags: '',
  enabled: true,
  appliesTo: 'both',
};

const targetLabelMap: Record<RegexCleanupAppliesTo, string> = {
  both: 'Download + TTS',
  download: 'Download only',
  tts: 'TTS only',
};

const createRuleId = (): string => {
  return `rule_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
};

type TabValue = 'sentences' | 'regex';

export default function SentenceRemovalScreen() {
  const { showAlert } = useAppAlert();
  const [sentences, setSentences] = useState<string[]>([]);
  const [regexRules, setRegexRules] = useState<RegexCleanupRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<TabValue>('sentences');

  const [sentenceDialogVisible, setSentenceDialogVisible] = useState(false);
  const [newSentence, setNewSentence] = useState('');
  const [editingSentenceIndex, setEditingSentenceIndex] = useState<number | null>(null);

  const [ruleDialogVisible, setRuleDialogVisible] = useState(false);
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(EMPTY_RULE_DRAFT);
  const [rulePreviewInput, setRulePreviewInput] = useState('');

  useEffect(() => {
    void loadData();
  }, []);

  const loadData = async () => {
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
          'Some Rules Were Skipped',
          `${rejectedCount} invalid regex rule${rejectedCount > 1 ? 's were' : ' was'} ignored. Please review your cleanup rules.`
        );
      }
    } finally {
      setLoading(false);
    }
  };

  const saveSentences = async (list: string[]) => {
    setSentences(list);
    await storageService.saveSentenceRemovalList(list);
  };

  const saveRegexRules = async (rules: RegexCleanupRule[]) => {
    setRegexRules(rules);
    await storageService.saveRegexCleanupRules(rules);
  };

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
      setRuleDraft({
        id: rule.id,
        name: rule.name,
        pattern: rule.pattern,
        flags: rule.flags,
        enabled: rule.enabled,
        appliesTo: rule.appliesTo,
      });
    }
    setRuleDialogVisible(true);
  };

  const closeRuleDialog = () => {
    setRuleDialogVisible(false);
    setRuleDraft(EMPTY_RULE_DRAFT);
  };

  const ruleValidation = useMemo(() => {
    return validateRegexCleanupRule({
      name: ruleDraft.name,
      pattern: ruleDraft.pattern,
      flags: ruleDraft.flags,
    });
  }, [ruleDraft.name, ruleDraft.pattern, ruleDraft.flags]);

  const previewOutput = useMemo(() => {
    if (!rulePreviewInput) return '';
    if (!ruleValidation.valid) return '';

    const previewRule: RegexCleanupRule = {
      id: 'preview',
      name: ruleDraft.name.trim() || 'Preview',
      pattern: ruleDraft.pattern.trim(),
      flags: ruleValidation.normalizedFlags || '',
      enabled: true,
      appliesTo: 'both',
    };

    return applyTtsCleanupLines(rulePreviewInput, [previewRule]);
  }, [ruleDraft.name, ruleDraft.pattern, rulePreviewInput, ruleValidation]);

  const handleSaveRule = () => {
    if (!ruleValidation.valid) {
      showAlert('Invalid Rule', ruleValidation.error || 'Please review the regex rule.');
      return;
    }

    const normalizedPattern = ruleDraft.pattern.trim();
    const normalizedFlags = ruleValidation.normalizedFlags || '';
    const normalizedName = ruleDraft.name.trim();

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
          <List.Section>
            <View style={styles.sectionHeader}>
              <List.Subheader>Exact Sentence Removal</List.Subheader>
              <Button icon="plus" mode="text" onPress={() => openSentenceDialog()}>
                Add
              </Button>
            </View>
            <Text variant="bodySmall" style={styles.sectionDescription}>
              Removes exact text matches from downloaded chapters.
            </Text>
            <Divider />
            {sentences.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Text variant="bodyMedium">No sentences in the list.</Text>
              </View>
            ) : (
              sentences.map((item, index) => (
                <View key={`sentence-${index}`}>
                  <List.Item
                    title={item}
                    titleNumberOfLines={3}
                    right={() => (
                      <View style={styles.rowActions}>
                        <IconButton icon="pencil" onPress={() => openSentenceDialog(item, index)} />
                        <IconButton icon="delete" iconColor="red" onPress={() => confirmDeleteSentence(index)} />
                      </View>
                    )}
                    style={styles.listItem}
                  />
                  <Divider />
                </View>
              ))
            )}
          </List.Section>
        )}

        {activeTab === 'regex' && (
          <List.Section>
            <View style={styles.sectionHeader}>
              <List.Subheader>Regex Cleanup Rules</List.Subheader>
              <Button icon="plus" mode="text" onPress={() => openRuleDialog()}>
                Add
              </Button>
            </View>
            <Text variant="bodySmall" style={styles.sectionDescription}>
              Remove patterns like long separators. Rules run in guarded mode and skip invalid patterns.
            </Text>
            <Divider />
            {regexRules.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Text variant="bodyMedium">No regex cleanup rules yet.</Text>
              </View>
            ) : (
              regexRules.map((rule) => (
                <View key={rule.id}>
                  <List.Item
                    title={rule.name}
                    description={`/${rule.pattern}/${rule.flags} • ${targetLabelMap[rule.appliesTo]}`}
                    right={() => (
                      <View style={styles.rowActions}>
                        <Switch value={rule.enabled} onValueChange={(value) => toggleRule(rule.id, value)} />
                        <IconButton icon="pencil" onPress={() => openRuleDialog(rule)} />
                        <IconButton icon="delete" iconColor="red" onPress={() => confirmDeleteRule(rule.id)} />
                      </View>
                    )}
                    style={[styles.listItem, !rule.enabled && styles.disabledItem]}
                  />
                  <Divider />
                </View>
              ))
            )}
          </List.Section>
        )}
      </ScrollView>

      <Portal>
        <Dialog visible={sentenceDialogVisible} onDismiss={() => setSentenceDialogVisible(false)}>
          <Dialog.Title>{editingSentenceIndex !== null ? 'Edit Sentence' : 'Add Sentence'}</Dialog.Title>
          <Dialog.Content>
            <TextInput
              label="Sentence to remove"
              value={newSentence}
              onChangeText={setNewSentence}
              mode="outlined"
              multiline
              numberOfLines={3}
              autoFocus
            />
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setSentenceDialogVisible(false)}>Cancel</Button>
            <Button onPress={handleSaveSentence}>Save</Button>
          </Dialog.Actions>
        </Dialog>

        <Dialog visible={ruleDialogVisible} onDismiss={closeRuleDialog}>
          <Dialog.Title>{ruleDraft.id ? 'Edit Regex Rule' : 'Add Regex Rule'}</Dialog.Title>
          <Dialog.Content>
            <TextInput
              label="Rule Name"
              value={ruleDraft.name}
              onChangeText={(value) => setRuleDraft(prev => ({ ...prev, name: value }))}
              mode="outlined"
              style={styles.input}
            />
            <TextInput
              label="Pattern"
              value={ruleDraft.pattern}
              onChangeText={(value) => setRuleDraft(prev => ({ ...prev, pattern: value }))}
              mode="outlined"
              style={styles.input}
              placeholder="(?:[-=]){5,}"
            />
            <TextInput
              label="Flags"
              value={ruleDraft.flags}
              onChangeText={(value) => setRuleDraft(prev => ({ ...prev, flags: value }))}
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
              onValueChange={(value) => setRuleDraft(prev => ({ ...prev, appliesTo: value as RegexCleanupAppliesTo }))}
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
                onValueChange={(value) => setRuleDraft(prev => ({ ...prev, enabled: value }))}
              />
            </View>

            <Divider style={styles.previewDivider} />
            <Text variant="labelMedium">Test Preview</Text>
            <TextInput
              label="Preview input"
              value={rulePreviewInput}
              onChangeText={setRulePreviewInput}
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
            <Button onPress={closeRuleDialog}>Cancel</Button>
            <Button onPress={handleSaveRule}>Save</Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
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
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  sectionDescription: {
    paddingHorizontal: 16,
    marginBottom: 8,
    opacity: 0.75,
  },
  listItem: {
    paddingVertical: 4,
  },
  rowActions: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  emptyContainer: {
    paddingHorizontal: 16,
    paddingVertical: 20,
  },
  disabledItem: {
    opacity: 0.5,
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
