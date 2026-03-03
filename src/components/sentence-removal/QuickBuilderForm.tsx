import React, { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { TextInput, Text, Switch, List } from 'react-native-paper';
import { QuickBuilderConfig } from '../../types/sentenceRemoval';
import { generateQuickPattern } from '../../utils/regexBuilder';

interface QuickBuilderFormProps {
  config: QuickBuilderConfig;
  onChange: (config: QuickBuilderConfig) => void;
}

export function QuickBuilderForm({ config, onChange }: QuickBuilderFormProps) {
  const { characters, minCount, wholeLine } = config;
  const [showAdvanced, setShowAdvanced] = useState(false);
  
  const generatedPattern = generateQuickPattern(config);
  const isValid = characters.length > 0 && minCount >= 1;

  return (
    <View style={styles.container}>
      <TextInput
        label="Character(s)"
        value={characters}
        onChangeText={(value) => onChange({ ...config, characters: value })}
        mode="outlined"
        style={styles.input}
        placeholder="e.g. =, -, ##, **"
        autoCapitalize="none"
        autoCorrect={false}
      />
      <Text variant="bodySmall" style={styles.helpText}>
        Enter the character(s) that form the separator. Use 1-2 characters for best results.
      </Text>

      <TextInput
        label="Minimum repetitions"
        value={minCount.toString()}
        onChangeText={(value) => {
          const num = parseInt(value, 10);
          if (!isNaN(num) && num >= 1) {
            onChange({ ...config, minCount: num });
          } else if (value === '') {
            onChange({ ...config, minCount: 1 });
          }
        }}
        onBlur={() => {
          if (minCount < 1) {
            onChange({ ...config, minCount: 1 });
          }
        }}
        mode="outlined"
        style={styles.input}
        keyboardType="numeric"
      />
      <Text variant="bodySmall" style={styles.helpText}>
        Match when the character repeats at least this many times (e.g., 5 means ===== or more).
      </Text>

      <List.Accordion
        title="Advanced Settings"
        expanded={showAdvanced}
        onPress={() => setShowAdvanced(!showAdvanced)}
        style={styles.accordion}
      >
        <View style={styles.switchRow}>
          <View style={styles.switchText}>
            <Text variant="bodyMedium">Whole line only</Text>
            <Text variant="bodySmall" style={styles.switchHint}>
              Match entire lines, not inline patterns
            </Text>
          </View>
          <Switch
            value={wholeLine}
            onValueChange={(value) => onChange({ ...config, wholeLine: value })}
          />
        </View>

        <View style={styles.previewSection}>
          <Text variant="labelMedium" style={styles.previewLabel}>Generated Pattern</Text>
          <View style={[styles.previewBox, !isValid && styles.previewBoxEmpty]}>
            <Text variant="bodySmall" style={styles.patternText}>
              {isValid ? `/${generatedPattern.pattern}/${generatedPattern.flags}` : 'Enter character(s) to generate pattern'}
            </Text>
          </View>
        </View>
      </List.Accordion>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 4,
  },
  input: {
    marginBottom: 2,
  },
  helpText: {
    opacity: 0.7,
    marginBottom: 8,
    paddingHorizontal: 4,
  },
  accordion: {
    marginVertical: 8,
    paddingHorizontal: 0,
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
  },
  switchText: {
    flex: 1,
    marginRight: 12,
  },
  switchHint: {
    opacity: 0.6,
    marginTop: 2,
  },
  previewSection: {
    marginTop: 8,
  },
  previewLabel: {
    marginBottom: 6,
  },
  previewBox: {
    backgroundColor: 'rgba(0, 0, 0, 0.05)',
    borderRadius: 8,
    padding: 12,
  },
  previewBoxEmpty: {
    opacity: 0.6,
  },
  patternText: {
    fontFamily: 'monospace',
  },
});
